import Foundation

public enum RidBuildMetadata {
    /// Matches Android's BuildConfig.BUILD_TIME archive format.
    public static func formattedBuildTime(
        _ date: Date,
        timeZone: TimeZone = .current
    ) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone
        formatter.dateFormat = "ddMMMyyyy:HHmmss"
        return formatter.string(from: date)
    }
}
