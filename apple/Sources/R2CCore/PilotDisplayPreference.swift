import Foundation

public let defaultActiveTrackColor = "#1E88E5"
public let defaultArchiveTrackColor = "#FF00FF"

public struct PilotDisplayPreference: Codable, Sendable, Equatable {
    public var activeTrackColor: String
    public var archiveTrackColor: String
    public var bearingEnabled: Bool

    public init(
        activeTrackColor: String = defaultActiveTrackColor,
        archiveTrackColor: String = defaultArchiveTrackColor,
        bearingEnabled: Bool = false
    ) {
        self.activeTrackColor = Self.sanitizeTrackColor(activeTrackColor, fallback: defaultActiveTrackColor)
        self.archiveTrackColor = Self.sanitizeTrackColor(archiveTrackColor, fallback: defaultArchiveTrackColor)
        self.bearingEnabled = bearingEnabled
    }

    public static func normalizePilotCallsign(_ value: String?) -> String? {
        let normalized = value?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
        return normalized.isEmpty ? nil : normalized
    }

    public static func sanitizeTrackColor(_ value: String?, fallback: String) -> String {
        var candidate = value?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased() ?? ""
        if candidate.count == 6 { candidate = "#" + candidate }
        guard candidate.count == 7, candidate.first == "#",
              candidate.dropFirst().allSatisfy({ $0.isHexDigit })
        else { return fallback }
        return candidate
    }
}

public struct MapScreenPoint: Sendable, Equatable {
    public let x: Double
    public let y: Double

    public init(x: Double, y: Double) {
        self.x = x
        self.y = y
    }
}

public enum OperationalMapGeometry {
    public static func bearingLineToViewportEdge(
        start: MapScreenPoint,
        headingDegrees: Double?,
        viewportWidth: Double,
        viewportHeight: Double
    ) -> MapScreenPoint? {
        guard let headingDegrees, headingDegrees.isFinite,
              viewportWidth > 0, viewportHeight > 0,
              start.x >= 0, start.x <= viewportWidth,
              start.y >= 0, start.y <= viewportHeight
        else { return nil }
        let radians = (headingDegrees - 90) * .pi / 180
        let dx = cos(radians)
        let dy = sin(radians)
        let epsilon = 1e-9
        var candidates: [Double] = []
        if dx > epsilon { candidates.append((viewportWidth - start.x) / dx) }
        else if dx < -epsilon { candidates.append((0 - start.x) / dx) }
        if dy > epsilon { candidates.append((viewportHeight - start.y) / dy) }
        else if dy < -epsilon { candidates.append((0 - start.y) / dy) }
        guard let distance = candidates.filter({ $0 >= 0 && $0.isFinite }).min() else { return nil }
        return MapScreenPoint(
            x: min(max(start.x + dx * distance, 0), viewportWidth),
            y: min(max(start.y + dy * distance, 0), viewportHeight)
        )
    }
}
