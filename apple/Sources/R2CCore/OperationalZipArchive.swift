import Compression
import Foundation

public enum OperationalZipError: Error, Sendable, Equatable {
    case invalidArchive
    case unsupportedCompression(UInt16)
    case unsafePath
    case sizeLimitExceeded
    case decompressionFailed
}

public enum OperationalZipArchive {
    public struct Entry: Sendable, Equatable {
        public let path: String
        public let data: Data

        public init(path: String, data: Data) {
            self.path = path
            self.data = data
        }
    }

    public static func encode(_ entries: [Entry], compress: Bool = false) throws -> Data {
        var output = Data()
        var central = Data()
        for entry in entries {
            let path = try safePath(entry.path)
            guard let name = path.data(using: .utf8), name.count <= Int(UInt16.max), entry.data.count <= Int(UInt32.max) else {
                throw OperationalZipError.sizeLimitExceeded
            }
            let compressed = compress ? deflate(entry.data) : nil
            let payload = compressed.flatMap { $0.count < entry.data.count ? $0 : nil } ?? entry.data
            let method: UInt16 = payload.count < entry.data.count ? 8 : 0
            let offset = UInt32(output.count)
            let crc = crc32(entry.data)
            output.appendLE(UInt32(0x04034b50))
            output.appendLE(UInt16(20)); output.appendLE(UInt16(0)); output.appendLE(method)
            output.appendLE(UInt16(0)); output.appendLE(UInt16(0)); output.appendLE(crc)
            output.appendLE(UInt32(payload.count)); output.appendLE(UInt32(entry.data.count))
            output.appendLE(UInt16(name.count)); output.appendLE(UInt16(0))
            output.append(name); output.append(payload)

            central.appendLE(UInt32(0x02014b50))
            central.appendLE(UInt16(20)); central.appendLE(UInt16(20)); central.appendLE(UInt16(0)); central.appendLE(method)
            central.appendLE(UInt16(0)); central.appendLE(UInt16(0)); central.appendLE(crc)
            central.appendLE(UInt32(payload.count)); central.appendLE(UInt32(entry.data.count))
            central.appendLE(UInt16(name.count)); central.appendLE(UInt16(0)); central.appendLE(UInt16(0))
            central.appendLE(UInt16(0)); central.appendLE(UInt16(0)); central.appendLE(UInt32(0)); central.appendLE(offset)
            central.append(name)
        }
        guard entries.count <= Int(UInt16.max), central.count <= Int(UInt32.max), output.count <= Int(UInt32.max) else {
            throw OperationalZipError.sizeLimitExceeded
        }
        let centralOffset = UInt32(output.count)
        output.append(central)
        output.appendLE(UInt32(0x06054b50))
        output.appendLE(UInt16(0)); output.appendLE(UInt16(0))
        output.appendLE(UInt16(entries.count)); output.appendLE(UInt16(entries.count))
        output.appendLE(UInt32(central.count)); output.appendLE(centralOffset); output.appendLE(UInt16(0))
        return output
    }

    public static func decode(
        _ archive: Data,
        maximumEntries: Int = 300_000,
        maximumExpandedBytes: Int = 4 * 1_024 * 1_024 * 1_024
    ) throws -> [Entry] {
        guard archive.count >= 22,
              let eocd = archive.lastIndex(ofSignature: 0x06054b50, withinLast: 65_557)
        else { throw OperationalZipError.invalidArchive }
        let count = Int(try archive.u16(at: eocd + 10))
        let centralSize = Int(try archive.u32(at: eocd + 12))
        var cursor = Int(try archive.u32(at: eocd + 16))
        guard count <= maximumEntries, cursor >= 0, centralSize >= 0, cursor + centralSize <= archive.count else {
            throw OperationalZipError.sizeLimitExceeded
        }
        var entries: [Entry] = []
        var expanded = 0
        for _ in 0 ..< count {
            guard try archive.u32(at: cursor) == 0x02014b50 else { throw OperationalZipError.invalidArchive }
            let method = try archive.u16(at: cursor + 10)
            let compressedSize = Int(try archive.u32(at: cursor + 20))
            let uncompressedSize = Int(try archive.u32(at: cursor + 24))
            let nameLength = Int(try archive.u16(at: cursor + 28))
            let extraLength = Int(try archive.u16(at: cursor + 30))
            let commentLength = Int(try archive.u16(at: cursor + 32))
            let localOffset = Int(try archive.u32(at: cursor + 42))
            let nameStart = cursor + 46
            guard nameStart + nameLength <= archive.count,
                  let rawPath = String(data: archive[nameStart ..< nameStart + nameLength], encoding: .utf8)
            else { throw OperationalZipError.invalidArchive }
            let path = try safePath(rawPath)
            guard try archive.u32(at: localOffset) == 0x04034b50 else { throw OperationalZipError.invalidArchive }
            let localNameLength = Int(try archive.u16(at: localOffset + 26))
            let localExtraLength = Int(try archive.u16(at: localOffset + 28))
            let dataStart = localOffset + 30 + localNameLength + localExtraLength
            guard compressedSize >= 0, dataStart >= 0, dataStart + compressedSize <= archive.count else {
                throw OperationalZipError.invalidArchive
            }
            expanded += uncompressedSize
            guard expanded <= maximumExpandedBytes else { throw OperationalZipError.sizeLimitExceeded }
            let compressed = Data(archive[dataStart ..< dataStart + compressedSize])
            let data: Data
            switch method {
            case 0: data = compressed
            case 8: data = try inflate(compressed, expectedSize: uncompressedSize)
            default: throw OperationalZipError.unsupportedCompression(method)
            }
            guard data.count == uncompressedSize else { throw OperationalZipError.decompressionFailed }
            entries.append(Entry(path: path, data: data))
            cursor = nameStart + nameLength + extraLength + commentLength
        }
        return entries
    }

