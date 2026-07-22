import Foundation

public enum ExternalRIDObservationDecoderError: Error, Sendable, Equatable {
    case malformedJSON
    case missingAircraftID
    case invalidCoordinate
}

/// Decodes one newline-free UDP JSON datagram from a network Remote ID receiver.
/// This normalized boundary is intentionally independent of receiver hardware.
public enum ExternalRIDObservationDecoder {
    public static func decode(_ data: Data, receivedAt: Date = Date()) throws -> RidObservation {
        let packet: Packet
        do {
            packet = try JSONDecoder().decode(Packet.self, from: data)
        } catch {
            throw ExternalRIDObservationDecoderError.malformedJSON
        }
        let aircraftID = packet.aircraftID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !aircraftID.isEmpty else {
            throw ExternalRIDObservationDecoderError.missingAircraftID
        }
        guard packet.latitude.isFinite,
              packet.longitude.isFinite,
              (-90 ... 90).contains(packet.latitude),
              (-180 ... 180).contains(packet.longitude)
        else {
            throw ExternalRIDObservationDecoderError.invalidCoordinate
        }
        let timestamp = packet.timestampMilliseconds.map {
            Date(timeIntervalSince1970: $0 / 1_000)
        } ?? receivedAt
        return RidObservation(
            source: packet.source.flatMap(RidObservation.Source.init(rawValue:)) ?? .externalReceiver,
            aircraftId: aircraftID,
            receivedAt: timestamp,
            latitude: packet.latitude,
            longitude: packet.longitude,
            altitudeMeters: packet.altitudeMeters,
            heightMeters: packet.heightMeters,
            heightReference: packet.heightReference.flatMap(RidObservation.HeightReference.init(rawValue:)),
            headingDegrees: packet.headingDegrees,
            speedMetersPerSecond: packet.speedMetersPerSecond,
            operatorLatitude: packet.operatorLatitude,
            operatorLongitude: packet.operatorLongitude,
            signalStrengthDbm: packet.signalStrengthDbm
        )
    }

    private struct Packet: Decodable {
        let aircraftID: String
        let source: String?
        let timestampMilliseconds: Double?
        let latitude: Double
        let longitude: Double
        let altitudeMeters: Double?
        let heightMeters: Double?
        let heightReference: String?
        let headingDegrees: Double?
        let speedMetersPerSecond: Double?
        let operatorLatitude: Double?
        let operatorLongitude: Double?
        let signalStrengthDbm: Int?

        enum CodingKeys: String, CodingKey {
            case aircraftID = "aircraft_id"
            case source
            case timestampMilliseconds = "timestamp_ms"
            case latitude
            case longitude
            case altitudeMeters = "altitude_m"
            case heightMeters = "height_m"
            case heightReference = "height_reference"
            case headingDegrees = "heading_deg"
            case speedMetersPerSecond = "speed_mps"
            case operatorLatitude = "operator_latitude"
            case operatorLongitude = "operator_longitude"
            case signalStrengthDbm = "rssi_dbm"
        }
    }
}
