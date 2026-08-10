import Foundation

public struct OperationalMapBounds: Sendable, Equatable {
    public let north: Double
    public let south: Double
    public let west: Double
    public let east: Double

    public init(north: Double, south: Double, west: Double, east: Double) {
        self.north = min(85.05112878, max(-85.05112878, max(north, south)))
        self.south = min(85.05112878, max(-85.05112878, min(north, south)))
        self.west = min(180, max(-180, min(west, east)))
        self.east = min(180, max(-180, max(west, east)))
    }

    public init(coordinates: [MapCoordinate]) {
        self.init(
            north: coordinates.map(\.latitude).max() ?? 0,
            south: coordinates.map(\.latitude).min() ?? 0,
            west: coordinates.map(\.longitude).min() ?? 0,
            east: coordinates.map(\.longitude).max() ?? 0
        )
    }
}

public struct OperationalOfflineTile: Sendable, Hashable {
    public let zoom: Int
    public let x: Int
    public let y: Int

    public init(zoom: Int, x: Int, y: Int) {
        self.zoom = zoom
        self.x = x
        self.y = y
    }
}

public enum OperationalMapTileLayerState {
    public static func fingerprint(
        baseLayer: OperationalMapBaseLayer,
        contours: Bool,
        offlineOnly: Bool,
        revision: Int
    ) -> String {
        "\(baseLayer.rawValue)|\(contours)|\(offlineOnly)|\(revision)"
    }
}

public enum OperationalVisibleMapTile {
    public static func zoomLevel(
        worldMapWidth: Double,
        visibleMapWidth: Double,
        viewportWidth: Double,
        tileSize: Double = 256
    ) -> Int {
        guard worldMapWidth > 0, visibleMapWidth > 0, viewportWidth > 0, tileSize > 0 else { return 0 }
        let tilesAcross = worldMapWidth / visibleMapWidth * viewportWidth / tileSize
        return min(20, max(0, Int(floor(log2(tilesAcross)))))
    }

    public static func tile(latitude: Double, longitude: Double, zoom: Int) -> OperationalOfflineTile {
        let zoom = min(20, max(0, zoom))
        let count = pow(2, Double(zoom))
        let longitude = min(180, max(-180, longitude))
        let latitude = min(85.051_128_78, max(-85.051_128_78, latitude)) * .pi / 180
        let x = min(Int(count) - 1, max(0, Int(floor((longitude + 180) / 360 * count))))
        let yValue = (1 - log(tan(latitude) + 1 / cos(latitude)) / .pi) / 2 * count
        let y = min(Int(count) - 1, max(0, Int(floor(yValue))))
        return OperationalOfflineTile(zoom: zoom, x: x, y: y)
    }
}

public struct OperationalOfflinePreset: Sendable, Hashable, Identifiable {
    public let id: String
    public let label: String
    public let minimumZoom: Int
    public let maximumZoom: Int

    public init(id: String, label: String, minimumZoom: Int, maximumZoom: Int) {
        self.id = id
        self.label = label
        self.minimumZoom = minimumZoom
        self.maximumZoom = maximumZoom
    }

    public static let overview = Self(id: "overview", label: "Overview (z8-z12)", minimumZoom: 8, maximumZoom: 12)
    public static let operations = Self(id: "operations", label: "Ops (z12-z16)", minimumZoom: 12, maximumZoom: 16)
    public static let fullDetail = Self(id: "full", label: "Full detail (z8-z19)", minimumZoom: 8, maximumZoom: 19)
    public static let all = [overview, operations, fullDetail]
}

public enum OperationalDEMResolution: Int, Sendable, Hashable, Identifiable, CaseIterable {
    case standard30m = 30
    case enhanced10m = 10
    case maximum1m = 1

    public var id: Int { rawValue }
    public var label: String {
        switch self {
        case .standard30m: "Standard (30 m)"
        case .enhanced10m: "Enhanced (10 m)"
        case .maximum1m: "Maximum available (1 m)"
        }
    }

    public var explanation: String {
        switch self {
        case .standard30m: "Default; broad coverage and smallest download."
        case .enhanced10m: "About 9x as many terrain samples as 30 m."
        case .maximum1m: "Downloads available USGS lidar-derived project tiles; may be very large."
        }
    }
}

public enum OperationalOfflineMapPlanner {
    public static func tileCount(
        bounds: OperationalMapBounds,
        minimumZoom: Int,
        maximumZoom: Int
    ) -> Int {
        guard minimumZoom >= 0, maximumZoom >= minimumZoom, maximumZoom <= 22 else { return 0 }
        var count: Int64 = 0
        for zoom in minimumZoom ... maximumZoom {
            let range = tileRange(bounds: bounds, zoom: zoom)
            count += Int64(range.x.count) * Int64(range.y.count)
        }
        return Int(min(Int64(Int.max), count))
    }

