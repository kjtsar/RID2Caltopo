import CoreLocation
import R2CCore
import SwiftUI

struct RIDAircraftSummaryRow: View {
    let track: RidAircraftTrack
    let operatorLocation: CLLocation?
    let identity: RidAircraftIdentity?
    var confirmedForCurrentFlight = false

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(identity?.mappedID ?? track.aircraftID)
                    .font(.headline.monospaced())
                Spacer()
                RIDSignalStrengthBars(rssi: track.lastSignalStrengthDbm)
                    .frame(width: 38, height: 18)
                Text(rssiText)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            HStack(spacing: 8) {
                if identity != nil {
                    Text(track.aircraftID)
                }
                Label(sourceSummary, systemImage: sourceIcon)
                Text("\(track.points.count) pts")
                if let altitude = track.lastObservation.altitudeMeters {
                    Text("\(Int((altitude * 3.28084).rounded())) ft")
                }
                if let relativePosition {
                    Text(formatRange(relativePosition.distanceMeters))
                }
                Spacer(minLength: 4)
                Label(
                    confirmedForCurrentFlight ? "Saved" : "Tap to Save",
                    systemImage: confirmedForCurrentFlight ? "checkmark.circle.fill" : "exclamationmark.circle"
                )
                .foregroundStyle(confirmedForCurrentFlight ? .green : .orange)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.75)
        }
        .padding(.vertical, 2)
    }

    private var sourceSummary: String {
        track.acceptedCountBySource
            .sorted { $0.key.rawValue < $1.key.rawValue }
            .map { "\(sourceShortName($0.key)) \($0.value)" }
            .joined(separator: " • ")
    }

    private var sourceIcon: String {
        switch track.lastObservation.source {
        case .bluetoothLegacy, .bluetoothExtended: "antenna.radiowaves.left.and.right"
        case .wifiBeacon, .wifiNan: "wifi"
        case .trackerRelay: "arrow.triangle.2.circlepath"
        }
    }

    private var rssiText: String {
        track.lastSignalStrengthDbm.map { "\($0) dBm" } ?? "n/a"
    }

    private var relativePosition: RidRelativePosition? {
        guard let operatorLocation else { return nil }
        return RidGeometry.relativePosition(
            fromLatitude: operatorLocation.coordinate.latitude,
            longitude: operatorLocation.coordinate.longitude,
            toLatitude: track.lastObservation.latitude,
            longitude: track.lastObservation.longitude
        )
    }
}

struct RIDAircraftDetailView: View {
    let track: RidAircraftTrack
    let operatorLocation: CLLocation?
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    let onConfirm: (RidAircraftIdentity) -> Void
    @State private var showConfirmation = false

