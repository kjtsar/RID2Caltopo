import Foundation

public enum OperationalDiagnosticLogFormat {
    public static func localTimestamp(
        _ date: Date,
        timeZone: TimeZone = .current
    ) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSXXXXX"
        return formatter.string(from: date)
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