    public static func tiles(
        bounds: OperationalMapBounds,
        minimumZoom: Int,
        maximumZoom: Int,
        maximumCount: Int = 250_000
    ) -> [OperationalOfflineTile]? {
        guard minimumZoom >= 0, maximumZoom >= minimumZoom, maximumZoom <= 22 else { return [] }
        let count = tileCount(bounds: bounds, minimumZoom: minimumZoom, maximumZoom: maximumZoom)
        guard count <= maximumCount else { return nil }
        var result: [OperationalOfflineTile] = []
        result.reserveCapacity(count)
        for zoom in minimumZoom ... maximumZoom {
            let range = tileRange(bounds: bounds, zoom: zoom)
            for x in range.x {
                for y in range.y {
                    result.append(.init(zoom: zoom, x: x, y: y))
                }
            }
        }
        return result
    }

    public static func demTileNames(bounds: OperationalMapBounds) -> [String] {
        let southBlock = Int(floor(bounds.south))
        let northBlock = Int(ceil(bounds.north)) - 1
        let westBlock = Int(floor(bounds.west))
        let eastBlock = Int(ceil(bounds.east)) - 1
        guard southBlock <= northBlock, westBlock <= eastBlock else { return [] }
        var names: [String] = []
        for latitudeBlock in southBlock ... northBlock {
            let northEdge = latitudeBlock + 1
            let latitude = northEdge >= 0
                ? String(format: "n%02d", northEdge)
                : String(format: "s%02d", -northEdge)
            for longitudeBlock in westBlock ... eastBlock {
                let longitude = longitudeBlock < 0
                    ? String(format: "w%03d", -longitudeBlock)
                    : String(format: "e%03d", longitudeBlock + 1)
                names.append(latitude + longitude)
            }
        }
        return names
    }

    public static func estimatedDEMTileCount(
        bounds: OperationalMapBounds,
        resolution: OperationalDEMResolution
    ) -> Int {
        guard resolution == .maximum1m else { return demTileNames(bounds: bounds).count }
        let centerLatitude = (bounds.north + bounds.south) / 2 * .pi / 180
        let widthMeters = abs(bounds.east - bounds.west) * 111_320 * max(0.1, cos(centerLatitude))
        let heightMeters = abs(bounds.north - bounds.south) * 111_320
        // Project-based USGS 1 m products are commonly distributed as 10 km square tiles.
        let oneMeterTiles = max(1, Int(ceil(widthMeters / 10_000)) * Int(ceil(heightMeters / 10_000)))
        return demTileNames(bounds: bounds).count + oneMeterTiles
    }

    public static func estimatedBytes(
        tileCount: Int,
        includeContours: Bool,
        demTileCount: Int,
        demResolution: OperationalDEMResolution = .standard30m
    ) -> Int64 {
        let mapOperations = Int64(tileCount) * Int64(includeContours ? 2 : 1)
        let demBytesPerPlanningTile: Int64 = switch demResolution {
        case .standard30m: 54_000_000
        case .enhanced10m: 486_000_000
        // Maximum detail includes 10 m fallback coverage under project-based 1 m tiles.
        case .maximum1m: 486_000_000
        }
        return mapOperations * 32_000 + Int64(demTileCount) * demBytesPerPlanningTile
    }

    public static func estimatedDEMBytes(
        bounds: OperationalMapBounds,
        resolution: OperationalDEMResolution
    ) -> Int64 {
        let geographicTiles = Int64(demTileNames(bounds: bounds).count)
        switch resolution {
        case .standard30m: return geographicTiles * 54_000_000
        case .enhanced10m: return geographicTiles * 486_000_000
        case .maximum1m:
            let total = Int64(estimatedDEMTileCount(bounds: bounds, resolution: resolution))
            let oneMeterTiles = max(1, total - geographicTiles)
            return geographicTiles * 486_000_000 + oneMeterTiles * 400_000_000
        }
    }

    private static func tileRange(bounds: OperationalMapBounds, zoom: Int) -> (x: ClosedRange<Int>, y: ClosedRange<Int>) {
        let maximum = (1 << zoom) - 1
        let minX = longitudeToTileX(bounds.west, zoom: zoom).clamped(to: 0 ... maximum)
        let maxX = longitudeToTileX(bounds.east, zoom: zoom).clamped(to: 0 ... maximum)
        let minY = latitudeToTileY(bounds.north, zoom: zoom).clamped(to: 0 ... maximum)
        let maxY = latitudeToTileY(bounds.south, zoom: zoom).clamped(to: 0 ... maximum)
        return (minX ... maxX, minY ... maxY)
    }