    var body: some View {
        List {
            Section("Current Flight") {
                Button {
                    showConfirmation = true
                } label: {
                    Label(
                        identityStore.isCurrentFlightConfirmed(track.aircraftID)
                            ? "Update Saved Drone"
                            : "Save Drone for This Flight",
                        systemImage: identityStore.isCurrentFlightConfirmed(track.aircraftID)
                            ? "checkmark.circle.fill"
                            : "square.and.arrow.down"
                    )
                    .font(.headline)
                }
                Text("Saving enables the archive-colored flight tail and, when configured, local ownership, alerts, and CalTopo publishing—matching Android's Save contract.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Identity and signal") {
                LabeledContent("Remote ID", value: track.aircraftID)
                if let identity = identityStore.identity(for: track.aircraftID) {
                    LabeledContent("Mapped ID", value: identity.mappedID)
                    LabeledContent("Organization", value: identity.organization)
                    LabeledContent("Pilot callsign", value: identity.pilotCallsign)
                    LabeledContent("Drone description", value: identity.droneDescription)
                }
                LabeledContent("Last transport", value: sourceName(track.lastObservation.source))
                HStack {
                    Text("Signal")
                    Spacer()
                    RIDSignalStrengthBars(rssi: track.lastSignalStrengthDbm)
                        .frame(width: 48, height: 20)
                    Text(track.lastSignalStrengthDbm.map { "\($0) dBm" } ?? "Unavailable")
                        .foregroundStyle(.secondary)
                }
                LabeledContent("Last received", value: track.lastSignalAt.formatted(date: .omitted, time: .standard))
            }

            Section("Latest aircraft position") {
                LabeledContent("Latitude", value: coordinate(track.lastObservation.latitude))
                LabeledContent("Longitude", value: coordinate(track.lastObservation.longitude))
                LabeledContent("Altitude", value: altitudeText)
                LabeledContent("Heading", value: measurement(track.lastObservation.headingDegrees, suffix: "°"))
                LabeledContent("Speed", value: speedText)
            }

            if let operatorLocation,
               let relativePosition = RidGeometry.relativePosition(
                   fromLatitude: operatorLocation.coordinate.latitude,
                   longitude: operatorLocation.coordinate.longitude,
                   toLatitude: track.lastObservation.latitude,
                   longitude: track.lastObservation.longitude
               ) {
                Section("Relative to this device") {
                    LabeledContent("Range", value: formatRange(relativePosition.distanceMeters))
                    LabeledContent(
                        "Bearing",
                        value: "\(Int(relativePosition.bearingDegrees.rounded()))° \(relativePosition.cardinalDirection)"
                    )
                    LabeledContent("Location accuracy", value: "±\(Int(operatorLocation.horizontalAccuracy.rounded())) m")
                }
            }

            if let latitude = track.lastObservation.operatorLatitude,
               let longitude = track.lastObservation.operatorLongitude {
                Section("Reported operator") {
                    LabeledContent("Latitude", value: coordinate(latitude))
                    LabeledContent("Longitude", value: coordinate(longitude))
                }
            }

            Section("Track") {
                LabeledContent("Accepted points", value: "\(track.points.count)")
                LabeledContent("Distance", value: distanceText)
                ForEach(RidObservation.Source.allCases, id: \.self) { source in
                    if let count = track.acceptedCountBySource[source], count > 0 {
                        LabeledContent(sourceName(source), value: "\(count)")
                    }
                }
            }

        }
        .navigationTitle(track.aircraftID)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if ProcessInfo.processInfo.arguments.contains("--show-drone-confirmation") {
                try? await Task.sleep(for: .milliseconds(500))
                showConfirmation = true
            }
        }
        .sheet(isPresented: $showConfirmation) {
            DroneConfirmationView(
                remoteID: track.aircraftID,
                existing: identityStore.identity(for: track.aircraftID),
                identityStore: identityStore,
                onConfirm: onConfirm
            )
        }
    }

    private var altitudeText: String {
        guard let meters = track.lastObservation.altitudeMeters else { return "Unavailable" }
        return "\(Int((meters * 3.28084).rounded())) ft (\(Int(meters.rounded())) m)"
    }

    private var speedText: String {
        guard let metersPerSecond = track.lastObservation.speedMetersPerSecond else { return "Unavailable" }
        return String(format: "%.1f mph (%.1f m/s)", metersPerSecond * 2.23694, metersPerSecond)
    }

    private var distanceText: String {
        if track.distanceMeters >= 1_000 {
            return String(format: "%.2f km", track.distanceMeters / 1_000)
        }
        return "\(Int(track.distanceMeters.rounded())) m"
    }

    private func coordinate(_ value: Double) -> String {
        String(format: "%.7f°", value)
    }

    private func measurement(_ value: Double?, suffix: String) -> String {
        value.map { String(format: "%.1f%@", $0, suffix) } ?? "Unavailable"
    }

}

@MainActor
final class AppleDroneConfirmationStore: ObservableObject {
    @Published private var sessionIdentities: [String: RidAircraftIdentity] = [:]
    @Published private var peerIdentities: [String: RidAircraftIdentity] = [:]
    @Published private var importedIdentities: [String: RidAircraftIdentity] = [:]
    private var activeRemoteIDs: Set<String> = []
    private var promptedRemoteIDs: Set<String> = []
    private var ignoredRemoteIDs: Set<String> = []
    private let defaults = UserDefaults.standard

    init() {
        guard let entries = defaults.array(forKey: "org.ridMappings") as? [[String: String]] else { return }
        importedIdentities = Dictionary(uniqueKeysWithValues: entries.compactMap { entry in
            guard let remoteID = entry["remoteID"], !remoteID.isEmpty else { return nil }
            let identity = RidAircraftIdentity(
                remoteID: remoteID,
                organization: entry["organization"] ?? "",
                pilotCallsign: Self.importedPilotCallsign(
                    mappedID: entry["mappedID"] ?? "",
                    model: entry["model"] ?? "",
                    remoteID: remoteID
                ),
                droneDescription: entry["model"] ?? "",
                mappedIDOverride: entry["mappedID"]
            )
            return (remoteID, identity)
        })
    }

