import Foundation

public struct RidTrackPolicy: Sendable, Equatable {
    /// Matches Android's 293.3 ft/s waypoint-jump ceiling (about 200 mph).
    public var maximumSpeedMetersPerSecond: Double
    /// Matches Android's minimum accepted waypoint spacing (2 ft by default).
    public var minimumDistanceMeters: Double
    public var duplicateKeepaliveInterval: TimeInterval
    public var activeTimeout: TimeInterval
    public var remoteLossGraceDistanceMeters: Double
    public var remoteLossGraceMultiplier: Double
    public var maximumPointsPerTrack: Int

    public init(
        maximumSpeedMetersPerSecond: Double = 89.39784,
        minimumDistanceMeters: Double = 0.6096,
        duplicateKeepaliveInterval: TimeInterval = 3,
        activeTimeout: TimeInterval = 30,
        remoteLossGraceDistanceMeters: Double = 15.24,
        remoteLossGraceMultiplier: Double = 5,
        maximumPointsPerTrack: Int = 5_000
    ) {
        self.maximumSpeedMetersPerSecond = maximumSpeedMetersPerSecond
        self.minimumDistanceMeters = minimumDistanceMeters
        self.duplicateKeepaliveInterval = duplicateKeepaliveInterval
        self.activeTimeout = activeTimeout
        self.remoteLossGraceDistanceMeters = remoteLossGraceDistanceMeters
        self.remoteLossGraceMultiplier = remoteLossGraceMultiplier
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
    case belowMinimumDistance(meters: Double)
    case implausibleSpeed(metersPerSecond: Double)
}

public enum RidTrackIngestOutcome: Sendable, Equatable {
    case accepted(RidAircraftTrack)
    case signalOnly(RidAircraftTrack, reason: RidTrackSignalOnlyReason)
    case rejectedHorizontalAccuracy(code: UInt8, track: RidAircraftTrack?)
    case rejectedInvalidObservation
}

public actor RidTrackStore {
    public private(set) var policy: RidTrackPolicy
    private var tracksByAircraftID: [String: RidAircraftTrack] = [:]

    public init(policy: RidTrackPolicy = RidTrackPolicy()) {
        self.policy = policy
    }

    public func updatePolicy(_ policy: RidTrackPolicy) {
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
        if let code = observation.horizontalAccuracyCode,
           !(10 ... 12).contains(code) {
            guard var track = tracksByAircraftID[aircraftID] else {
                return .rejectedHorizontalAccuracy(code: code, track: nil)
            }
            track.lastSignalAt = max(track.lastSignalAt, observation.receivedAt)
            track.lastSignalStrengthDbm = observation.signalStrengthDbm
            tracksByAircraftID[aircraftID] = track
            return .rejectedHorizontalAccuracy(code: code, track: track)
        }
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
        if distance > 0, distance < policy.minimumDistanceMeters {
            tracksByAircraftID[aircraftID] = track
            return .signalOnly(track, reason: .belowMinimumDistance(meters: distance))
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
        snapshot().filter { $0.isActive(at: date, timeout: effectiveTimeout(for: $0)) }
    }

    public func removeInactive(at date: Date = Date()) -> [RidAircraftTrack] {
        let removed = tracksByAircraftID.values.filter {
            !$0.isActive(at: date, timeout: effectiveTimeout(for: $0))
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

    private func effectiveTimeout(for track: RidAircraftTrack) -> TimeInterval {
        guard policy.remoteLossGraceMultiplier > 1,
              let first = track.points.first,
              let last = track.points.last
        else { return policy.activeTimeout }
        let displacement = Self.distanceMeters(
            fromLatitude: first.latitude,
            longitude: first.longitude,
            toLatitude: last.latitude,
            longitude: last.longitude
        )
        return displacement > policy.remoteLossGraceDistanceMeters
            ? policy.activeTimeout * policy.remoteLossGraceMultiplier
            : policy.activeTimeout
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
            heightMeters: heightMeters,
            heightReference: heightReference,
            horizontalAccuracyCode: horizontalAccuracyCode,
            headingDegrees: headingDegrees,
            speedMetersPerSecond: speedMetersPerSecond,
            operatorLatitude: operatorLatitude,
            operatorLongitude: operatorLongitude,
            signalStrengthDbm: signalStrengthDbm
        )
    }
}
