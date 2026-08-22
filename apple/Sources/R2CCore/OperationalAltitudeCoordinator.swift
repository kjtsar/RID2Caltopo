import Foundation

public struct OperationalTerrainSample: Sendable, Equatable {
    public let elevationMeters: Double
    public let stale: Bool
    public let source: String?
    public let horizontalResolutionMeters: Double?

    public init(
        elevationMeters: Double,
        stale: Bool = false,
        source: String? = nil,
        horizontalResolutionMeters: Double? = nil
    ) {
        self.elevationMeters = elevationMeters
        self.stale = stale
        self.source = source
        self.horizontalResolutionMeters = horizontalResolutionMeters
    }
}

public struct OperationalAircraftAltitudeDisplay: Sendable, Equatable {
    public let atoFeet: Double?
    public let aglFeet: Double?
    public let aglStale: Bool
    public let aglUsesTerrain: Bool
    public let rangeFeet: Double?

    public init(
        atoFeet: Double?,
        aglFeet: Double?,
        aglStale: Bool,
        aglUsesTerrain: Bool,
        rangeFeet: Double?
    ) {
        self.atoFeet = atoFeet
        self.aglFeet = aglFeet
        self.aglStale = aglStale
        self.aglUsesTerrain = aglUsesTerrain
        self.rangeFeet = rangeFeet
    }
}

/// Per-aircraft altitude state matching Android's takeoff-reference and DEM correction rules.
public struct OperationalAltitudeCoordinator: Sendable {
    public enum SeedSource: Sendable, Equatable {
        case automatic
        case automaticSealed
        case manual
    }

    public struct Coordinate: Sendable, Equatable {
        public let latitude: Double
        public let longitude: Double

        public init(latitude: Double, longitude: Double) {
            self.latitude = latitude
            self.longitude = longitude
        }
    }

    private struct Calibration: Sendable, Equatable {
        var takeoffTrackAltitudeMeters: Double
        var seedSource: SeedSource
    }

    public private(set) var takeoffCoordinate: Coordinate?
    public private(set) var currentCoordinate: Coordinate?
    private var currentAltitudeMeters: Double?
    private var relativeHeightMeters: Double?
    private var relativeHeightReference: RidObservation.HeightReference?
    private var calibration: Calibration?
    private var automaticSampleCount = 0
    private var takeoffTerrain: OperationalTerrainSample?
    private var currentTerrain: OperationalTerrainSample?
    private var currentTerrainKey: String?
    private var correctionMeters: Double?

    public init() {}

    public mutating func ingest(_ observation: RidObservation) {
        let coordinate = Coordinate(latitude: observation.latitude, longitude: observation.longitude)
        if takeoffCoordinate == nil { takeoffCoordinate = coordinate }
        currentCoordinate = coordinate
        currentAltitudeMeters = observation.altitudeMeters.flatMap(Self.validAltitude)
        relativeHeightMeters = observation.heightMeters.flatMap(Self.validAltitude)
        relativeHeightReference = relativeHeightMeters == nil ? nil : observation.heightReference

        guard calibration?.seedSource != .automaticSealed,
              calibration?.seedSource != .manual,
              let altitude = currentAltitudeMeters
        else { return }

        if relativeHeightReference == .takeoff, let height = relativeHeightMeters {
            if calibration == nil || height >= 2 {
                let sample = altitude - height
                if var existing = calibration {
                    let updated = existing.takeoffTrackAltitudeMeters * 0.75 + sample * 0.25
                    let delta = abs(updated - existing.takeoffTrackAltitudeMeters)
                    automaticSampleCount += 1
                    existing.takeoffTrackAltitudeMeters = updated
                    if automaticSampleCount >= 6, delta < 0.4 {
                        existing.seedSource = .automaticSealed
                    }
                    calibration = existing
                } else {
                    calibration = Calibration(takeoffTrackAltitudeMeters: sample, seedSource: .automatic)
                    automaticSampleCount = 1
                }
                refreshCorrection()
            }
        } else if calibration == nil {
            calibration = Calibration(takeoffTrackAltitudeMeters: altitude, seedSource: .automatic)
            automaticSampleCount = 1
            refreshCorrection()
        }
    }

    public mutating func applyTakeoffTerrain(_ sample: OperationalTerrainSample?) {
        takeoffTerrain = sample
        refreshCorrection()
    }

