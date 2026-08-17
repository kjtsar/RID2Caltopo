import Foundation

public enum OperationalMainScreenPresentation {
    public static let incidentMapLabel = "Incident map"

    public static func incidentMapValue(mapID: String, mapTitle: String) -> String {
        let title = mapTitle.trimmingCharacters(in: .whitespacesAndNewlines)
        if !title.isEmpty { return title }
        let identifier = mapID.trimmingCharacters(in: .whitespacesAndNewlines)
        return identifier.isEmpty ? "Standalone" : identifier
    }

    public static func showsAircraftHeader(activeTrackCount: Int) -> Bool {
        activeTrackCount > 0
    }
}