    func identity(for remoteID: String) -> RidAircraftIdentity? {
        peerIdentities[remoteID] ?? sessionIdentities[remoteID] ?? importedIdentities[remoteID]
    }

    func isCurrentFlightConfirmed(_ remoteID: String) -> Bool {
        sessionIdentities[remoteID] != nil || peerIdentities[remoteID] != nil
    }

    /// Matches Android's current-flight confirmation lifecycle: prompt once for
    /// every newly active flight, including known aircraft, and forget that
    /// flight's session state after the aircraft becomes inactive.
    func reconcileActiveFlights(_ orderedRemoteIDs: [String]) -> String? {
        let currentRemoteIDs = Set(orderedRemoteIDs.filter { !$0.isEmpty })
        let endedRemoteIDs = activeRemoteIDs.subtracting(currentRemoteIDs)
        for remoteID in endedRemoteIDs {
            sessionIdentities.removeValue(forKey: remoteID)
            promptedRemoteIDs.remove(remoteID)
            ignoredRemoteIDs.remove(remoteID)
        }
        activeRemoteIDs = currentRemoteIDs

        guard let candidate = orderedRemoteIDs.first(where: { remoteID in
            !remoteID.isEmpty
                && !promptedRemoteIDs.contains(remoteID)
                && !ignoredRemoteIDs.contains(remoteID)
                && !isCurrentFlightConfirmed(remoteID)
        }) else { return nil }
        promptedRemoteIDs.insert(candidate)
        AppleLog.info("DroneConfirmation", "Queueing confirmation for active flight remoteId=\(candidate)")
        return candidate
    }

    func ignoreForCurrentFlight(_ remoteID: String) {
        guard !remoteID.isEmpty else { return }
        ignoredRemoteIDs.insert(remoteID)
        AppleLog.info("DroneConfirmation", "Ignored active flight remoteId=\(remoteID) sessionOnly=true")
    }

    var importedMappingCount: Int { importedIdentities.count }

    var importedMappings: [RidAircraftIdentity] {
        importedIdentities.values.sorted { $0.remoteID < $1.remoteID }
    }

    func confirm(_ identity: RidAircraftIdentity) {
        guard identity.isComplete else { return }
        sessionIdentities[identity.remoteID] = identity
        AppleLog.info(
            "DroneConfirmation",
            "Confirmed remoteId=\(identity.remoteID) mappedId=\(identity.mappedID) organization='\(identity.organization)' callsign='\(identity.pilotCallsign)' description='\(identity.droneDescription)' sessionOnly=true"
        )
    }

    func applyImportedMappings(_ mappings: [OrgConfigRIDMapping]) {
        importedIdentities = Dictionary(uniqueKeysWithValues: mappings.map { mapping in
            (
                mapping.remoteID,
                RidAircraftIdentity(
                    remoteID: mapping.remoteID,
                    organization: mapping.organization,
                    pilotCallsign: Self.importedPilotCallsign(
                        mappedID: mapping.mappedID,
                        model: mapping.model,
                        remoteID: mapping.remoteID
                    ),
                    droneDescription: mapping.model,
                    mappedIDOverride: mapping.mappedID
                )
            )
        })
        let persisted = mappings.map { mapping in
            [
                "remoteID": mapping.remoteID,
                "mappedID": mapping.mappedID,
                "organization": mapping.organization,
                "model": mapping.model,
                "owner": mapping.owner,
            ]
        }
        defaults.set(persisted, forKey: "org.ridMappings")
    }

    func applyPeerConfirmation(_ identity: TrackerCoordinationIdentity) {
        peerIdentities[identity.remoteID] = RidAircraftIdentity(
            remoteID: identity.remoteID,
            organization: identity.organization,
            pilotCallsign: identity.ownerName,
            droneDescription: identity.model,
            mappedIDOverride: identity.mappedID
        )
    }

    func clearPeerConfirmation(remoteID: String) {
        peerIdentities.removeValue(forKey: remoteID)
    }

