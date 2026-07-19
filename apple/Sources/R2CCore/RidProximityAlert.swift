import Foundation

public struct RidProximityDrone: Sendable, Equatable {
    public let remoteID: String
    public let mappedID: String
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    public let sampleDate: Date
    public let distanceToOperatorMeters: Double?
    public let teamDrone: Bool
    public let localAlertEligible: Bool

    public init(
        remoteID: String,
        mappedID: String,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double?,
        sampleDate: Date = Date(),
        distanceToOperatorMeters: Double? = nil,
        teamDrone: Bool,
        localAlertEligible: Bool
    ) {
        self.remoteID = remoteID
        self.mappedID = mappedID
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
        self.sampleDate = sampleDate
        self.distanceToOperatorMeters = distanceToOperatorMeters
        self.teamDrone = teamDrone
        self.localAlertEligible = localAlertEligible
    }
}

public struct RidProximityAlertState: Sendable, Equatable, Identifiable {
    public var id: Int64 { alertInstanceID }
    public let alertInstanceID: Int64
    public let pairKey: String
    public let thresholdFeet: Double
    public let highSeverity: Bool
    public let nearestDroneMappedID: String
    public let farthestDroneMappedID: String
    public let highestDroneMappedID: String
    public let lowestDroneMappedID: String
    public let horizontalSeparationFeet: Double
    public let verticalSeparationFeet: Double
    public let currentHorizontalSeparationFeet: Double
    public let currentVerticalSeparationFeet: Double
    public let usesProjection: Bool
    public let firstLatitude: Double
    public let firstLongitude: Double
    public let secondLatitude: Double
    public let secondLongitude: Double
}

public struct RidProximityAlertOutput: Sendable, Equatable {
    public let activeAlert: RidProximityAlertState?
    public let suspendedAlert: RidProximityAlertState?
    public let canResume: Bool
}

/// Stateful Android-parity alert policy. UI presentation, speech, and haptics
/// remain platform responsibilities.
public struct RidProximityAlertEngine: Sendable {
    private struct DroneSample: Sendable {
        let latitude: Double
        let longitude: Double
        let altitudeFeet: Double
        let sampleDate: Date
    }

    private struct EvaluatedDrone: Sendable {
        let input: RidProximityDrone
        let effectiveLatitude: Double
        let effectiveLongitude: Double
        let effectiveAltitudeFeet: Double
    }

    private struct PairSnapshot: Sendable {
        let horizontalFeet: Double
        let verticalFeet: Double
        let threeDimensionalFeet: Double
    }

    private struct PairEvaluation: Sendable {
        let pairKey: String
        let first: EvaluatedDrone
        let second: EvaluatedDrone
        let horizontalFeet: Double
        let verticalFeet: Double
        let currentHorizontalFeet: Double
        let currentVerticalFeet: Double
        let usesProjection: Bool
        let altitudeSensitive: Bool
        let shouldAlert: Bool
        let highSeverity: Bool
        let severityScore: Double

        func isInside(thresholdFeet: Double) -> Bool {
            horizontalFeet <= thresholdFeet && (!altitudeSensitive || verticalFeet <= thresholdFeet)
        }
    }

    private var previousPairs: [String: PairSnapshot] = [:]
    private var sampleHistory: [String: [DroneSample]] = [:]
    private var latestPairs: [String: PairEvaluation] = [:]
    private var activeAlert: RidProximityAlertState?
    private var suspendedAlert: RidProximityAlertState?
    private var alertsSuspended = false
    private var clearEligibleSince: Date?
    private var nextAlertInstanceID: Int64 = 1

    public init() {}

