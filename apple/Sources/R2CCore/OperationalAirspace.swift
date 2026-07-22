import Foundation

public enum OperationalAirspaceSeverity: String, Codable, Sendable, Equatable {
    case neutral, normal, caution
}

public struct OperationalFacilityMapRecord: Codable, Sendable, Equatable, Identifiable {
    public let objectID: Int64
    public let ceilingFeet: Int?
    public let unit: String
    public let primaryAirportFAAID: String
    public let primaryAirportICAO: String
    public let primaryAirportName: String
    public let laancAvailable: Bool
    public let airspaceClasses: [String]

    public var id: Int64 { objectID }

    public init(
        objectID: Int64, ceilingFeet: Int?, unit: String,
        primaryAirportFAAID: String, primaryAirportICAO: String,
        primaryAirportName: String, laancAvailable: Bool,
        airspaceClasses: [String]
    ) {
        self.objectID = objectID
        self.ceilingFeet = ceilingFeet
        self.unit = unit
        self.primaryAirportFAAID = primaryAirportFAAID
        self.primaryAirportICAO = primaryAirportICAO
        self.primaryAirportName = primaryAirportName
        self.laancAvailable = laancAvailable
        self.airspaceClasses = airspaceClasses
    }
}

public struct OperationalAirspaceState: Sendable, Equatable {
    public var loading: Bool
    public var severity: OperationalAirspaceSeverity
    public var chipLabel: String
    public var summary: String
    public var detail: String
    public var records: [OperationalFacilityMapRecord]
    public var errorMessage: String?

    public init(
        loading: Bool = false,
        severity: OperationalAirspaceSeverity = .neutral,
        chipLabel: String = "Airspace pending",
        summary: String = "",
        detail: String = "",
        records: [OperationalFacilityMapRecord] = [],
        errorMessage: String? = nil
    ) {
        self.loading = loading
        self.severity = severity
        self.chipLabel = chipLabel
        self.summary = summary
        self.detail = detail
        self.records = records
        self.errorMessage = errorMessage
    }
}

public enum OperationalFacilityMap {
    public static let operatingRadiusNM = 0.868976
    public static let operatingRadiusStatuteMiles = 1.0
    public static let operatingAreaLabel = "1 mi operating area"
    public static let endpoint = "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/FAA_UAS_FacilityMap_Data/FeatureServer/0/query"
    public static let outFields = "OBJECTID,CEILING,UNIT,APT1_FAAID,APT1_ICAO,APT1_NAME,APT1_LAANC,AIRSPACE_1,AIRSPACE_2,AIRSPACE_3,AIRSPACE_4,AIRSPACE_5"

    public static func queryURL(latitude: Double, longitude: Double) -> URL? {
        guard latitude.isFinite, longitude.isFinite,
              (-90 ... 90).contains(latitude), (-180 ... 180).contains(longitude),
              var components = URLComponents(string: endpoint)
        else { return nil }
        components.queryItems = [
            .init(name: "f", value: "json"),
            .init(name: "where", value: "1=1"),
            .init(name: "outFields", value: outFields),
            .init(name: "returnGeometry", value: "false"),
            .init(name: "geometry", value: String(format: "%.6f,%.6f", longitude, latitude)),
            .init(name: "geometryType", value: "esriGeometryPoint"),
            .init(name: "inSR", value: "4326"),
            .init(name: "distance", value: String(format: "%.6f", operatingRadiusStatuteMiles)),
            .init(name: "units", value: "esriSRUnit_StatuteMile"),
            .init(name: "spatialRel", value: "esriSpatialRelIntersects"),
        ]
        return components.url
    }

    public static func parse(_ data: Data) throws -> [OperationalFacilityMapRecord] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CocoaError(.fileReadCorruptFile)
        }
        if let error = root["error"] as? [String: Any] {
            let message = (error["message"] as? String) ?? "FAA Facility Map returned an error."
            throw OperationalFacilityMapError.service(message)
        }
        return (root["features"] as? [[String: Any]] ?? []).compactMap { feature in
            guard let attributes = feature["attributes"] as? [String: Any] else { return nil }
            let classes = (1 ... 5).compactMap { index -> String? in
                let value = string(attributes["AIRSPACE_\(index)"]).trimmingCharacters(in: .whitespacesAndNewlines)
                return value.isEmpty ? nil : value
            }
            return OperationalFacilityMapRecord(
                objectID: number(attributes["OBJECTID"])?.int64Value ?? -1,
                ceilingFeet: number(attributes["CEILING"])?.intValue,
                unit: string(attributes["UNIT"]),
                primaryAirportFAAID: string(attributes["APT1_FAAID"]),
                primaryAirportICAO: string(attributes["APT1_ICAO"]),
                primaryAirportName: string(attributes["APT1_NAME"]),
                laancAvailable: number(attributes["APT1_LAANC"])?.intValue == 1,
                airspaceClasses: classes
            )
        }
    }

    public static func state(
        records: [OperationalFacilityMapRecord],
        loading: Bool,
        errorMessage: String?
    ) -> OperationalAirspaceState {
        if loading {
            return .init(loading: true, chipLabel: "Airspace updating…", records: records, errorMessage: errorMessage)
        }
        if let errorMessage, records.isEmpty {
            return .init(chipLabel: "Airspace unavailable", detail: errorMessage, errorMessage: errorMessage)
        }
        if let controlled = records.first(where: { !$0.airspaceClasses.isEmpty }) {
            let airport = shortAirportName(controlled.primaryAirportName)
            let classes = controlled.airspaceClasses.map { "Class \($0)" }.joined(separator: "/")
            let ceiling = controlled.ceilingFeet.map { " up to \($0) ft" } ?? ""
            let prefix = controlled.laancAvailable ? "LAANC required" : "Authorization required"
            return .init(
                severity: .caution,
                chipLabel: "Airspace: \(prefix) - \(airport) \(classes)\(ceiling)",
                summary: "\(airport) \(classes)",
                detail: "Controlled airspace intersects the \(operatingAreaLabel). FAA authorization is required before flight.",
                records: records,
                errorMessage: errorMessage
            )
        }
        return .init(
            severity: .normal,
            chipLabel: "Airspace clear",
            summary: "No FAA UAS Facility Map grid at this location",
            records: records,
            errorMessage: errorMessage
        )
    }

    private static func shortAirportName(_ value: String) -> String {
        let shortened = value.components(separatedBy: " (").first?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return shortened.isEmpty ? "Controlled airspace" : shortened
    }

    private static func string(_ value: Any?) -> String {
        if value is NSNull || value == nil { return "" }
        return value as? String ?? String(describing: value!)
    }

    private static func number(_ value: Any?) -> NSNumber? {
        if let value = value as? NSNumber { return value }
        if let value = value as? String, let double = Double(value) { return NSNumber(value: double) }
        return nil
    }
}

public enum OperationalFacilityMapError: LocalizedError {
    case service(String)
    public var errorDescription: String? {
        switch self { case let .service(message): message }
    }
}
