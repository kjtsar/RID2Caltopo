import Foundation

public enum OperationalDiagnosticLogFormat {
    /// Files written for operator review use local wall time plus both the
    /// localized zone abbreviation and numeric UTC offset. Keeping this in one
    /// formatter prevents logs, track GeoJSON, and clue reports from drifting.
    public static func filenameTimestamp(
        _ date: Date,
        timeZone: TimeZone = .current
    ) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = "ddMMMyyyy-HHmmss-zZ"
        return formatter.string(from: date)
    }

    public static func localTimestamp(
        _ date: Date,
        timeZone: TimeZone = .current
    ) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS"
        let local = formatter.string(from: date)
        let abbreviation = timeZone.abbreviation(for: date) ?? timeZone.identifier
        formatter.dateFormat = "XXXXX"
        return "\(local) \(abbreviation) \(formatter.string(from: date))"
    }

    public static func line(
        level: String,
        processAndThread: String,
        category: String,
        message: String,
        at date: Date,
        timeZone: TimeZone = .current
    ) -> String {
        let cleanMessage = message.replacingOccurrences(of: "\n", with: " ")
        return "\(localTimestamp(date, timeZone: timeZone)) [\(level)][\(processAndThread)] [\(category)] \(cleanMessage)\n"
    }
}