    public mutating func update(
        drones: [RidProximityDrone],
        thresholdFeet: Double,
        predictiveEnabled: Bool = true,
        now: Date = Date()
    ) -> RidProximityAlertOutput {
        guard thresholdFeet.isFinite, thresholdFeet > 0 else {
            reset()
            return output
        }

        updateSampleHistory(drones: drones)
        let evaluated = drones.map { evaluateDrone($0, predictiveEnabled: predictiveEnabled) }
        let evaluations = evaluatePairs(drones: evaluated, thresholdFeet: thresholdFeet, predictiveEnabled: predictiveEnabled)
        latestPairs = Dictionary(uniqueKeysWithValues: evaluations.map { ($0.pairKey, $0) })
        let best = evaluations
            .filter(\.shouldAlert)
            .min {
                if $0.severityScore == $1.severityScore {
                    return hypot($0.horizontalFeet, $0.verticalFeet) < hypot($1.horizontalFeet, $1.verticalFeet)
                }
                return $0.severityScore < $1.severityScore
            }

        let current = activeAlert ?? suspendedAlert
        if let best {
            let instanceID: Int64
            if current?.pairKey == best.pairKey {
                instanceID = current?.alertInstanceID ?? nextAlertInstanceID
            } else {
                instanceID = nextAlertInstanceID
                nextAlertInstanceID += 1
            }
            let alert = makeAlert(best, thresholdFeet: thresholdFeet, instanceID: instanceID)
            if alertsSuspended {
                activeAlert = nil
                suspendedAlert = alert
            } else {
                activeAlert = alert
                suspendedAlert = nil
            }
            clearEligibleSince = nil
        } else if let current {
            let evaluation = latestPairs[current.pairKey]
            if evaluation?.isInside(thresholdFeet: thresholdFeet) != true {
                if clearEligibleSince == nil { clearEligibleSince = now }
                if now.timeIntervalSince(clearEligibleSince ?? now) >= 3 {
                    activeAlert = nil
                    suspendedAlert = nil
                    clearEligibleSince = nil
                }
            } else if let evaluation {
                let refreshed = makeAlert(
                    evaluation,
                    thresholdFeet: thresholdFeet,
                    instanceID: current.alertInstanceID
                )
                if alertsSuspended {
                    activeAlert = nil
                    suspendedAlert = refreshed
                } else {
                    activeAlert = refreshed
                    suspendedAlert = nil
                }
                clearEligibleSince = nil
            }
        } else {
            suspendedAlert = nil
        }

        previousPairs = Dictionary(uniqueKeysWithValues: evaluations.map { evaluation in
            let decisionVertical = evaluation.altitudeSensitive ? evaluation.verticalFeet : 0
            return (
                evaluation.pairKey,
                PairSnapshot(
                    horizontalFeet: evaluation.horizontalFeet,
                    verticalFeet: decisionVertical,
                    threeDimensionalFeet: hypot(evaluation.horizontalFeet, decisionVertical)
                )
            )
        })
        return output
    }

    public mutating func suspend() -> RidProximityAlertOutput {
        alertsSuspended = true
        clearEligibleSince = nil
        suspendedAlert = activeAlert ?? suspendedAlert
        activeAlert = nil
        return output
    }

    public mutating func resume() -> RidProximityAlertOutput {
        guard let suspendedAlert else { return output }
        let evaluation = latestPairs[suspendedAlert.pairKey]
        alertsSuspended = false
        if let evaluation, evaluation.isInside(thresholdFeet: suspendedAlert.thresholdFeet) {
            activeAlert = makeAlert(
                evaluation,
                thresholdFeet: suspendedAlert.thresholdFeet,
                instanceID: suspendedAlert.alertInstanceID
            )
            self.suspendedAlert = nil
        } else {
            activeAlert = nil
            self.suspendedAlert = nil
        }
        clearEligibleSince = nil
        return output
    }

    public mutating func reset() {
        previousPairs.removeAll()
        sampleHistory.removeAll()
        latestPairs.removeAll()
        activeAlert = nil
        suspendedAlert = nil
        alertsSuspended = false
        clearEligibleSince = nil
    }

    private var output: RidProximityAlertOutput {
        let canResume = suspendedAlert.flatMap { alert in
            latestPairs[alert.pairKey]?.isInside(thresholdFeet: alert.thresholdFeet)
        } == true
        return RidProximityAlertOutput(
            activeAlert: activeAlert,
            suspendedAlert: suspendedAlert,
            canResume: canResume
        )
    }

