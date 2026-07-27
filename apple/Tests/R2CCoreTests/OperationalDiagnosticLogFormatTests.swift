import Foundation
import Testing
@testable import R2CCore

struct OperationalDiagnosticLogFormatTests {
    @Test
    func timestampUsesRequestedLocalTimeZoneAndNumericOffset() {
        let date = Date(timeIntervalSince1970: 0)
        let pacific = TimeZone(secondsFromGMT: -8 * 60 * 60)!

        #expect(
            OperationalDiagnosticLogFormat.localTimestamp(date, timeZone: pacific)
                == "1969-12-31T16:00:00.000-08:00"
        )
    }

    @Test
    func lineIncludesProcessThreadIdentityAndFlattensEmbeddedNewlines() {
        let line = OperationalDiagnosticLogFormat.line(
            level: "DEBUG",
            processAndThread: "42-731:worker",
            category: "Tracker",
            message: "first\nsecond",
            at: Date(timeIntervalSince1970: 0),
            timeZone: TimeZone(secondsFromGMT: 0)!
        )

        #expect(line == "1970-01-01T00:00:00.000Z [DEBUG][42-731:worker] [Tracker] first second\n")
    }

    @Test
    func compressedDiagnosticArchiveRoundTripsAndActuallyShrinksText() throws {
        let log = Data(String(repeating: "2026-07-26 log message from worker thread\n", count: 2_000).utf8)
        let archive = try OperationalZipArchive.encode(
            [.init(path: "2026-07-26/Log.txt", data: log)],
            compress: true
        )

        #expect(archive.count < log.count)
        #expect(try OperationalZipArchive.decode(archive) == [
            .init(path: "2026-07-26/Log.txt", data: log),
        ])
    }
}
