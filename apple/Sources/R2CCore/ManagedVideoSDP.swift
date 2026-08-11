import Foundation

public struct ManagedVideoICECandidate: Equatable, Sendable {
    public let sdp: String
    public let mediaLineIndex: Int32

    public init(sdp: String, mediaLineIndex: Int32) {
        self.sdp = sdp
        self.mediaLineIndex = mediaLineIndex
    }
}

public enum ManagedVideoSDP {
    /// Return a non-trickle SDP description containing candidates reported by
    /// libwebrtc after setLocalDescription().  Apple libwebrtc does not always
    /// fold those callbacks back into localDescription before the one-shot
    /// answer is sent.
    public static func withICECandidates(
        _ value: String,
        candidates: [ManagedVideoICECandidate]
    ) -> String {
        var lines = value
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        for candidate in candidates {
            var candidateLine = candidate.sdp.trimmingCharacters(
                in: .whitespacesAndNewlines
            )
            guard !candidateLine.isEmpty else { continue }
            if !candidateLine.hasPrefix("a=") {
                candidateLine = "a=" + candidateLine
            }
            guard !lines.contains(candidateLine) else { continue }

            let targetMediaLine = max(0, Int(candidate.mediaLineIndex))
            var currentMediaLine = -1
            var insertAt = lines.endIndex
            var foundTarget = false
            for index in lines.indices {
                let line = lines[index]
                if line.hasPrefix("m=") {
                    currentMediaLine += 1
                    if foundTarget {
                        insertAt = index
                        break
                    }
                    foundTarget = currentMediaLine == targetMediaLine
                } else if foundTarget && line == "a=end-of-candidates" {
                    insertAt = index
                    break
                }
            }
            if foundTarget {
                lines.insert(candidateLine, at: insertAt)
            }
        }
        return lines.joined(separator: "\r\n") + "\r\n"
    }

    public static func hasRoutableICECandidate(_ value: String) -> Bool {
        value.contains(" typ srflx ") || value.contains(" typ relay ")
    }
}
