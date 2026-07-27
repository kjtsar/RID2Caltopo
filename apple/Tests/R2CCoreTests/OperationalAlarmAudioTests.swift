import Testing
@testable import R2CCore

struct OperationalAlarmAudioTests {
    @Test
    func volumeMatchesAndroidRangeAndMultiplier() {
        #expect(OperationalAlarmAudioPolicy.normalizedVolumePercent(-1) == 0)
        #expect(OperationalAlarmAudioPolicy.normalizedVolumePercent(55) == 55)
        #expect(OperationalAlarmAudioPolicy.normalizedVolumePercent(101) == 100)
        #expect(OperationalAlarmAudioPolicy.volumeMultiplier(forPercent: 55) == 0.55)
    }

    @Test
    func alarmTestUsesAndroidWarningOrderAndPhrases() {
        #expect(OperationalAlarmAudioPolicy.testKinds.map(\.phrase) == [
            "Drone Telemetry",
            "Altitude",
            "Proximity",
            "Controller Signal Strength",
        ])
    }
}
