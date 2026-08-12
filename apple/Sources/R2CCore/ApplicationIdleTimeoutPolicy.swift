import Foundation

public enum ApplicationIdleTimeoutPolicy {
    public static func deadline(
        appStartedAt: Date,
        lastRIDMessageAt: Date?,
        maximumIdleMinutes: Int
    ) -> Date? {
        guard maximumIdleMinutes > 0 else { return nil }
        let baseline = max(appStartedAt, lastRIDMessageAt ?? appStartedAt)
        return baseline.addingTimeInterval(Double(maximumIdleMinutes) * 60)
    }

    public static func remainingDelay(
        appStartedAt: Date,
        lastRIDMessageAt: Date?,
        maximumIdleMinutes: Int,
        now: Date
    ) -> TimeInterval? {
        guard let deadline = deadline(
            appStartedAt: appStartedAt,
            lastRIDMessageAt: lastRIDMessageAt,
            maximumIdleMinutes: maximumIdleMinutes
        ) else { return nil }
        return max(0, deadline.timeIntervalSince(now))
    }

    public static func isExpired(
        appStartedAt: Date,
        lastRIDMessageAt: Date?,
        maximumIdleMinutes: Int,
        now: Date
    ) -> Bool {
        guard let deadline = deadline(
            appStartedAt: appStartedAt,
            lastRIDMessageAt: lastRIDMessageAt,
            maximumIdleMinutes: maximumIdleMinutes
        ) else { return false }
        return now >= deadline
    }
}