    private func evaluatePairs(
        drones: [EvaluatedDrone],
        thresholdFeet: Double,
        predictiveEnabled: Bool
    ) -> [PairEvaluation] {
        var result: [PairEvaluation] = []
        for firstIndex in drones.indices {
            for secondIndex in drones.indices where secondIndex > firstIndex {
                let first = drones[firstIndex]
                let second = drones[secondIndex]
                guard first.input.remoteID != second.input.remoteID,
                      let currentRelative = RidGeometry.relativePosition(
                          fromLatitude: first.input.latitude,
                          longitude: first.input.longitude,
                          toLatitude: second.input.latitude,
                          longitude: second.input.longitude
                      ),
                      let effectiveRelative = RidGeometry.relativePosition(
                          fromLatitude: first.effectiveLatitude,
                          longitude: first.effectiveLongitude,
                          toLatitude: second.effectiveLatitude,
                          longitude: second.effectiveLongitude
                      )
                else { continue }

                let currentHorizontalFeet = currentRelative.distanceMeters * 3.28084
                let currentVerticalFeet = verticalSeparationFeet(
                    first.input.altitudeMeters,
                    second.input.altitudeMeters
                )
                let horizontalFeet = effectiveRelative.distanceMeters * 3.28084
                let verticalFeet = abs(first.effectiveAltitudeFeet - second.effectiveAltitudeFeet)
                let altitudeSensitive = first.input.teamDrone && second.input.teamDrone
                let decisionCurrentVertical = altitudeSensitive ? currentVerticalFeet : 0
                let decisionVertical = altitudeSensitive ? verticalFeet : 0
                let currentThreeDimensional = hypot(currentHorizontalFeet, decisionCurrentVertical)
                let threeDimensional = hypot(horizontalFeet, decisionVertical)
                let pairKey = Self.pairKey(first.input.remoteID, second.input.remoteID)
                let previous = previousPairs[pairKey]
                let inside = horizontalFeet <= thresholdFeet && decisionVertical <= thresholdFeet
                let crossedInside = previous == nil
                    || (previous?.horizontalFeet ?? 0) > thresholdFeet
                    || (previous?.verticalFeet ?? 0) > thresholdFeet
                let predictedCloser = threeDimensional + 1 < currentThreeDimensional
                let actuallyApproaching = previous == nil
                    || threeDimensional + 1 < (previous?.threeDimensionalFeet ?? threeDimensional)
                let thresholdAllowsAlert = predictiveEnabled
                    ? inside && (predictedCloser || crossedInside)
                    : inside && (actuallyApproaching || crossedInside)
                let eligible = (first.input.teamDrone || second.input.teamDrone)
                    && (first.input.localAlertEligible || second.input.localAlertEligible)
                result.append(
                    PairEvaluation(
                        pairKey: pairKey,
                        first: first,
                        second: second,
                        horizontalFeet: horizontalFeet,
                        verticalFeet: verticalFeet,
                        currentHorizontalFeet: currentHorizontalFeet,
                        currentVerticalFeet: currentVerticalFeet,
                        usesProjection: predictiveEnabled && (
                            abs(horizontalFeet - currentHorizontalFeet) >= 0.1
                                || abs(verticalFeet - currentVerticalFeet) >= 0.1
                        ),
                        altitudeSensitive: altitudeSensitive,
                        shouldAlert: thresholdAllowsAlert && eligible,
                        highSeverity: horizontalFeet < thresholdFeet * 0.75
                            || decisionVertical < thresholdFeet * 0.75,
                        severityScore: max(horizontalFeet / thresholdFeet, decisionVertical / thresholdFeet)
                    )
                )
            }
        }
        return result
    }

    private func makeAlert(
        _ evaluation: PairEvaluation,
        thresholdFeet: Double,
        instanceID: Int64
    ) -> RidProximityAlertState {
        let orderedByDistance = [evaluation.first, evaluation.second].sorted {
            ($0.input.distanceToOperatorMeters ?? .greatestFiniteMagnitude, $0.input.mappedID)
                < ($1.input.distanceToOperatorMeters ?? .greatestFiniteMagnitude, $1.input.mappedID)
        }
        let orderedByAltitude = [evaluation.first, evaluation.second].sorted {
            $0.effectiveAltitudeFeet > $1.effectiveAltitudeFeet
        }
        return RidProximityAlertState(
            alertInstanceID: instanceID,
            pairKey: evaluation.pairKey,
            thresholdFeet: thresholdFeet,
            highSeverity: evaluation.highSeverity,
            nearestDroneMappedID: orderedByDistance[0].input.mappedID,
            farthestDroneMappedID: orderedByDistance[1].input.mappedID,
            highestDroneMappedID: orderedByAltitude[0].input.mappedID,
            lowestDroneMappedID: orderedByAltitude[1].input.mappedID,
            horizontalSeparationFeet: evaluation.horizontalFeet,
            verticalSeparationFeet: evaluation.verticalFeet,
            currentHorizontalSeparationFeet: evaluation.currentHorizontalFeet,
            currentVerticalSeparationFeet: evaluation.currentVerticalFeet,
            usesProjection: evaluation.usesProjection,
            firstLatitude: evaluation.first.input.latitude,
            firstLongitude: evaluation.first.input.longitude,
            secondLatitude: evaluation.second.input.latitude,
            secondLongitude: evaluation.second.input.longitude
        )
    }

