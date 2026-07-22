import Foundation

/// Keeps the UI attached to a real RTMP publisher instead of the empty
/// placeholder used before the first stream arrives.
public enum LiveStreamSelectionPolicy {
    public static let placeholderID = "demo"

    public static func focusAfterPublisherStarted(
        currentFocus: String,
        publisherPath: String,
        activePublisherPaths: Set<String>
    ) -> String {
        let normalized = normalize(publisherPath)
        guard !normalized.isEmpty else { return currentFocus }
        if currentFocus == placeholderID || !activePublisherPaths.contains(normalize(currentFocus)) {
            return normalized
        }
        return currentFocus
    }

    public static func shouldAcceptHLSMuxer(
        path: String,
        activePublisherPaths: Set<String>
    ) -> Bool {
        let normalized = normalize(path)
        return normalized != placeholderID && activePublisherPaths.contains(normalized)
    }

    public static func playbackPath(
        focusedID: String,
        activePublisherPaths: Set<String>
    ) -> String? {
        let normalized = normalize(focusedID)
        guard normalized != placeholderID, activePublisherPaths.contains(normalized) else { return nil }
        return normalized
    }

    private static func normalize(_ value: String) -> String {
        value.trimmingCharacters(in: CharacterSet(charactersIn: "/ "))
    }
}
