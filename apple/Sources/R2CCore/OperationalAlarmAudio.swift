import Foundation

public enum OperationalSpokenWarningKind: String, CaseIterable, Sendable {
    case droneTelemetry = "Drone Telemetry"
    case altitude = "Altitude"
    case proximity = "Proximity"
    case controllerSignalStrength = "Controller Signal Strength"
    case bridgeNotDetected = "Bridge Not Detected"

    public var phrase: String { rawValue }
}

public enum OperationalAlarmAudioPolicy {
    public static let defaultVolumePercent = 100
    public static let testKinds: [OperationalSpokenWarningKind] = [
        .droneTelemetry,
        .altitude,
        .proximity,
        .controllerSignalStrength,
        .bridgeNotDetected,
    ]

    public static func normalizedVolumePercent(_ value: Int) -> Int {
        min(100, max(0, value))
    }

    public static func volumeMultiplier(forPercent value: Int) -> Float {
        Float(normalizedVolumePercent(value)) / 100
    }
}
