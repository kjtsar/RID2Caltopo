import Foundation

public struct OpenDroneIDAdvertisement: Equatable, Sendable {
    public let messageCounter: UInt8
    public let messages: [OpenDroneIDMessage]

    public init(messageCounter: UInt8, messages: [OpenDroneIDMessage]) {
        self.messageCounter = messageCounter
        self.messages = messages
    }
}

public struct OpenDroneIDMessage: Equatable, Sendable {
    public enum Kind: UInt8, Equatable, Sendable {
        case basicID = 0
        case location = 1
        case authentication = 2
        case selfID = 3
        case system = 4
        case operatorID = 5
        case messagePack = 15
    }

    public indirect enum Payload: Equatable, Sendable {
        case basicID(OpenDroneIDBasicID)
        case location(OpenDroneIDLocation)
        case system(OpenDroneIDSystem)
        case messagePack([OpenDroneIDMessage])
        case opaque(kind: Kind, bytes: Data)
    }

    public let kind: Kind
    public let protocolVersion: UInt8
    public let payload: Payload
}

public struct OpenDroneIDBasicID: Equatable, Sendable {
    public let idType: UInt8
    public let aircraftType: UInt8
    public let uasID: String

    public var isSerialNumber: Bool { idType == 1 }
}

public struct OpenDroneIDLocation: Equatable, Sendable {
    public let status: UInt8
    public let heightType: UInt8
    public let latitude: Double
    public let longitude: Double
    public let pressureAltitudeMeters: Double
    public let geodeticAltitudeMeters: Double
    public let heightMeters: Double
    public let directionDegrees: Double
    public let horizontalSpeedMetersPerSecond: Double
    public let verticalSpeedMetersPerSecond: Double
    public let timestampTenths: UInt16

    public var preferredAltitudeMeters: Double? {
        for candidate in [pressureAltitudeMeters, geodeticAltitudeMeters, heightMeters]
        where candidate > -999.0 {
            return candidate
        }
        return nil
    }
}

public struct OpenDroneIDSystem: Equatable, Sendable {
    public let operatorLatitude: Double
    public let operatorLongitude: Double
    public let operatorAltitudeMeters: Double
    public let timestamp: UInt32
}

public enum OpenDroneIDParserError: Error, Equatable, Sendable {
    case invalidApplicationCode(UInt8?)
    case truncatedMessage(expected: Int, actual: Int)
    case unknownMessageType(UInt8)
    case invalidMessagePack(size: Int, count: Int, availableBytes: Int)
    case invalidRawDatagramLength(Int)
}

public enum OpenDroneIDParser {
    public static let bluetoothServiceUUID = "FFFA"
    public static let applicationCode: UInt8 = 0x0D
    public static let messageSize = 25
    public static let maximumMessagesPerPack = 9

    /// Parses the value from CoreBluetooth's `CBAdvertisementDataServiceDataKey`
    /// for service UUID FFFA. CoreBluetooth has already removed the AD length,
    /// AD type, and 16-bit UUID that precede this payload in the raw packet.
    public static func parseBluetoothServiceData(_ data: Data) throws -> OpenDroneIDAdvertisement {
        guard data.first == applicationCode else {
            throw OpenDroneIDParserError.invalidApplicationCode(data.first)
        }
        guard data.count >= 2 else {
            throw OpenDroneIDParserError.truncatedMessage(expected: 2, actual: data.count)
        }

        let counter = data[data.startIndex + 1]
        let messageData = Data(data.dropFirst(2))
        let message = try parseMessage(messageData)
        let messages: [OpenDroneIDMessage]
        if case let .messagePack(contents) = message.payload {
            messages = contents
        } else {
            messages = [message]
        }
        return OpenDroneIDAdvertisement(messageCounter: counter, messages: messages)
    }

    /// Parses the compact payload an external Wi-Fi radio can forward over UDP.
    /// The payload may be either the complete Bluetooth FFFA service-data value,
    /// one raw 25-byte ASTM message, a concatenation of raw messages, or a raw
    /// ASTM Message Pack. Full 802.11 management-frame headers are intentionally
    /// outside this transport contract because radio adapters expose them in
    /// device-specific forms.
    public static func parseExternalDatagram(_ data: Data) throws -> OpenDroneIDAdvertisement {
        if data.first == applicationCode, data.count >= messageSize + 2 {
            return try parseBluetoothServiceData(data)
        }
        guard data.count >= messageSize else {
            throw OpenDroneIDParserError.invalidRawDatagramLength(data.count)
        }

        if data.first.map({ $0 >> 4 }) == OpenDroneIDMessage.Kind.messagePack.rawValue {
            let pack = try parseMessage(data)
            guard case let .messagePack(messages) = pack.payload else {
                preconditionFailure("Message-pack header produced a different payload")
            }
            return OpenDroneIDAdvertisement(messageCounter: 0, messages: messages)
        }

        guard data.count.isMultiple(of: messageSize) else {
            throw OpenDroneIDParserError.invalidRawDatagramLength(data.count)
        }
        let messages = try stride(from: 0, to: data.count, by: messageSize).map { offset in
            try parseMessage(data.subdata(in: offset ..< offset + messageSize))
        }
        return OpenDroneIDAdvertisement(messageCounter: 0, messages: messages)
    }

