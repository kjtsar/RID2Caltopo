import CoreLocation
import Foundation
import R2CCore
import SwiftUI

struct AppleNotamState: Equatable {
    var visible = false
    var enabled = false
    var configured = false
    var loading = false
    var stale = false
    var chipSeverity: OperationalNotamSeverity = .neutral
    var chipLabel = "NOTAMs unavailable"
    var statusLine = ""
    var lastUpdated: Date?
    var queryCoordinate: OperationalNotamCoordinate?
    var radiusNM = 2
    var notices: [OperationalNotam] = []
    var suppressedCount = 0
    var errorMessage: String?
}

@MainActor
final class AppleNotamCenter: ObservableObject {
    static let shared = AppleNotamCenter()

    @Published private(set) var state = AppleNotamState()
    @Published var enabled: Bool { didSet { defaults.set(enabled, forKey: "notam.enabled") } }
    @Published var showOnMap: Bool { didSet { defaults.set(showOnMap, forKey: "notam.showOnMap") } }
    @Published var autoRefresh: Bool { didSet { defaults.set(autoRefresh, forKey: "notam.autoRefresh") } }
    @Published var radiusNM: Int { didSet { defaults.set(radiusNM, forKey: "notam.radiusNM") } }
    @Published var refreshIntervalSeconds: Int { didSet { defaults.set(refreshIntervalSeconds, forKey: "notam.refreshSeconds") } }

