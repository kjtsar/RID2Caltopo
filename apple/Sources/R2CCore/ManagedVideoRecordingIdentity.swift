import CryptoKit
import Foundation

public enum ManagedVideoRecordingIdentity {
    public struct Candidate: Sendable, Equatable {
        public let sessionID: String
        public let designator: String
        public let startedAt: Date
        public let endedAt: Date

        public init(
            sessionID: String,
            designator: String,
            startedAt: Date,
            endedAt: Date
        ) {
            self.sessionID = sessionID
            self.designator = designator
            self.startedAt = startedAt
            self.endedAt = endedAt
        }
    }

    public static let trackAssociationGrace: TimeInterval = 30

    public static func localizedMediaMTXRecordingURL(
        for url: URL,
        timeZone: TimeZone = .current
    ) -> URL? {
        let filename = url.deletingPathExtension().lastPathComponent
        let pattern = #"\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}-(\d{6})"#
        guard let timestampRange = filename.range(of: pattern, options: .regularExpression) else {
            return nil
        }

        let rawTimestamp = String(filename[timestampRange])
        let utcFormatter = DateFormatter()
        utcFormatter.locale = Locale(identifier: "en_US_POSIX")
        utcFormatter.calendar = Calendar(identifier: .gregorian)
        utcFormatter.timeZone = TimeZone(secondsFromGMT: 0)
        utcFormatter.dateFormat = "yyyy-MM-dd_HH-mm-ss-SSSSSS"
        utcFormatter.isLenient = false
        guard let date = utcFormatter.date(from: rawTimestamp) else { return nil }

        let localFormatter = DateFormatter()
        localFormatter.locale = Locale(identifier: "en_US_POSIX")
        localFormatter.calendar = Calendar(identifier: .gregorian)
        localFormatter.timeZone = timeZone
        localFormatter.dateFormat = "ddMMMyyyy_HHmmss_z"

        var localizedFilename = filename
        localizedFilename.replaceSubrange(
            timestampRange,
            with: localFormatter.string(from: date)
        )
        return url
            .deletingLastPathComponent()
            .appendingPathComponent(localizedFilename)
            .appendingPathExtension(url.pathExtension)
    }

    public static func sessionID(forPath path: String) -> String {
        let hex = SHA256.hash(data: Data(path.utf8))
            .prefix(16)
            .map { String(format: "%02x", $0) }
            .joined()
        return "\(hex.prefix(8))-\(hex.dropFirst(8).prefix(4))-\(hex.dropFirst(12).prefix(4))-\(hex.dropFirst(16).prefix(4))-\(hex.dropFirst(20).prefix(12))"
    }

    /// MediaMTX's raw UTC name remains visible while the current segment is
    /// still being written. Only localized names are safe to advertise as
    /// completed recordings because finalization renames the file and changes
    /// the path-derived session identifier.
    public static func isCompletedRecordingPath(_ path: String) -> Bool {
        localizedMediaMTXRecordingURL(for: URL(fileURLWithPath: path)) == nil
    }

    public static func recordingStartedAt(forPath path: String) -> Date? {
        let filename = URL(fileURLWithPath: path)
            .deletingPathExtension()
            .lastPathComponent
        let patterns: [(String, String, TimeZone?)] = [
            // MediaMTX expands its recording path in UTC (CT/Z time).
            (#"\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}-\d{6}"#,
             "yyyy-MM-dd_HH-mm-ss-SSSSSS",
             TimeZone(secondsFromGMT: 0)!),
            // Continue reading the earlier explicit-zone-plus-offset convention.
            (#"\d{2}[A-Z][a-z]{2}\d{4}_\d{6}_[A-Za-z]{1,8}[+-]\d{4}"#,
             "ddMMMyyyy_HHmmss_zZ",
             nil),
            // Current completed files carry a concise local zone designation.
            (#"\d{2}[A-Z][a-z]{2}\d{4}_\d{6}_[A-Za-z]{1,8}"#,
             "ddMMMyyyy_HHmmss_z",
             nil),
            // Continue reading older local files that predate the explicit zone suffix.
            (#"\d{2}[A-Z][a-z]{2}\d{4}_\d{6}"#, "ddMMMyyyy_HHmmss", .current),
        ]
        for (pattern, format, timeZone) in patterns {
            guard let range = filename.range(of: pattern, options: .regularExpression) else {
                continue
            }
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.calendar = Calendar(identifier: .gregorian)
            if let timeZone { formatter.timeZone = timeZone }
            formatter.dateFormat = format
            if let date = formatter.date(from: String(filename[range])) {
                return date
            }
        }
        return nil
    }

    public static func availableRecordingURL(
        preferred: URL,
        fileExists: (String) -> Bool
    ) -> URL {
        guard fileExists(preferred.path) else { return preferred }
        let directory = preferred.deletingLastPathComponent()
        let stem = preferred.deletingPathExtension().lastPathComponent
        let pathExtension = preferred.pathExtension
        for sequence in 2...999 {
            var candidate = directory.appendingPathComponent("\(stem)-\(sequence)")
            if !pathExtension.isEmpty {
                candidate.appendPathExtension(pathExtension)
            }
            if !fileExists(candidate.path) { return candidate }
        }
        return preferred
    }

    public static func recording(
        matching designators: [String],
        trackStartedAt: Date,
        trackEndedAt: Date,
        candidates: [Candidate]
    ) -> Candidate? {
        guard trackEndedAt >= trackStartedAt else { return nil }
        let normalized = Set(
            designators
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
                .filter { !$0.isEmpty }
        )
        guard !normalized.isEmpty else { return nil }
        let allowedStart = trackStartedAt.addingTimeInterval(-trackAssociationGrace)
        let allowedEnd = trackEndedAt.addingTimeInterval(trackAssociationGrace)
        return candidates
            .filter {
                normalized.contains(
                    $0.designator.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                ) && $0.endedAt >= allowedStart && $0.startedAt <= allowedEnd
            }
            .sorted { lhs, rhs in
                let lhsOverlap = overlap(
                    trackStartedAt: trackStartedAt,
                    trackEndedAt: trackEndedAt,
                    candidate: lhs
                )
                let rhsOverlap = overlap(
                    trackStartedAt: trackStartedAt,
                    trackEndedAt: trackEndedAt,
                    candidate: rhs
                )
                if lhsOverlap != rhsOverlap { return lhsOverlap > rhsOverlap }
                let lhsDistance = boundaryDistance(
                    trackStartedAt: trackStartedAt,
                    trackEndedAt: trackEndedAt,
                    candidate: lhs
                )
                let rhsDistance = boundaryDistance(
                    trackStartedAt: trackStartedAt,
                    trackEndedAt: trackEndedAt,
                    candidate: rhs
                )
                if lhsDistance != rhsDistance { return lhsDistance < rhsDistance }
                return lhs.endedAt > rhs.endedAt
            }
            .first
    }

    private static func overlap(
        trackStartedAt: Date,
        trackEndedAt: Date,
        candidate: Candidate
    ) -> TimeInterval {
        max(0, min(trackEndedAt, candidate.endedAt).timeIntervalSince(
            max(trackStartedAt, candidate.startedAt)
        ))
    }

    private static func boundaryDistance(
        trackStartedAt: Date,
        trackEndedAt: Date,
        candidate: Candidate
    ) -> TimeInterval {
        min(
            abs(candidate.startedAt.timeIntervalSince(trackStartedAt)),
            abs(candidate.endedAt.timeIntervalSince(trackEndedAt))
        )
    }
}
