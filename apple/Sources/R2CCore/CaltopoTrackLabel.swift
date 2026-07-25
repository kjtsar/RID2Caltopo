import Foundation

public enum CaltopoTrackLabel {
    /// Mirrors Android's `CtDroneSpec.updateTrackLabel()` and
    /// `CaltopoClient.TimeDatestampString()`.
    public static func androidCompatible(
        baseLabel: String,
        firstWaypointAt: Date,
        timeZone: TimeZone = .current
    ) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone
        formatter.dateFormat = "HHmmssMMMdd"
        return "\(baseLabel)_\(formatter.string(from: firstWaypointAt))"
    }
}
