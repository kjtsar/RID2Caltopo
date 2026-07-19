import Foundation
import R2CCore

actor AppleTrackArchiveStore {
    enum ArchiveError: Error {
        case documentsDirectoryUnavailable
    }

    private let rootURL: URL?

    init(fileManager: FileManager = .default) {
        rootURL = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first?
            .appendingPathComponent("RID2Caltopo", isDirectory: true)
            .appendingPathComponent("Tracks", isDirectory: true)
    }

    func archive(
        track: RidAircraftTrack,
        metadata: RidTrackArchiveMetadata
    ) throws -> URL {
        guard let rootURL else { throw ArchiveError.documentsDirectoryUnavailable }
        let directory = rootURL.appendingPathComponent(dayDirectoryName(), isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = directory.appendingPathComponent(RidTrackGeoJSON.suggestedFilename(for: track))
        try RidTrackGeoJSON.encode(track: track, metadata: metadata).write(to: destination, options: .atomic)
        return destination
    }

    private func dayDirectoryName() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: Date())
    }
}