    private let defaults: UserDefaults
    private var credentials: FaaSharedConfig?
    private var token: String?
    private var tokenExpiry = Date.distantPast
    private var lastFetchCoordinate: OperationalNotamCoordinate?
    private var lastAttempt = Date.distantPast
    private var lastSuccessfulNotices: [OperationalNotam] = []
    private var refreshTask: Task<Void, Never>?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        enabled = defaults.object(forKey: "notam.enabled") as? Bool ?? false
        showOnMap = defaults.object(forKey: "notam.showOnMap") as? Bool ?? true
        autoRefresh = defaults.object(forKey: "notam.autoRefresh") as? Bool ?? true
        radiusNM = max(1, min(100, defaults.object(forKey: "notam.radiusNM") as? Int ?? 2))
        refreshIntervalSeconds = max(30, defaults.object(forKey: "notam.refreshSeconds") as? Int ?? 300)
        rebuildState(loading: false, coordinate: nil, error: nil)
    }

    func configure(_ credentials: FaaSharedConfig?) {
        let old = self.credentials
        self.credentials = credentials
        if old != credentials {
            token = nil
            tokenExpiry = .distantPast
            rebuildState(loading: false, coordinate: state.queryCoordinate, error: nil)
        }
    }

    func update(location: CLLocation?, force: Bool = false) {
        let coordinate = location.map { OperationalNotamCoordinate(latitude: $0.coordinate.latitude, longitude: $0.coordinate.longitude) }
        guard enabled else {
            state = AppleNotamState(visible: false, enabled: false, configured: credentials != nil, radiusNM: radiusNM)
            return
        }
        guard force || shouldRefresh(coordinate) else {
            rebuildState(loading: false, coordinate: coordinate, error: state.errorMessage)
            return
        }
        guard refreshTask == nil else { return }
        lastAttempt = Date()
        rebuildState(loading: true, coordinate: coordinate, error: state.errorMessage)
        refreshTask = Task { [weak self] in
            guard let self else { return }
            defer { refreshTask = nil }
            do {
                guard let coordinate else { throw AppleNotamError.locationUnavailable }
                guard let credentials else { throw AppleNotamError.credentialsUnavailable }
                let bearer = try await bearerToken(credentials)
                let notices = try await fetchNotams(coordinate: coordinate, credentials: credentials, bearer: bearer)
                lastFetchCoordinate = coordinate
                lastSuccessfulNotices = notices
                rebuildState(loading: false, coordinate: coordinate, error: nil, successfulAt: Date())
                AppleLog.info("NOTAM", "Fetched \(notices.count) notices radiusNM=\(radiusNM)")
            } catch {
                let message = error.localizedDescription
                rebuildState(loading: false, coordinate: coordinate, error: message)
                AppleLog.error("NOTAM", message)
            }
        }
    }

    func refreshNow(location: CLLocation?) { update(location: location, force: true) }

    func installSimulatorDemo() {
        enabled = true
        showOnMap = true
        let ring = [
            OperationalNotamCoordinate(latitude: 39.72, longitude: -105.01),
            OperationalNotamCoordinate(latitude: 39.72, longitude: -104.97),
            OperationalNotamCoordinate(latitude: 39.76, longitude: -104.97),
            OperationalNotamCoordinate(latitude: 39.76, longitude: -105.01),
            OperationalNotamCoordinate(latitude: 39.72, longitude: -105.01),
        ]
        let notice = OperationalNotam(
            id: "demo-tfr", title: "Temporary flight restriction",
            summary: "Training TFR intersects the pilot operating area.", distanceNM: 0,
            intersectsPilotArea: true, effectiveText: "Simulator qualification only",
            details: "Deterministic NOTAM/TFR overlay used for Apple UI qualification.",
            rawText: "TFR SFC-400 FT AGL", reference: "FDC DEMO/26", severity: .danger,
            altitudeBand: .init(floorFeetMSL: nil, ceilingFeetMSL: nil, floorLabel: "SFC", ceilingLabel: "400 FT", reference: "AGL"),
            geometries: [.polygon([ring])]
        )
        lastSuccessfulNotices = [notice]
        state = AppleNotamState(
            visible: true, enabled: true, configured: true, loading: false, stale: false,
            chipSeverity: .danger, chipLabel: "NOTAMs: RESTRICTED 0.0 NM",
            statusLine: "1 nearby NOTAM (simulator).", lastUpdated: Date(),
            queryCoordinate: .init(latitude: 39.7392, longitude: -104.9903), radiusNM: radiusNM,
            notices: [notice]
        )
    }

    private func shouldRefresh(_ coordinate: OperationalNotamCoordinate?) -> Bool {
        if state.lastUpdated == nil { return Date().timeIntervalSince(lastAttempt) >= 15 }
        guard autoRefresh else { return false }
        if Date().timeIntervalSince(lastAttempt) >= Double(refreshIntervalSeconds) { return true }
        guard let coordinate, let previous = lastFetchCoordinate else { return false }
        return distanceNM(coordinate, previous) >= 0.25
    }

    private func rebuildState(
        loading: Bool,
        coordinate: OperationalNotamCoordinate?,
        error: String?,
        successfulAt: Date? = nil
    ) {
        let configured = credentials != nil
        let filtered = OperationalNotamPolicy.filtered(lastSuccessfulNotices, radiusNM: radiusNM)
        let updated = successfulAt ?? state.lastUpdated
        let stale = updated.map { Date().timeIntervalSince($0) > Double(max(refreshIntervalSeconds * 2, 600)) } ?? false
        state = AppleNotamState(
            visible: enabled,
            enabled: enabled,
            configured: configured,
            loading: loading,
            stale: stale,
            chipSeverity: OperationalNotamPolicy.chipSeverity(notices: filtered.visible, configured: configured, hasError: error != nil),
            chipLabel: OperationalNotamPolicy.chipLabel(notices: filtered.visible, configured: configured, loading: loading, hasError: error != nil),
            statusLine: statusLine(configured: configured, loading: loading, count: filtered.visible.count, error: error),
            lastUpdated: updated,
            queryCoordinate: coordinate,
            radiusNM: radiusNM,
            notices: filtered.visible,
            suppressedCount: filtered.suppressed,
            errorMessage: error
        )
    }

    private func bearerToken(_ credentials: FaaSharedConfig) async throws -> String {
        if let token, Date().addingTimeInterval(60) < tokenExpiry { return token }
        let url = URL(string: credentials.tokenURL.isEmpty ? "https://api-nms.aim.faa.gov/v1/auth/token" : credentials.tokenURL)
        guard let url else { throw AppleNotamError.invalidConfiguration }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("Basic \(Data("\(credentials.clientID):\(credentials.clientSecret)".utf8).base64EncodedString())", forHTTPHeaderField: "Authorization")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data("grant_type=client_credentials".utf8)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw AppleNotamError.service("No HTTP token response") }
        guard (200 ..< 300).contains(http.statusCode) else { throw AppleNotamError.service("NOTAM authentication failed (HTTP \(http.statusCode)).") }
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let value = object["access_token"] as? String, !value.isEmpty
        else { throw AppleNotamError.service("NOTAM token response did not include an access token.") }
        token = value
        tokenExpiry = Date().addingTimeInterval(max(60, (object["expires_in"] as? NSNumber)?.doubleValue ?? 1_800))
        return value
    }

    private func fetchNotams(
        coordinate: OperationalNotamCoordinate,
        credentials: FaaSharedConfig,
        bearer: String
    ) async throws -> [OperationalNotam] {
        let base = credentials.apiBaseURL.isEmpty ? "https://api-nms.aim.faa.gov/nmsapi" : credentials.apiBaseURL
        guard var components = URLComponents(string: base + (base.hasSuffix("/") ? "v1/notams" : "/v1/notams")) else {
            throw AppleNotamError.invalidConfiguration
        }
        components.queryItems = [
            URLQueryItem(name: "latitude", value: String(format: "%.6f", coordinate.latitude)),
            URLQueryItem(name: "longitude", value: String(format: "%.6f", coordinate.longitude)),
            URLQueryItem(name: "radius", value: String(radiusNM)),
        ]
        guard let url = components.url else { throw AppleNotamError.invalidConfiguration }
        var request = URLRequest(url: url)
        request.timeoutInterval = 45
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        request.setValue("GEOJSON", forHTTPHeaderField: "nmsResponseFormat")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw AppleNotamError.service("No HTTP NOTAM response") }
        guard (200 ..< 300).contains(http.statusCode) else { throw AppleNotamError.service("NOTAM query failed (HTTP \(http.statusCode)).") }
        return try OperationalNotamParser.parseResponse(data, pilot: coordinate, operatingRadiusNM: 2)
    }

    private func statusLine(configured: Bool, loading: Bool, count: Int, error: String?) -> String {
        if loading { return "Refreshing nearby NOTAMs…" }
        if let error { return error }
        if !configured { return "FAA NOTAM credentials have not been imported." }
        return count == 0 ? "No nearby NOTAM restrictions found." : "\(count) nearby NOTAM(s)."
    }

    private func distanceNM(_ a: OperationalNotamCoordinate, _ b: OperationalNotamCoordinate) -> Double {
        let left = CLLocation(latitude: a.latitude, longitude: a.longitude)
        return left.distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude)) / 1_852
    }
}

