import CoreLocation
import Foundation
import R2CCore
import SwiftUI

@MainActor
final class AppleAirspaceCenter: ObservableObject {
    static let shared = AppleAirspaceCenter()

    @Published private(set) var state = OperationalAirspaceState()
    @Published var enabled: Bool { didSet { defaults.set(enabled, forKey: "airspace.enabled") } }
    @Published var autoRefresh: Bool { didSet { defaults.set(autoRefresh, forKey: "airspace.autoRefresh") } }

    private let defaults: UserDefaults
    private var records: [OperationalFacilityMapRecord] = []
    private var lastAttempt = Date.distantPast
    private var lastCoordinate: CLLocationCoordinate2D?
    private var refreshTask: Task<Void, Never>?

    private init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        enabled = defaults.object(forKey: "airspace.enabled") as? Bool ?? true
        autoRefresh = defaults.object(forKey: "airspace.autoRefresh") as? Bool ?? true
    }

    func update(location: CLLocation?, force: Bool = false) {
        guard enabled else {
            state = .init(chipLabel: "Airspace off")
            return
        }
        guard let location else {
            state = OperationalFacilityMap.state(
                records: records, loading: false,
                errorMessage: records.isEmpty ? "Waiting for GPS location" : state.errorMessage
            )
            return
        }
        guard force || shouldRefresh(location) else { return }
        guard refreshTask == nil else { return }
        lastAttempt = Date()
        state = OperationalFacilityMap.state(records: records, loading: true, errorMessage: state.errorMessage)
        refreshTask = Task { [weak self] in
            guard let self else { return }
            defer { refreshTask = nil }
            do {
                guard let url = OperationalFacilityMap.queryURL(
                    latitude: location.coordinate.latitude,
                    longitude: location.coordinate.longitude
                ) else { throw URLError(.badURL) }
                var request = URLRequest(url: url)
                request.timeoutInterval = 30
                request.setValue("RID2Caltopo/Apple (contact: kjtsar@kjt.us)", forHTTPHeaderField: "User-Agent")
                let (data, response) = try await URLSession.shared.data(for: request)
                guard let http = response as? HTTPURLResponse, (200 ..< 300).contains(http.statusCode) else {
                    throw AppleAirspaceError.service("Controlled-airspace lookup failed.")
                }
                records = try OperationalFacilityMap.parse(data)
                lastCoordinate = location.coordinate
                state = OperationalFacilityMap.state(records: records, loading: false, errorMessage: nil)
                AppleLog.info("Airspace", "FAA Facility Map returned \(records.count) record(s)")
            } catch {
                state = OperationalFacilityMap.state(records: records, loading: false, errorMessage: error.localizedDescription)
                AppleLog.error("Airspace", error.localizedDescription)
            }
        }
    }

    func refreshNow(location: CLLocation?) { update(location: location, force: true) }

    func installSimulatorDemo() {
        enabled = true
        records = [.init(
            objectID: 1, ceilingFeet: 200, unit: "FEET",
            primaryAirportFAAID: "DEN", primaryAirportICAO: "KDEN",
            primaryAirportName: "Denver International Airport (DEN)",
            laancAvailable: true, airspaceClasses: ["B"],
            rings: [[
                .init(latitude: 39.735, longitude: -105.000),
                .init(latitude: 39.745, longitude: -105.000),
                .init(latitude: 39.745, longitude: -104.986),
                .init(latitude: 39.735, longitude: -104.986),
                .init(latitude: 39.735, longitude: -105.000),
            ]]
        )]
        state = OperationalFacilityMap.state(records: records, loading: false, errorMessage: nil)
    }

    private func shouldRefresh(_ location: CLLocation) -> Bool {
        guard autoRefresh else { return records.isEmpty }
        if Date().timeIntervalSince(lastAttempt) >= 60 { return true }
        guard let lastCoordinate else { return records.isEmpty }
        return location.distance(from: CLLocation(latitude: lastCoordinate.latitude, longitude: lastCoordinate.longitude)) >= 402
    }
}

private enum AppleAirspaceError: LocalizedError {
    case service(String)
    var errorDescription: String? { switch self { case let .service(message): message } }
}

struct AppleAirspacePanel: View {
    @ObservedObject var center: AppleAirspaceCenter
    let location: CLLocation?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("One-mile operating area") {
                    Label(center.state.chipLabel, systemImage: "airplane.circle")
                        .foregroundStyle(severityColor)
                    if !center.state.summary.isEmpty { Text(center.state.summary) }
                    if !center.state.detail.isEmpty { Text(center.state.detail).font(.caption).foregroundStyle(.secondary) }
                    Button("Refresh Now") { center.refreshNow(location: location) }.disabled(center.state.loading)
                }
                Section("FAA UAS Facility Map") {
                    if center.state.records.isEmpty { Text("No facility-map grids returned.").foregroundStyle(.secondary) }
                    ForEach(center.state.records) { record in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(record.primaryAirportName.isEmpty ? "Controlled airspace" : record.primaryAirportName).fontWeight(.semibold)
                            Text(record.airspaceClasses.map { "Class \($0)" }.joined(separator: ", "))
                            LabeledContent("Ceiling", value: record.ceilingFeet.map { "\($0) \(record.unit.lowercased())" } ?? "Not published")
                            LabeledContent("LAANC", value: record.laancAvailable ? "Available—authorization required" : "Unavailable—authorization required")
                            if !record.primaryAirportFAAID.isEmpty { LabeledContent("Airport", value: record.primaryAirportFAAID) }
                        }
                    }
                }
            }
            .navigationTitle("Airspace")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }

    private var severityColor: Color {
        switch center.state.severity {
        case .caution: .orange
        case .normal: .green
        case .neutral: .secondary
        }
    }
}