    public mutating func applyCurrentTerrain(
        _ sample: OperationalTerrainSample?,
        coordinate: Coordinate
    ) {
        guard coordinate == currentCoordinate else { return }
        currentTerrain = sample
        currentTerrainKey = Self.terrainKey(coordinate)
    }

    public mutating func markCurrentTerrainPending() {
        currentTerrain = currentTerrain.map {
            OperationalTerrainSample(elevationMeters: $0.elevationMeters, stale: true)
        }
    }

    public mutating func manualCalibrateAtFiftyFeet() {
        guard let altitude = currentAltitudeMeters else { return }
        calibration = Calibration(
            takeoffTrackAltitudeMeters: altitude - 50 * Self.feetToMeters,
            seedSource: .manual
        )
        refreshCorrection()
    }

    public var canManualCalibrate: Bool { currentAltitudeMeters != nil }
    public var seedSource: SeedSource? { calibration?.seedSource }

    public var display: OperationalAircraftAltitudeDisplay {
        let atoMeters: Double? = {
            guard let altitude = currentAltitudeMeters else { return nil }
            if calibration?.seedSource == .manual {
                return calibration.map { altitude - $0.takeoffTrackAltitudeMeters }
            }
            if relativeHeightReference == .takeoff { return relativeHeightMeters }
            return calibration.map { altitude - $0.takeoffTrackAltitudeMeters }
        }()

        let terrainMatchesPosition = currentCoordinate.map(Self.terrainKey) == currentTerrainKey
        let aglMeters: Double?
        let usesTerrain = correctionMeters != nil
        if let correctionMeters, let terrain = currentTerrain {
            if relativeHeightReference == .takeoff,
               let height = relativeHeightMeters,
               let calibration {
                let takeoffGround = calibration.takeoffTrackAltitudeMeters - correctionMeters
                aglMeters = height + takeoffGround - terrain.elevationMeters
            } else if let altitude = currentAltitudeMeters {
                aglMeters = altitude - terrain.elevationMeters - correctionMeters
            } else {
                aglMeters = nil
            }
        } else if relativeHeightReference == .ground {
            aglMeters = relativeHeightMeters
        } else if !usesTerrain, relativeHeightReference == .takeoff {
            aglMeters = relativeHeightMeters
        } else {
            aglMeters = nil
        }

        let rangeMeters: Double? = {
            guard let takeoffCoordinate, let currentCoordinate else { return nil }
            return RidGeometry.relativePosition(
                fromLatitude: takeoffCoordinate.latitude,
                longitude: takeoffCoordinate.longitude,
                toLatitude: currentCoordinate.latitude,
                longitude: currentCoordinate.longitude
            )?.distanceMeters
        }()
        let nonNegativeAGLMeters = aglMeters.map { max(0, $0) }
        return OperationalAircraftAltitudeDisplay(
            atoFeet: atoMeters.map { $0 * Self.metersToFeet },
            aglFeet: nonNegativeAGLMeters.map { $0 * Self.metersToFeet },
            aglStale: usesTerrain && (currentTerrain?.stale == true || !terrainMatchesPosition),
            aglUsesTerrain: usesTerrain,
            rangeFeet: rangeMeters.map { $0 * Self.metersToFeet }
        )
    }

    public static func terrainKey(_ coordinate: Coordinate) -> String {
        // Schedule local DEM sampling at roughly one-metre position changes. The GeoTIFF
        // source bilinearly interpolates its surrounding pixels. Approximately one-metre
        // scheduling exposes 1 m local tiles and best-available EPQS results without visible steps.
        "\(Int((coordinate.latitude * 100_000).rounded()))|\(Int((coordinate.longitude * 100_000).rounded()))"
    }

    public static func terrainCacheKey(_ coordinate: Coordinate) -> String {
        // EPQS is backed by the best available 3DEP source, including 1 m lidar DEMs.
        // Retain approximately one-metre spacing so a fine source is not flattened to 30 m.
        "\(Int((coordinate.latitude * 100_000).rounded()))|\(Int((coordinate.longitude * 100_000).rounded()))"
    }

    private mutating func refreshCorrection() {
        guard let calibration, let takeoffTerrain else { return }
        correctionMeters = calibration.takeoffTrackAltitudeMeters - takeoffTerrain.elevationMeters
    }

    private static func validAltitude(_ value: Double) -> Double? {
        value.isFinite && value > -999 ? value : nil
    }

    private static let feetToMeters = 0.3048
    private static let metersToFeet = 3.28084
}
