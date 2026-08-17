import Foundation

public enum ArchiveFolderDisplay {
    private static let minute: TimeInterval = 60
    private static let hour = 60 * minute
    private static let day = 24 * hour
    private static let month = 30 * day
    private static let year = 365 * day

    public static func age(_ ageSeconds: TimeInterval) -> String {
        let age = max(0, ageSeconds)
        if age < minute { return "<1 minute" }
        if age < hour { return unit(Int(age / minute), singular: "minute") }
        if age < day { return unit(Int(age / hour), singular: "hour") }
        if age < month { return unit(Int(age / day), singular: "day") }
        if age < year { return unit(Int(age / month), singular: "month") }
        return unit(Int(age / year), singular: "year")
    }

    public static func size(_ bytes: Int64) -> String {
        let safeBytes = max(0, bytes)
        if safeBytes < 1_024 { return "\(safeBytes) B" }
        let units = ["KB", "MB", "GB", "TB"]
        var value = Double(safeBytes) / 1_024
        var unitIndex = 0
        while value >= 1_024, unitIndex < units.count - 1 {
            value /= 1_024
            unitIndex += 1
        }
        if value >= 10 {
            return String(format: "%.0f %@", locale: Locale(identifier: "en_US_POSIX"), value, units[unitIndex])
        }
        return String(format: "%.1f %@", locale: Locale(identifier: "en_US_POSIX"), value, units[unitIndex])
    }

    private static func unit(_ count: Int, singular: String) -> String {
        "\(count) \(singular)\(count == 1 ? "" : "s")"
    }
}
