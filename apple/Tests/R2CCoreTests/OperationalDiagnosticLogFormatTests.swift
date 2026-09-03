import Foundation
import Testing
@testable import R2CCore

struct OperationalDiagnosticLogFormatTests {
    @Test
    func timestampUsesRequestedLocalTimeZoneDesignationAndNumericOffset() throws {
        let date = Date(timeIntervalSince1970: 0)
        let pacific = try #require(TimeZone(identifier: "America/Los_Angeles"))

        #expect(
            OperationalDiagnosticLogFormat.localTimestamp(date, timeZone: pacific)
                == "1969-12-31T16:00:00.000 PST -08:00"
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

        #expect(line == "1970-01-01T00:00:00.000 GMT Z [DEBUG][42-731:worker] [Tracker] first second\n")
    }

    @Test
    func lineRedactsCoordinateBearingMessagesAndRawTelemetryPayloads() {
        let messages = [
            "Published fresh fix latitude=39.153017 longitude=-121.133046 accuracyMeters=7",
            "request?latitude=39.153017&longitude=-121.133046&radius=2",
            #"geometry={"type":"Point","coordinates":[-121.72,38.21]}"#,
            "airport center=37.104722,-116.761111 radiusNm=103",
            "tile source=Imagery z=14 x=2689 y=6226",
            "tile url=https://example.test/tile/14/6226/2689.jpg",
            "DEM USGS_1_n40w122.tif tieXY=-122.001667,40.001667",
            "DJI_SEI_HEX len=32 payload=00112233",
        ]

        for message in messages {
            #expect(
                OperationalDiagnosticLogFormat.redactLocation(from: message)
                    == "[location details redacted]"
            )
        }
        #expect(
            OperationalDiagnosticLogFormat.redactLocation(
                from: "Location authorization changed; coordinates unavailable"
            ) == "Location authorization changed; coordinates unavailable"
        )
    }

    @Test
    func historicalLogTextIsRedactedLineByLine() {
        let input = """
        INFO Location Published lat=39.153017 lng=-121.133046 accuracy=7
        INFO Location authorization changed
        DEBUG NOTAM geometry={"coordinates":[-121.72,38.21]}

        """
        #expect(
            OperationalDiagnosticLogFormat.redactLocations(inLogText: input)
                == """
                [location details redacted]
                INFO Location authorization changed
                [location details redacted]

                """
        )
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
