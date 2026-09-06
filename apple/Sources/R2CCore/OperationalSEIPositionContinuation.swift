import Foundation

public enum OperationalCluePositionSourcePolicy {
    public static func shouldBlockRIDFallback(
        seiPositionAuthorityEstablished: Bool,
        freshRawSEIPositionAvailable: Bool,
        validatedSEIPositionAvailable: Bool
    ) -> Bool {
        !validatedSEIPositionAvailable &&
            (seiPositionAuthorityEstablished || freshRawSEIPositionAvailable)
    }
}

public struct OperationalSEIPositionContinuation: Sendable, Equatable {
    public private(set) var positionValidated = false
    public private(set) var relativeUpValidated = false
    public private(set) var lastSourceTimestampMicroseconds: Int64?

    public init() {}

    public mutating func acceptHorizontalPosition(
        latitudeDegrees: Double?,
        longitudeDegrees: Double?,
        ridLatitudeDegrees: Double,
        ridLongitudeDegrees: Double,
        sourceTimestampMicroseconds: Int64?,
        maximumResidualMeters: Double = 30
    ) -> Bool {
        if let previous = lastSourceTimestampMicroseconds,
           let current = sourceTimestampMicroseconds,
           current + 1_000_000 < previous {
            positionValidated = false
            relativeUpValidated = false
        }
        if let sourceTimestampMicroseconds {
            lastSourceTimestampMicroseconds = sourceTimestampMicroseconds
        }

        guard let latitudeDegrees, latitudeDegrees.isFinite,
              let longitudeDegrees, longitudeDegrees.isFinite,
              (-90 ... 90).contains(latitudeDegrees),
              (-180 ... 180).contains(longitudeDegrees)
        else { return false }

        if !positionValidated {
            positionValidated = OperationalClueGeometry.djiValidatedHorizontalPosition(
                latitudeDegrees: latitudeDegrees,
                longitudeDegrees: longitudeDegrees,
                ridLatitudeDegrees: ridLatitudeDegrees,
                ridLongitudeDegrees: ridLongitudeDegrees,
                maximumResidualMeters: maximumResidualMeters
            ) != nil
        }
        return positionValidated
    }

    public mutating func acceptRelativeUp(
        observedRelativeUpMeters: Double?,
        ridAltitudeMeters: Double?,
        takeoffReportedAltitudeMeters: Double?,
        maximumResidualMeters: Double = 20
    ) -> Bool {
        guard let observedRelativeUpMeters, observedRelativeUpMeters.isFinite else { return false }
        if !relativeUpValidated {
            guard let ridAltitudeMeters, ridAltitudeMeters.isFinite,
                  let takeoffReportedAltitudeMeters, takeoffReportedAltitudeMeters.isFinite
            else { return false }
            relativeUpValidated = OperationalClueGeometry.djiValidatedRelativeUpMeters(
                observedRelativeUpMeters: observedRelativeUpMeters,
                ridAltitudeMeters: ridAltitudeMeters,
                takeoffMslMeters: takeoffReportedAltitudeMeters,
                maximumResidualMeters: maximumResidualMeters
            ) != nil
        }
        return relativeUpValidated
    }

    public mutating func reset() {
        positionValidated = false
        relativeUpValidated = false
        lastSourceTimestampMicroseconds = nil
    }
}
