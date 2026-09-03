import Foundation

public struct OperationalStreamDesignatorCandidate: Equatable, Sendable {
    public let id: String
    public let designator: String

    public init(id: String, designator: String) {
        self.id = id
        self.designator = designator
    }
}

public enum OperationalStreamDesignatorMatch {
    public static func automaticPairingStreamID(
        confirmedCandidateID: String,
        activeCandidateIDs: [String],
        liveUnpairedStreamIDs: [String]
    ) -> String? {
        let confirmed = confirmedCandidateID.trimmingCharacters(in: .whitespacesAndNewlines)
        let candidates = activeCandidateIDs
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        let streams = Array(Set(liveUnpairedStreamIDs
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }))
        guard !confirmed.isEmpty,
              candidates.count == 1,
              candidates[0] == confirmed,
              streams.count == 1
        else { return nil }
        return streams[0]
    }

    /// Returns a suggestion only when one candidate has a strictly smaller edit distance.
    /// The suggestion is for picker presentation and must not automatically bind telemetry.
    public static func closestCandidateID(
        streamDesignator: String,
        candidates: [OperationalStreamDesignatorCandidate]
    ) -> String? {
        let stream = normalized(streamDesignator)
        guard !stream.isEmpty else { return nil }

        let ranked = candidates.compactMap { candidate -> (id: String, distance: Int)? in
            let designator = normalized(candidate.designator)
            guard !candidate.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !designator.isEmpty
            else { return nil }
            return (candidate.id, levenshteinDistance(stream, designator))
        }
        guard let bestDistance = ranked.map(\.distance).min() else { return nil }
        let closest = ranked.filter { $0.distance == bestDistance }
        return closest.count == 1 ? closest[0].id : nil
    }

    private static func normalized(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    private static func levenshteinDistance(_ left: String, _ right: String) -> Int {
        let leftCharacters = Array(left)
        let rightCharacters = Array(right)
        if leftCharacters == rightCharacters { return 0 }
        if leftCharacters.isEmpty { return rightCharacters.count }
        if rightCharacters.isEmpty { return leftCharacters.count }

        var previous = Array(0...rightCharacters.count)
        var current = Array(repeating: 0, count: rightCharacters.count + 1)
        for (leftIndex, leftCharacter) in leftCharacters.enumerated() {
            current[0] = leftIndex + 1
            for (rightIndex, rightCharacter) in rightCharacters.enumerated() {
                let insertion = current[rightIndex] + 1
                let deletion = previous[rightIndex + 1] + 1
                let substitution = previous[rightIndex] + (leftCharacter == rightCharacter ? 0 : 1)
                current[rightIndex + 1] = min(insertion, deletion, substitution)
            }
            swap(&previous, &current)
        }
        return previous[rightCharacters.count]
    }
}
