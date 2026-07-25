import Foundation

public enum OperationalCoordinateDisplayFormat: String, CaseIterable, Codable, Sendable, Identifiable {
    case decimal
    case utm
    case usng

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .decimal: "Decimal"
        case .utm: "UTM"
        case .usng: "USNG"
        }
    }
}

public enum OperationalCoordinateFormatter {
    public static func format(
        latitude: Double,
        longitude: Double,
        as format: OperationalCoordinateDisplayFormat
    ) -> String {
        guard latitude.isFinite, longitude.isFinite else { return "loc:unknown" }
        switch format {
        case .decimal:
            return String(format: "loc:%.5f,%.5f", latitude, longitude)
        case .utm:
            let coordinate = toUTM(latitude: latitude, longitude: longitude)
            return String(
                format: "loc:%d%@ %06d %07d",
                coordinate.zoneNumber,
                String(coordinate.zoneLetter),
                Int(coordinate.easting.rounded()),
                Int(coordinate.northing.rounded())
            )
        case .usng:
            let coordinate = toUTM(latitude: latitude, longitude: longitude)
            let grid = usng100KID(
                zoneNumber: coordinate.zoneNumber,
                easting: coordinate.easting,
                northing: coordinate.northing
            )
            return String(
                format: "loc:%d%@ %@ %05d %05d",
                coordinate.zoneNumber,
                String(coordinate.zoneLetter),
                grid,
                positiveModulo(Int(coordinate.easting.rounded()), 100_000),
                positiveModulo(Int(coordinate.northing.rounded()), 100_000)
            )
        }
    }

    private static func toUTM(latitude: Double, longitude: Double) -> UTMCoordinate {
        let equatorialRadius = 6_378_137.0
        let eccentricitySquared = 0.00669438
        let scale = 0.9996
        let latitude = min(84, max(-80, latitude))
        let longitude = ((longitude + 180) - floor((longitude + 180) / 360) * 360) - 180
        let latitudeRadians = latitude * .pi / 180
        let longitudeRadians = longitude * .pi / 180

        var zoneNumber = Int((longitude + 180) / 6) + 1
        if latitude >= 56, latitude < 64, longitude >= 3, longitude < 12 {
            zoneNumber = 32
        }
        if latitude >= 72, latitude < 84 {
            switch longitude {
            case 0 ..< 9: zoneNumber = 31
            case 9 ..< 21: zoneNumber = 33
            case 21 ..< 33: zoneNumber = 35
            case 33 ..< 42: zoneNumber = 37
            default: break
            }
        }

        let longitudeOrigin = Double((zoneNumber - 1) * 6 - 180 + 3)
        let longitudeOriginRadians = longitudeOrigin * .pi / 180
        let eccentricityPrimeSquared = eccentricitySquared / (1 - eccentricitySquared)
        let sinLatitude = sin(latitudeRadians)
        let cosLatitude = cos(latitudeRadians)
        let tangent = tan(latitudeRadians)
        let n = equatorialRadius / sqrt(1 - eccentricitySquared * pow(sinLatitude, 2))
        let t = pow(tangent, 2)
        let c = eccentricityPrimeSquared * pow(cosLatitude, 2)
        let a = cosLatitude * (longitudeRadians - longitudeOriginRadians)

        let m = equatorialRadius * (
            (1
                - eccentricitySquared / 4
                - 3 * pow(eccentricitySquared, 2) / 64
                - 5 * pow(eccentricitySquared, 3) / 256) * latitudeRadians
                - (3 * eccentricitySquared / 8
                    + 3 * pow(eccentricitySquared, 2) / 32
                    + 45 * pow(eccentricitySquared, 3) / 1024) * sin(2 * latitudeRadians)
                + (15 * pow(eccentricitySquared, 2) / 256
                    + 45 * pow(eccentricitySquared, 3) / 1024) * sin(4 * latitudeRadians)
                - (35 * pow(eccentricitySquared, 3) / 3072) * sin(6 * latitudeRadians)
        )

        let easting = max(0, scale * n * (
            a
                + (1 - t + c) * pow(a, 3) / 6
                + (5 - 18 * t + t * t + 72 * c - 58 * eccentricityPrimeSquared) * pow(a, 5) / 120
        ) + 500_000)

        var northing = scale * (
            m + n * tangent * (
                pow(a, 2) / 2
                    + (5 - t + 9 * c + 4 * c * c) * pow(a, 4) / 24
                    + (61 - 58 * t + t * t + 600 * c - 330 * eccentricityPrimeSquared) * pow(a, 6) / 720
            )
        )
        if latitude < 0 { northing += 10_000_000 }

        return UTMCoordinate(
            zoneNumber: zoneNumber,
            zoneLetter: latitudeBand(latitude),
            easting: easting,
            northing: max(0, northing)
        )
    }

    private static func latitudeBand(_ latitude: Double) -> Character {
        switch latitude {
        case ..<(-72): "C"
        case ..<(-64): "D"
        case ..<(-56): "E"
        case ..<(-48): "F"
        case ..<(-40): "G"
        case ..<(-32): "H"
        case ..<(-24): "J"
        case ..<(-16): "K"
        case ..<(-8): "L"
        case ..<0: "M"
        case ..<8: "N"
        case ..<16: "P"
        case ..<24: "Q"
        case ..<32: "R"
        case ..<40: "S"
        case ..<48: "T"
        case ..<56: "U"
        case ..<64: "V"
        case ..<72: "W"
        default: "X"
        }
    }

    private static func usng100KID(zoneNumber: Int, easting: Double, northing: Double) -> String {
        let columnSets = [Array("ABCDEFGH"), Array("JKLMNPQR"), Array("STUVWXYZ")]
        let rowSets = [Array("ABCDEFGHJKLMNPQRSTUV"), Array("FGHJKLMNPQRSTUVABCDE")]
        let columns = columnSets[(zoneNumber - 1) % columnSets.count]
        let rows = rowSets[(zoneNumber - 1) % rowSets.count]
        let columnIndex = max(0, Int(floor(easting / 100_000)) - 1) % columns.count
        let rowIndex = Int(floor(northing / 100_000)) % rows.count
        return "\(columns[columnIndex])\(rows[rowIndex])"
    }

    private static func positiveModulo(_ value: Int, _ modulus: Int) -> Int {
        let remainder = value % modulus
        return remainder < 0 ? remainder + modulus : remainder
    }

    private struct UTMCoordinate {
        let zoneNumber: Int
        let zoneLetter: Character
        let easting: Double
        let northing: Double
    }
}
