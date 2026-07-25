import Foundation

public struct RidRelativePosition: Sendable, Equatable {
    public let distanceMeters: Double
    public let bearingDegrees: Double
    public let cardinalDirection: String

    public init(distanceMeters: Double, bearingDegrees: Double, cardinalDirection: String) {
        self.distanceMeters = distanceMeters
        self.bearingDegrees = bearingDegrees
        self.cardinalDirection = cardinalDirection
    }
}

public enum RidHeading {
    public static func normalized(_ value: Double?) -> Double? {
        guard let value, value.isFinite else { return nil }
        return (value.truncatingRemainder(dividingBy: 360) + 360)
            .truncatingRemainder(dividingBy: 360)
    }

    public static func roundedWholeDegrees(_ value: Double?) -> Int? {
        guard let normalized = normalized(value) else { return nil }
        return Int(normalized.rounded()).quotientAndRemainder(dividingBy: 360).remainder
    }
}

public enum RidGeometry {
    public static func relativePosition(
        fromLatitude: Double,
        longitude fromLongitude: Double,
        toLatitude: Double,
        longitude toLongitude: Double
    ) -> RidRelativePosition? {
        guard fromLatitude.isFinite, fromLongitude.isFinite,
              toLatitude.isFinite, toLongitude.isFinite,
              (-90 ... 90).contains(fromLatitude), (-180 ... 180).contains(fromLongitude),
              (-90 ... 90).contains(toLatitude), (-180 ... 180).contains(toLongitude)
        else { return nil }

        let originLatitude = fromLatitude * .pi / 180
        let destinationLatitude = toLatitude * .pi / 180
        let deltaLatitude = (toLatitude - fromLatitude) * .pi / 180
        let deltaLongitude = (toLongitude - fromLongitude) * .pi / 180

        let haversine = sin(deltaLatitude / 2) * sin(deltaLatitude / 2)
            + cos(originLatitude) * cos(destinationLatitude)
            * sin(deltaLongitude / 2) * sin(deltaLongitude / 2)
        let distance = 6_371_008.8 * 2 * atan2(sqrt(haversine), sqrt(1 - haversine))

        let y = sin(deltaLongitude) * cos(destinationLatitude)
        let x = cos(originLatitude) * sin(destinationLatitude)
            - sin(originLatitude) * cos(destinationLatitude) * cos(deltaLongitude)
        let bearing = (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
        return RidRelativePosition(
            distanceMeters: distance,
            bearingDegrees: bearing,
            cardinalDirection: cardinalDirection(for: bearing)
        )
    }

    private static func cardinalDirection(for bearing: Double) -> String {
        let directions = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"]
        let index = Int((bearing + 22.5) / 45) % directions.count
        return directions[index]
    }
}