    private static func inflate(_ input: Data, expectedSize: Int) throws -> Data {
        guard expectedSize >= 0 else { throw OperationalZipError.decompressionFailed }
        var output = Data(count: expectedSize)
        let count = output.withUnsafeMutableBytes { destination in
            input.withUnsafeBytes { source in
                compression_decode_buffer(
                    destination.bindMemory(to: UInt8.self).baseAddress!, expectedSize,
                    source.bindMemory(to: UInt8.self).baseAddress!, input.count,
                    nil, COMPRESSION_ZLIB
                )
            }
        }
        guard count == expectedSize else { throw OperationalZipError.decompressionFailed }
        return output
    }

    private static func deflate(_ input: Data) -> Data? {
        guard !input.isEmpty else { return nil }
        let capacity = input.count + max(64, input.count / 1_000 + 16)
        var output = Data(count: capacity)
        let count = output.withUnsafeMutableBytes { destination in
            input.withUnsafeBytes { source in
                compression_encode_buffer(
                    destination.bindMemory(to: UInt8.self).baseAddress!, capacity,
                    source.bindMemory(to: UInt8.self).baseAddress!, input.count,
                    nil, COMPRESSION_ZLIB
                )
            }
        }
        guard count > 0 else { return nil }
        output.removeSubrange(count ..< output.count)
        return output
    }

    private static func safePath(_ path: String) throws -> String {
        let normalized = path.replacingOccurrences(of: "\\", with: "/").trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !normalized.isEmpty, !normalized.hasPrefix("/"),
              !normalized.split(separator: "/").contains(".."), !normalized.contains(":")
        else { throw OperationalZipError.unsafePath }
        return normalized
    }

    private static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xffff_ffff
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0 ..< 8 { crc = (crc >> 1) ^ ((crc & 1) == 1 ? 0xedb8_8320 : 0) }
        }
        return crc ^ 0xffff_ffff
    }
}

private extension Data {
    mutating func appendLE<T: FixedWidthInteger>(_ value: T) {
        var value = value.littleEndian
        Swift.withUnsafeBytes(of: &value) { append(contentsOf: $0) }
    }

    func u16(at offset: Int) throws -> UInt16 {
        guard offset >= 0, offset + 2 <= count else { throw OperationalZipError.invalidArchive }
        return UInt16(self[offset]) | UInt16(self[offset + 1]) << 8
    }

    func u32(at offset: Int) throws -> UInt32 {
        guard offset >= 0, offset + 4 <= count else { throw OperationalZipError.invalidArchive }
        return UInt32(self[offset]) | UInt32(self[offset + 1]) << 8 | UInt32(self[offset + 2]) << 16 | UInt32(self[offset + 3]) << 24
    }

    func lastIndex(ofSignature signature: UInt32, withinLast limit: Int) -> Int? {
        guard count >= 4 else { return nil }
        let start = Swift.max(0, count - limit)
        for index in stride(from: count - 4, through: start, by: -1) {
            if (try? u32(at: index)) == signature { return index }
        }
        return nil
    }
}
