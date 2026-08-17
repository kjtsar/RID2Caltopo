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

    public static func sessionID(forPath path: String) -> String {
        let hex = SHA256.hash(data: Data(path.utf8))
            .prefix(16)
            .map { String(format: "%02x", $0) }
            .joined()
        return "\(hex.prefix(8))-\(hex.dropFirst(8).prefix(4))-\(hex.dropFirst(12).prefix(4))-\(hex.dropFirst(16).prefix(4))-\(hex.dropFirst(20).prefix(12))"
    }

    public static func recordingStartedAt(forPath path: String) -> Date? {
        let filename = URL(fileURLWithPath: path)
            .deletingPathExtension()
            .lastPathComponent
        let patterns = [
            (#"\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}-\d{6}"#, "yyyy-MM-dd_HH-mm-ss-SSSSSS"),
            (#"\d{2}[A-Z][a-z]{2}\d{4}_\d{6}"#, "ddMMMyyyy_HHmmss"),
        ]
        for (pattern, format) in patterns {
            guard let range = filename.range(of: pattern, options: .regularExpression) else {
                continue
            }
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.calendar = Calendar(identifier: .gregorian)
            formatter.timeZone = .current
            formatter.dateFormat = format
            if let date = formatter.date(from: String(filename[range])) {
                return date
            }
        }
        return nil
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