    private static func longitudeToTileX(_ longitude: Double, zoom: Int) -> Int {
        Int(floor((longitude + 180) / 360 * Double(1 << zoom)))
    }

    private static func latitudeToTileY(_ latitude: Double, zoom: Int) -> Int {
        let radians = latitude * .pi / 180
        return Int(floor((1 - asinh(tan(radians)) / .pi) / 2 * Double(1 << zoom)))
    }
}

public struct OperationalSignalLossInput: Sendable, Equatable {
    public let signalIdleSeconds: Double
    public let trackTelemetryIdleSeconds: Double
    public let learnedIntervalSeconds: Double?
    public let learnedSamples: Int
    public let distanceFromDeviceFeet: Double
    public let distanceFromTakeoffFeet: Double?
    public let bridgeCheckDistanceFeet: Double
    public let maximumTrackDelaySeconds: Double
    public let hasPreviouslyExceededBridgeDistance: Bool

    public init(
        signalIdleSeconds: Double,
        trackTelemetryIdleSeconds: Double? = nil,
        learnedIntervalSeconds: Double?,
        learnedSamples: Int,
        distanceFromDeviceFeet: Double,
        distanceFromTakeoffFeet: Double?,
        bridgeCheckDistanceFeet: Double,
        maximumTrackDelaySeconds: Double,
        hasPreviouslyExceededBridgeDistance: Bool
    ) {
        self.signalIdleSeconds = signalIdleSeconds
        self.trackTelemetryIdleSeconds = trackTelemetryIdleSeconds ?? signalIdleSeconds
        self.learnedIntervalSeconds = learnedIntervalSeconds
        self.learnedSamples = learnedSamples
        self.distanceFromDeviceFeet = distanceFromDeviceFeet
        self.distanceFromTakeoffFeet = distanceFromTakeoffFeet
        self.bridgeCheckDistanceFeet = bridgeCheckDistanceFeet
        self.maximumTrackDelaySeconds = maximumTrackDelaySeconds
        self.hasPreviouslyExceededBridgeDistance = hasPreviouslyExceededBridgeDistance
    }
}

public struct OperationalSignalLossDecision: Sendable, Equatable {
    public let alert: Bool
    public let hasExceededBridgeDistance: Bool
    public let idleThresholdSeconds: Double
}

public enum OperationalSignalLossPolicy {
    public static func evaluate(_ input: OperationalSignalLossInput) -> OperationalSignalLossDecision {
        let dynamic = input.learnedSamples >= 2 && (input.learnedIntervalSeconds ?? 0) > 0
            ? (input.learnedIntervalSeconds ?? 0) * 2.5
            : 10
        let maximum = max(2, input.maximumTrackDelaySeconds - 1)
        let threshold = min(maximum, max(10, dynamic))
        let exceeded = input.hasPreviouslyExceededBridgeDistance
            || input.distanceFromDeviceFeet > input.bridgeCheckDistanceFeet
            || (input.distanceFromTakeoffFeet ?? 0) > input.bridgeCheckDistanceFeet
        let returnedToDevice = input.distanceFromDeviceFeet <= input.bridgeCheckDistanceFeet
        let returnedToTakeoff = input.distanceFromTakeoffFeet.map { $0 <= 30 } ?? false
        return OperationalSignalLossDecision(
            alert: exceeded
                && !returnedToDevice
                && !returnedToTakeoff
                && input.signalIdleSeconds > threshold
                && input.trackTelemetryIdleSeconds > threshold,
            hasExceededBridgeDistance: exceeded,
            idleThresholdSeconds: threshold
        )
    }
}

public enum OperationalAltitudeSeverity: Int, Sendable, Equatable, Comparable {
    case normal
    case caution
    case overLimit

    public static func < (lhs: Self, rhs: Self) -> Bool { lhs.rawValue < rhs.rawValue }
}

public enum OperationalAltitudeAlertPolicy {
    public static func severity(aglFeet: Double?) -> OperationalAltitudeSeverity {
        guard let aglFeet, aglFeet.isFinite else { return .normal }
        if aglFeet >= 200 { return .overLimit }
        if aglFeet >= 180 { return .caution }
        return .normal
    }
}

private extension Int {
    func clamped(to range: ClosedRange<Int>) -> Int {
        Swift.min(range.upperBound, Swift.max(range.lowerBound, self))
    }
}
