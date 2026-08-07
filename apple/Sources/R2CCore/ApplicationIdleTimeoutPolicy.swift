import Foundation

public enum ApplicationIdleTimeoutPolicy {
    public static func deadline(
        appStartedAt: Date,
        lastValidRIDUpdateAt: Date?,
        maximumIdleMinutes: Int
    ) -> Date? {
        guard maximumIdleMinutes > 0 else { return nil }
        let baseline = max(appStartedAt, lastValidRIDUpdateAt ?? appStartedAt)
        return baseline.addingTimeInterval(Double(maximumIdleMinutes) * 60)
    }

    public static func isExpired(
        appStartedAt: Date,
        lastValidRIDUpdateAt: Date?,
        maximumIdleMinutes: Int,
        now: Date
    ) -> Bool {
        guard let deadline = deadline(
            appStartedAt: appStartedAt,
            lastValidRIDUpdateAt: lastValidRIDUpdateAt,
            maximumIdleMinutes: maximumIdleMinutes
        ) else { return false }
        return now >= deadline
    }
}
