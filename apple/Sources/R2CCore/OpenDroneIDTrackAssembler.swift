import Foundation

public actor OpenDroneIDTrackAssembler {
    private struct State: Sendable {
        var primaryID: OpenDroneIDBasicID?
        var serialID: OpenDroneIDBasicID?
        var location: OpenDroneIDLocation?
        var system: OpenDroneIDSystem?
    }

    private var transmitters: [UUID: State] = [:]

    public init() {}

    public func ingest(
        _ advertisement: OpenDroneIDAdvertisement,
        transmitterID: UUID,
        source: RidObservation.Source,
        receivedAt: Date,
        signalStrengthDbm: Int?
    ) -> RidObservation? {
        var state = transmitters[transmitterID, default: State()]

        for message in advertisement.messages {
            switch message.payload {
            case let .basicID(basicID):
                if state.primaryID == nil {
                    state.primaryID = basicID
                }
                if basicID.isSerialNumber {
                    state.serialID = basicID
                }
            case let .location(location):
                state.location = location
            case let .system(system):
                state.system = system
            case .messagePack, .opaque:
                break
            }
        }

        transmitters[transmitterID] = state
        guard let identity = state.serialID ?? state.primaryID,
              !identity.uasID.isEmpty,
              let location = state.location,
              location.latitude != 0,
              location.longitude != 0
        else {
            return nil
        }

        return RidObservation(
            source: source,
            aircraftId: RidTrackStore.canonicalAircraftID(identity.uasID),
            receivedAt: receivedAt,
            latitude: location.latitude,
            longitude: location.longitude,
            altitudeMeters: location.preferredAltitudeMeters,
            headingDegrees: location.directionDegrees,
            speedMetersPerSecond: location.horizontalSpeedMetersPerSecond,
            operatorLatitude: state.system?.operatorLatitude,
            operatorLongitude: state.system?.operatorLongitude,
            signalStrengthDbm: signalStrengthDbm
        )
    }

    public func removeState(for transmitterID: UUID) {
        transmitters.removeValue(forKey: transmitterID)
    }

    public func removeAllState() {
        transmitters.removeAll()
    }
}
