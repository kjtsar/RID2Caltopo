import Foundation

public struct RidTrafficPosition: Sendable, Equatable {
    public let aircraftID: String
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?

    public init(aircraftID: String, latitude: Double, longitude: Double, altitudeMeters: Double?) {
        self.aircraftID = aircraftID
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
    }
}

public struct RidPairSeparation: Sendable, Equatable {
    public let firstAircraftID: String
    public let secondAircraftID: String
    public let horizontalMeters: Double
    public let verticalMeters: Double?
    public let threeDimensionalMeters: Double?
}

public enum RidTrafficSeparation {
    public static func closestPair(in positions: [RidTrafficPosition]) -> RidPairSeparation? {
        var best: RidPairSeparation?
        for firstIndex in positions.indices {
            for secondIndex in positions.indices where secondIndex > firstIndex {
                let first = positions[firstIndex]
                let second = positions[secondIndex]
                guard first.aircraftID != second.aircraftID,
                      let relative = RidGeometry.relativePosition(
                          fromLatitude: first.latitude,
                          longitude: first.longitude,
                          toLatitude: second.latitude,
                          longitude: second.longitude
                      )
                else { continue }

                let vertical = verticalSeparation(first.altitudeMeters, second.altitudeMeters)
                let threeDimensional = vertical.map {
                    hypot(relative.distanceMeters, $0)
                }
                let orderedIDs = [first.aircraftID, second.aircraftID].sorted()
                let candidate = RidPairSeparation(
                    firstAircraftID: orderedIDs[0],
                    secondAircraftID: orderedIDs[1],
                    horizontalMeters: relative.distanceMeters,
                    verticalMeters: vertical,
                    threeDimensionalMeters: threeDimensional
                )

                if let currentBest = best {
                    if candidate.horizontalMeters < currentBest.horizontalMeters {
                        best = candidate
                    }
                } else {
                    best = candidate
                }
            }
        }
        return best
    }

    private static func verticalSeparation(_ first: Double?, _ second: Double?) -> Double? {
        guard let first, let second, first.isFinite, second.isFinite else { return nil }
        return abs(first - second)
    }
}