    private mutating func updateSampleHistory(drones: [RidProximityDrone]) {
        let activeIDs = Set(drones.map(\.remoteID))
        sampleHistory = sampleHistory.filter { activeIDs.contains($0.key) }
        for drone in drones {
            var history = sampleHistory[drone.remoteID, default: []]
            if history.last?.sampleDate != drone.sampleDate {
                history.append(
                    DroneSample(
                        latitude: drone.latitude,
                        longitude: drone.longitude,
                        altitudeFeet: (drone.altitudeMeters ?? 0) * 3.28084,
                        sampleDate: drone.sampleDate
                    )
                )
                if history.count > 2 { history.removeFirst(history.count - 2) }
                sampleHistory[drone.remoteID] = history
            }
        }
    }

    private func evaluateDrone(
        _ drone: RidProximityDrone,
        predictiveEnabled: Bool
    ) -> EvaluatedDrone {
        guard predictiveEnabled,
              let history = sampleHistory[drone.remoteID],
              history.count == 2,
              let projected = projectedSample(history)
        else {
            return EvaluatedDrone(
                input: drone,
                effectiveLatitude: drone.latitude,
                effectiveLongitude: drone.longitude,
                effectiveAltitudeFeet: (drone.altitudeMeters ?? 0) * 3.28084
            )
        }
        return EvaluatedDrone(
            input: drone,
            effectiveLatitude: projected.latitude,
            effectiveLongitude: projected.longitude,
            effectiveAltitudeFeet: projected.altitudeFeet
        )
    }

    private func projectedSample(_ history: [DroneSample]) -> DroneSample? {
        let first = history[0]
        let second = history[1]
        let deltaSeconds = min(second.sampleDate.timeIntervalSince(first.sampleDate), 2)
        guard deltaSeconds > 0,
              let movement = RidGeometry.relativePosition(
                  fromLatitude: first.latitude,
                  longitude: first.longitude,
                  toLatitude: second.latitude,
                  longitude: second.longitude
              )
        else { return nil }

        let projectedCoordinate = movement.distanceMeters * 3.28084 >= 1
            ? destinationPoint(
                latitude: second.latitude,
                longitude: second.longitude,
                bearingDegrees: movement.bearingDegrees,
                distanceMeters: movement.distanceMeters
            )
            : (second.latitude, second.longitude)
        let verticalRate = (second.altitudeFeet - first.altitudeFeet) / deltaSeconds
        return DroneSample(
            latitude: projectedCoordinate.0,
            longitude: projectedCoordinate.1,
            altitudeFeet: second.altitudeFeet + verticalRate * deltaSeconds,
            sampleDate: second.sampleDate.addingTimeInterval(deltaSeconds)
        )
    }

    private func destinationPoint(
        latitude: Double,
        longitude: Double,
        bearingDegrees: Double,
        distanceMeters: Double
    ) -> (Double, Double) {
        let angularDistance = distanceMeters / 6_371_000
        let bearing = bearingDegrees * .pi / 180
        let latitude1 = latitude * .pi / 180
        let longitude1 = longitude * .pi / 180
        let latitude2 = asin(
            sin(latitude1) * cos(angularDistance)
                + cos(latitude1) * sin(angularDistance) * cos(bearing)
        )
        let longitude2 = longitude1 + atan2(
            sin(bearing) * sin(angularDistance) * cos(latitude1),
            cos(angularDistance) - sin(latitude1) * sin(latitude2)
        )
        return (latitude2 * 180 / .pi, longitude2 * 180 / .pi)
    }

    private func verticalSeparationFeet(_ first: Double?, _ second: Double?) -> Double {
        guard let first, let second, first.isFinite, second.isFinite else { return 0 }
        return abs(first - second) * 3.28084
    }

    private static func pairKey(_ first: String, _ second: String) -> String {
        first <= second ? "\(first)|\(second)" : "\(second)|\(first)"
    }
}
