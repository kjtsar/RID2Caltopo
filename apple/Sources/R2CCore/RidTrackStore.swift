import Foundation

public struct RidTrackPolicy: Sendable, Equatable {
    /// Matches Android's 293.3 ft/s waypoint-jump ceiling (about 200 mph).
    public var maximumSpeedMetersPerSecond: Double
    public var duplicateKeepaliveInterval: TimeInterval
    public var activeTimeout: TimeInterval
    public var maximumPointsPerTrack: Int

    public init(
        maximumSpeedMetersPerSecond: Double = 89.39784,
        duplicateKeepaliveInterval: TimeInterval = 3,
        activeTimeout: TimeInterval = 30,
        maximumPointsPerTrack: Int = 5_000
    ) {
        self.maximumSpeedMetersPerSecond = maximumSpeedMetersPerSecond
        self.duplicateKeepaliveInterval = duplicateKeepaliveInterval
        self.activeTimeout = activeTimeout
        self.maximumPointsPerTrack = maximumPointsPerTrack
    }
}

public struct RidTrackPoint: Sendable, Equatable, Identifiable {
    public let id: UUID
    public let receivedAt: Date
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    public let headingDegrees: Double?
    public let speedMetersPerSecond: Double?

    init(observation: RidObservation) {
        id = UUID()
        receivedAt = observation.receivedAt
        latitude = observation.latitude
        longitude = observation.longitude
        altitudeMeters = observation.altitudeMeters
        headingDegrees = observation.headingDegrees
        speedMetersPerSecond = observation.speedMetersPerSecond
    }
}

public struct RidAircraftTrack: Sendable, Equatable, Identifiable {
    public var id: String { aircraftID }
    public let aircraftID: String
    public fileprivate(set) var points: [RidTrackPoint]
    public fileprivate(set) var lastObservation: RidObservation
    public fileprivate(set) var lastSignalAt: Date
    public fileprivate(set) var lastSignalStrengthDbm: Int?
    public fileprivate(set) var distanceMeters: Double
    public fileprivate(set) var acceptedCountBySource: [RidObservation.Source: Int]

    public func isActive(at date: Date, timeout: TimeInterval = 30) -> Bool {
        date.timeIntervalSince(lastSignalAt) <= timeout
    }
}

public enum RidTrackSignalOnlyReason: Sendable, Equatable {
    case duplicatePosition
    case implausibleSpeed(metersPerSecond: Double)
}

public enum RidTrackIngestOutcome: Sendable, Equatable {
    case accepted(RidAircraftTrack)
    case signalOnly(RidAircraftTrack, reason: RidTrackSignalOnlyReason)
    case rejectedInvalidObservation
}