    func resetPersistedState() {
        importedIdentities.removeAll()
        sessionIdentities.removeAll()
        peerIdentities.removeAll()
        activeRemoteIDs.removeAll()
        promptedRemoteIDs.removeAll()
        ignoredRemoteIDs.removeAll()
        defaults.removeObject(forKey: "org.ridMappings")
    }

    private static func importedPilotCallsign(mappedID: String, model: String, remoteID: String) -> String {
        RidAircraftIdentity.guessPilotCallsign(mappedID: mappedID, model: model, remoteID: remoteID)
    }
}

struct DroneConfirmationView: View {
    let remoteID: String
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    let onConfirm: (RidAircraftIdentity) -> Void
    let onIgnore: (() -> Void)?
    @Environment(\.dismiss) private var dismiss
    @State private var organization: String
    @State private var pilotCallsign: String
    @State private var droneDescription: String

    init(
        remoteID: String,
        existing: RidAircraftIdentity?,
        identityStore: AppleDroneConfirmationStore,
        onConfirm: @escaping (RidAircraftIdentity) -> Void,
        onIgnore: (() -> Void)? = nil
    ) {
        self.remoteID = remoteID
        self.identityStore = identityStore
        self.onConfirm = onConfirm
        self.onIgnore = onIgnore
        _organization = State(initialValue: existing?.organization ?? "")
        _pilotCallsign = State(initialValue: existing?.pilotCallsign ?? "")
        _droneDescription = State(initialValue: existing?.droneDescription ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Confirm Drone") {
                    LabeledContent("Remote ID", value: remoteID)
                    TextField("Organization", text: $organization)
                    TextField("Pilot Callsign", text: $pilotCallsign)
                    TextField("Drone Description", text: $droneDescription)
                }
                Section {
                    Text("Matching Android, all three fields are required. Save keeps the local identity for this session and broadcasts it when tracker coordination is configured.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Confirm Drone")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if let onIgnore {
                        Button("Ignore") {
                            onIgnore()
                            dismiss()
                        }
                    } else {
                        Button("Cancel") { dismiss() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        identityStore.confirm(identity)
                        onConfirm(identity)
                        dismiss()
                    }
                    .disabled(!identity.isComplete)
                }
            }
        }
    }

    private var identity: RidAircraftIdentity {
        RidAircraftIdentity(
            remoteID: remoteID,
            organization: organization,
            pilotCallsign: pilotCallsign,
            droneDescription: droneDescription
        )
    }
}

private struct RIDSignalStrengthBars: View {
    let rssi: Int?

    var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0 ..< 4, id: \.self) { index in
                RoundedRectangle(cornerRadius: 1.5)
                    .fill(index < filledBarCount ? Color.green : Color.green.opacity(0.25))
                    .frame(maxWidth: .infinity)
                    .frame(height: CGFloat(5 + index * 4))
            }
        }
        .accessibilityLabel("Signal strength")
        .accessibilityValue(rssi.map { "\($0) decibels milliwatt" } ?? "unavailable")
    }

    private var filledBarCount: Int {
        guard let rssi else { return 0 }
        if rssi >= -60 { return 4 }
        if rssi >= -70 { return 3 }
        if rssi >= -80 { return 2 }
        if rssi >= -90 { return 1 }
        return 0
    }
}

private func sourceShortName(_ source: RidObservation.Source) -> String {
    switch source {
    case .bluetoothLegacy: "BT4"
    case .bluetoothExtended: "BT5"
    case .wifiBeacon: "Wi-Fi"
    case .wifiNan: "NAN"
    case .trackerRelay: "R2C"
    }
}

private func sourceName(_ source: RidObservation.Source) -> String {
    switch source {
    case .bluetoothLegacy: "Bluetooth Legacy"
    case .bluetoothExtended: "Bluetooth Extended"
    case .wifiBeacon: "Wi-Fi Beacon"
    case .wifiNan: "Wi-Fi NAN"
    case .trackerRelay: "Tracker Relay"
    }
}

private func formatRange(_ meters: Double) -> String {
    let feet = meters * 3.28084
    if feet >= 5_280 {
        return String(format: "%.2f mi", feet / 5_280)
    }
    return "\(Int(feet.rounded())) ft"
}
