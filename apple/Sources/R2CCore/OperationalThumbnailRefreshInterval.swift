import Foundation

public enum OperationalThumbnailRefreshInterval {
    public static let storageKey = "video.thumbnailRefreshSeconds"
    public static let defaultSeconds = 5.0
    public static let minimumSeconds = 0.5
    public static let maximumSeconds = 60.0

    public static func normalized(_ seconds: Double?) -> Double {
        guard let seconds, seconds.isFinite else { return defaultSeconds }
        let clamped = min(max(seconds, minimumSeconds), maximumSeconds)
        return (clamped * 10).rounded() / 10
    }

    public static func formatted(_ seconds: Double?) -> String {
        String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), normalized(seconds))
    }

    public static func incremented(_ seconds: Double) -> Double {
        let current = normalized(seconds)
        let step = current < 1.0 ? 0.1 : 0.5
        return normalized(current + step)
    }

    public static func decremented(_ seconds: Double) -> Double {
        let current = normalized(seconds)
        let step = current <= 1.0 ? 0.1 : 0.5
        return normalized(current - step)
    }
}