public actor RidTrackStore {
    public let policy: RidTrackPolicy
    private var tracksByAircraftID: [String: RidAircraftTrack] = [:]

    public init(policy: RidTrackPolicy = RidTrackPolicy()) {
        self.policy = policy
    }

    public func ingest(_ rawObservation: RidObservation) -> RidTrackIngestOutcome {
        let aircraftID = Self.canonicalAircraftID(rawObservation.aircraftId)
        guard !aircraftID.isEmpty,
              rawObservation.latitude.isFinite,
              rawObservation.longitude.isFinite,
              rawObservation.latitude != 0,
              rawObservation.longitude != 0,
              (-90 ... 90).contains(rawObservation.latitude),
              (-180 ... 180).contains(rawObservation.longitude)
        else {
            return .rejectedInvalidObservation
        }

        let observation = rawObservation.withAircraftID(aircraftID)
        guard var track = tracksByAircraftID[aircraftID] else {
            let point = RidTrackPoint(observation: observation)
            let track = RidAircraftTrack(
                aircraftID: aircraftID,
                points: [point],
                lastObservation: observation,
                lastSignalAt: observation.receivedAt,
                lastSignalStrengthDbm: observation.signalStrengthDbm,
                distanceMeters: 0,
                acceptedCountBySource: [observation.source: 1]
            )
            tracksByAircraftID[aircraftID] = track
            return .accepted(track)
        }

        track.lastSignalAt = max(track.lastSignalAt, observation.receivedAt)
        track.lastSignalStrengthDbm = observation.signalStrengthDbm
        guard let previous = track.points.last else {
            tracksByAircraftID.removeValue(forKey: aircraftID)
            return ingest(observation)
        }

        let distance = Self.distanceMeters(
            fromLatitude: previous.latitude,
            longitude: previous.longitude,
            toLatitude: observation.latitude,
            longitude: observation.longitude
        )
        let elapsed = observation.receivedAt.timeIntervalSince(previous.receivedAt)

        if distance == 0, elapsed < policy.duplicateKeepaliveInterval {
            tracksByAircraftID[aircraftID] = track
            return .signalOnly(track, reason: .duplicatePosition)
        }
        if distance > 0, elapsed > 0 {
            let impliedSpeed = distance / elapsed
            if impliedSpeed > policy.maximumSpeedMetersPerSecond {
                tracksByAircraftID[aircraftID] = track
                return .signalOnly(track, reason: .implausibleSpeed(metersPerSecond: impliedSpeed))
            }
        }

        track.points.append(RidTrackPoint(observation: observation))
        if track.points.count > policy.maximumPointsPerTrack {
            track.points.removeFirst(track.points.count - policy.maximumPointsPerTrack)
        }
        track.lastObservation = observation
        track.distanceMeters += distance
        track.acceptedCountBySource[observation.source, default: 0] += 1
        tracksByAircraftID[aircraftID] = track
        return .accepted(track)
    }

    public func snapshot() -> [RidAircraftTrack] {
        tracksByAircraftID.values.sorted {
            if $0.lastSignalAt != $1.lastSignalAt { return $0.lastSignalAt > $1.lastSignalAt }
            return $0.aircraftID < $1.aircraftID
        }
    }

    public func activeSnapshot(at date: Date = Date()) -> [RidAircraftTrack] {
        snapshot().filter { $0.isActive(at: date, timeout: policy.activeTimeout) }
    }

    public func removeInactive(at date: Date = Date()) -> [RidAircraftTrack] {
        let removed = tracksByAircraftID.values.filter {
            !$0.isActive(at: date, timeout: policy.activeTimeout)
        }
        for track in removed {
            tracksByAircraftID.removeValue(forKey: track.aircraftID)
        }
        return removed.sorted { $0.aircraftID < $1.aircraftID }
    }

    public func removeAll() {
        tracksByAircraftID.removeAll()
    }

    public static func canonicalAircraftID(_ value: String) -> String {
        value.uppercased().filter { $0.isASCII && ($0.isLetter || $0.isNumber) }
    }

    private static func distanceMeters(
        fromLatitude: Double,
        longitude fromLongitude: Double,
        toLatitude: Double,
        longitude toLongitude: Double
    ) -> Double {
        let radius = 6_371_008.8
        let lat1 = fromLatitude * .pi / 180
        let lat2 = toLatitude * .pi / 180
        let deltaLat = (toLatitude - fromLatitude) * .pi / 180
        let deltaLon = (toLongitude - fromLongitude) * .pi / 180
        let a = sin(deltaLat / 2) * sin(deltaLat / 2)
            + cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return radius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

private extension RidObservation {
    func withAircraftID(_ aircraftID: String) -> RidObservation {
        RidObservation(
            source: source,
            aircraftId: aircraftID,
            receivedAt: receivedAt,
            latitude: latitude,
            longitude: longitude,
            altitudeMeters: altitudeMeters,
            headingDegrees: headingDegrees,
            speedMetersPerSecond: speedMetersPerSecond,
            operatorLatitude: operatorLatitude,
            operatorLongitude: operatorLongitude,
            signalStrengthDbm: signalStrengthDbm
        )
    }
}