    public static func parseMessage(_ data: Data) throws -> OpenDroneIDMessage {
        guard data.count >= messageSize else {
            throw OpenDroneIDParserError.truncatedMessage(expected: messageSize, actual: data.count)
        }

        let header = data[data.startIndex]
        let typeValue = header >> 4
        guard let kind = OpenDroneIDMessage.Kind(rawValue: typeValue) else {
            throw OpenDroneIDParserError.unknownMessageType(typeValue)
        }
        let version = header & 0x0F

        let payload: OpenDroneIDMessage.Payload
        switch kind {
        case .basicID:
            payload = .basicID(parseBasicID(data))
        case .location:
            payload = .location(parseLocation(data))
        case .system:
            payload = .system(parseSystem(data))
        case .messagePack:
            payload = .messagePack(try parseMessagePack(data))
        case .authentication, .selfID, .operatorID:
            payload = .opaque(kind: kind, bytes: Data(data.prefix(messageSize)))
        }

        return OpenDroneIDMessage(kind: kind, protocolVersion: version, payload: payload)
    }

    private static func parseBasicID(_ data: Data) -> OpenDroneIDBasicID {
        let type = byte(data, 1)
        let idBytes = dataBytes(data, 2 ..< 22)
        let uasID = String(bytes: idBytes.prefix { $0 != 0 }, encoding: .ascii) ?? ""
        return OpenDroneIDBasicID(
            idType: type >> 4,
            aircraftType: type & 0x0F,
            uasID: uasID
        )
    }

    private static func parseLocation(_ data: Data) -> OpenDroneIDLocation {
        let flags = byte(data, 1)
        let eastWest = (flags >> 1) & 0x01
        let speedMultiplier = flags & 0x01
        let rawDirection = byte(data, 2)
        let rawHorizontalSpeed = byte(data, 3)
        let rawVerticalSpeed = Int8(bitPattern: byte(data, 4))

        return OpenDroneIDLocation(
            status: flags >> 4,
            heightType: (flags >> 2) & 0x01,
            latitude: Double(int32LE(data, 5)) * 1e-7,
            longitude: Double(int32LE(data, 9)) * 1e-7,
            pressureAltitudeMeters: altitude(uint16LE(data, 13)),
            geodeticAltitudeMeters: altitude(uint16LE(data, 15)),
            heightMeters: altitude(uint16LE(data, 17)),
            directionDegrees: Double(rawDirection) + (eastWest == 0 ? 0 : 180),
            horizontalSpeedMetersPerSecond: speedMultiplier == 0
                ? Double(rawHorizontalSpeed) * 0.25
                : Double(rawHorizontalSpeed) * 0.75 + 63.75,
            verticalSpeedMetersPerSecond: Double(rawVerticalSpeed) * 0.5,
            timestampTenths: uint16LE(data, 21)
        )
    }

    private static func parseSystem(_ data: Data) -> OpenDroneIDSystem {
        OpenDroneIDSystem(
            operatorLatitude: Double(int32LE(data, 2)) * 1e-7,
            operatorLongitude: Double(int32LE(data, 6)) * 1e-7,
            operatorAltitudeMeters: altitude(uint16LE(data, 17)),
            timestamp: uint32LE(data, 19)
        )
    }

    private static func parseMessagePack(_ data: Data) throws -> [OpenDroneIDMessage] {
        let size = Int(byte(data, 1))
        let count = Int(byte(data, 2))
        let required = 3 + size * count
        guard size == messageSize,
              count > 0,
              count <= maximumMessagesPerPack,
              data.count >= required
        else {
            throw OpenDroneIDParserError.invalidMessagePack(
                size: size,
                count: count,
                availableBytes: data.count
            )
        }

        return try (0 ..< count).map { index in
            let start = 3 + index * size
            return try parseMessage(data.subdata(in: start ..< start + size))
        }
    }

    private static func altitude(_ raw: UInt16) -> Double {
        Double(raw) / 2.0 - 1000.0
    }

    private static func byte(_ data: Data, _ offset: Int) -> UInt8 {
        data[data.startIndex + offset]
    }

    private static func dataBytes(_ data: Data, _ range: Range<Int>) -> Data {
        data.subdata(in: range)
    }

    private static func uint16LE(_ data: Data, _ offset: Int) -> UInt16 {
        UInt16(byte(data, offset)) | UInt16(byte(data, offset + 1)) << 8
    }

    private static func int32LE(_ data: Data, _ offset: Int) -> Int32 {
        Int32(bitPattern: uint32LE(data, offset))
    }

    private static func uint32LE(_ data: Data, _ offset: Int) -> UInt32 {
        UInt32(byte(data, offset))
            | UInt32(byte(data, offset + 1)) << 8
            | UInt32(byte(data, offset + 2)) << 16
            | UInt32(byte(data, offset + 3)) << 24
    }
}
