import Foundation

/// Platform-neutral Remote ID input consumed by the tracking and publishing layers.
///
/// Android scanners and Apple observation sources should normalize transport-specific
/// packets into this type before applying track or CalTopo publishing policy.
public struct RidObservation: Sendable, Equatable {
    public enum HeightReference: String, Sendable, Codable, CaseIterable {
        case takeoff
        case ground
    }

    public enum Source: String, Sendable, Codable, CaseIterable {
        case bluetoothLegacy
        case bluetoothExtended
        case wifiBeacon
        case wifiNan
        case trackerRelay
    }

    public let source: Source
    public let aircraftId: String
    public let receivedAt: Date
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    public let heightMeters: Double?
    public let heightReference: HeightReference?
    public let headingDegrees: Double?
    public let speedMetersPerSecond: Double?
    public let operatorLatitude: Double?
    public let operatorLongitude: Double?
    public let signalStrengthDbm: Int?

    public init(
        source: Source,
        aircraftId: String,
        receivedAt: Date,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double? = nil,
        heightMeters: Double? = nil,
        heightReference: HeightReference? = nil,
        headingDegrees: Double? = nil,
        speedMetersPerSecond: Double? = nil,
        operatorLatitude: Double? = nil,
        operatorLongitude: Double? = nil,
        signalStrengthDbm: Int? = nil
    ) {
        self.source = source
        self.aircraftId = aircraftId
        self.receivedAt = receivedAt
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
        self.heightMeters = heightMeters
        self.heightReference = heightReference
        self.headingDegrees = RidHeading.normalized(headingDegrees)
        self.speedMetersPerSecond = speedMetersPerSecond
        self.operatorLatitude = operatorLatitude
        self.operatorLongitude = operatorLongitude
        self.signalStrengthDbm = signalStrengthDbm
    }
}