private enum AppleNotamError: LocalizedError {
    case locationUnavailable
    case credentialsUnavailable
    case invalidConfiguration
    case service(String)

    var errorDescription: String? {
        switch self {
        case .locationUnavailable: "Waiting for GPS location before querying NOTAMs."
        case .credentialsUnavailable: "NOTAM monitoring is enabled, but FAA credentials have not been imported."
        case .invalidConfiguration: "FAA NOTAM service configuration is invalid."
        case let .service(message): message
        }
    }
}

struct AppleNotamPanel: View {
    @ObservedObject var center: AppleNotamCenter
    let location: CLLocation?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Status") {
                    Label(center.state.chipLabel, systemImage: "airplane.departure")
                        .foregroundStyle(color(center.state.chipSeverity))
                    Text(center.state.statusLine).font(.caption).foregroundStyle(.secondary)
                    if let date = center.state.lastUpdated {
                        LabeledContent("Updated", value: date.formatted(date: .omitted, time: .standard))
                    }
                    if center.state.stale { Label("Results are stale", systemImage: "clock.badge.exclamationmark").foregroundStyle(.orange) }
                    Button("Refresh Now") { center.refreshNow(location: location) }
                        .disabled(center.state.loading)
                }
                Section("Nearby NOTAMs") {
                    if center.state.notices.isEmpty {
                        Text(center.state.configured ? "No nearby notices." : "Import an FAA configuration to enable queries.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(center.state.notices) { notice in
                        DisclosureGroup {
                            if !notice.effectiveText.isEmpty { Text(notice.effectiveText) }
                            Text(notice.details).textSelection(.enabled)
                            if !notice.reference.isEmpty { LabeledContent("Reference", value: notice.reference) }
                        } label: {
                            VStack(alignment: .leading) {
                                Text(notice.title).fontWeight(.semibold)
                                Text(notice.summary).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    if center.state.suppressedCount > 0 {
                        Text("\(center.state.suppressedCount) notice(s) are outside the selected radius.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Nearby NOTAMs")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }

    private func color(_ severity: OperationalNotamSeverity) -> Color {
        switch severity {
        case .danger: .red
        case .caution: .orange
        case .normal: .green
        case .neutral: .secondary
        }
    }
}
