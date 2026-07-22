import R2CCore
import R2CAppleRadios
import SwiftUI

private struct DroneConfirmationRequest: Identifiable {
    let id: String
}

struct ContentView: View {
    private let endpoint = MediaStreamEndpoint(designator: "demo")
    @StateObject private var bluetoothScanner = BluetoothRIDScanner()
    @StateObject private var externalReceiver = ExternalRIDUDPReceiver()
    @StateObject private var mediaMTX = MediaMTXViewModel()
    @ObservedObject private var videoFrames = AppleStreamRegistry.shared.primaryModel
    @StateObject private var ridTracks = RIDTrackViewModel()
    @StateObject private var locationProvider = AppleLocationProvider()
    @StateObject private var caltopoSettings = AppleCaltopoSettings()
    @StateObject private var clueStore = AppleClueStore()
    @StateObject private var diagnostics = AppleDiagnosticsCenter()
    @StateObject private var droneConfirmations = AppleDroneConfirmationStore()
    @StateObject private var orgConfigSettings = AppleOrgConfigSettings()
    @StateObject private var orgConfigImporter = AppleOrgConfigImporter()
    @StateObject private var peerCoordinator = AppleTrackerCoordinator()
    @StateObject private var proximityAlerts = AppleProximityAlertCenter()
    @StateObject private var operationalAlerts = AppleOperationalAlertCenter()
    @StateObject private var notams = AppleNotamCenter.shared
    @StateObject private var airspace = AppleAirspaceCenter.shared
    @StateObject private var landRestrictions = AppleLandRestrictionCenter.shared
    @StateObject private var streamRegistry = AppleStreamRegistry.shared
    private let iCloudBackup = AppleICloudBackupCenter.shared
    @State private var showTrackMap = false
    @State private var showCaltopoSettings = false
    @State private var showDiagnosticLogs = false
    @State private var showStatus = false
    @State private var showReleaseNotes = false
    @State private var showAboutPrivacy = false
    @State private var showImportConfig = false
    @State private var showConfigurationTransfer = false
    @State private var pendingImportToken = ""
    @State private var selectedAircraftID: String?
    @State private var pendingDroneConfirmation: DroneConfirmationRequest?
    @State private var controllerRTMPURL = "Connect this device to Wi-Fi"
    @State private var appleRidRelayDestination = "Connect this device to Wi-Fi"
    @State private var appStartedAt = Date()

    private var startupRoot: some View {
        NavigationStack {
            rootScreen
            .navigationTitle("RID-2-Caltopo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Live View", systemImage: "video") { showTrackMap = true }
                        Button("Send app log to Ken…", systemImage: "square.and.arrow.up") {
                            showDiagnosticLogs = true
                        }
                        Button("Status", systemImage: "info.circle") { showStatus = true }
                        Button("Release Notes", systemImage: "doc.text") { showReleaseNotes = true }
                        Divider()
                        Button("Import Config", systemImage: "qrcode.viewfinder") { showImportConfig = true }
                        Button("Backup & Transfer", systemImage: "shippingbox") { showConfigurationTransfer = true }
                        Button("Settings", systemImage: "gearshape") { showCaltopoSettings = true }
                        Button("About & Privacy", systemImage: "hand.raised") {
                            showAboutPrivacy = true
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .navigationDestination(isPresented: $showTrackMap) {
                operationalMapView
            }
            .navigationDestination(isPresented: $showCaltopoSettings) {
                CaltopoSettingsView(settings: caltopoSettings, orgSettings: orgConfigSettings) { configuration in
                    ridTracks.configureCaltopo(configuration)
                    clueStore.configure(configuration)
                }
            }
            .navigationDestination(isPresented: $showConfigurationTransfer) {
                AppleConfigurationTransferView(
                    caltopo: caltopoSettings,
                    organization: orgConfigSettings,
                    identities: droneConfirmations
                )
            }
            .navigationDestination(isPresented: $showDiagnosticLogs) {
                DiagnosticLogView(diagnostics: diagnostics)
            }
            .navigationDestination(isPresented: $showStatus) {
                AppleStatusView(snapshot: statusSnapshot)
            }
            .navigationDestination(isPresented: $showReleaseNotes) {
                AppleReleaseNotesView()
            }
            .navigationDestination(isPresented: $showAboutPrivacy) {
                AboutPrivacyView()
            }
            .navigationDestination(isPresented: $showImportConfig) {
                ConfigImportView(
                    initialToken: pendingImportToken,
                    importer: orgConfigImporter,
                    caltopoSettings: caltopoSettings,
                    orgSettings: orgConfigSettings,
                    identityStore: droneConfirmations
                )
                .id(pendingImportToken)
            }
            .navigationDestination(item: $selectedAircraftID) { aircraftID in
                aircraftDestination(aircraftID)
            }
            .sheet(item: $pendingDroneConfirmation, onDismiss: queueNextDroneConfirmation) { request in
                DroneConfirmationView(
                    remoteID: request.id,
                    existing: droneConfirmations.identity(for: request.id),
                    identityStore: droneConfirmations,
                    onConfirm: peerCoordinator.confirm,
                    onIgnore: { droneConfirmations.ignoreForCurrentFlight(request.id) }
                )
                .interactiveDismissDisabled()
            }
            .task {
                iCloudBackup.configure(
                    caltopo: caltopoSettings,
                    organization: orgConfigSettings,
                    identities: droneConfirmations
                )
                iCloudBackup.scheduleBackup()
                mediaMTX.eventHandler = { event in
                    streamRegistry.handle(event)
                }
                await diagnostics.start()
                AppleLog.info("App", "Application UI started")
                if !ProcessInfo.processInfo.arguments.contains("--no-location") {
                    locationProvider.start()
                }
                if !ProcessInfo.processInfo.arguments.contains("--manual-radios") {
                    try? await bluetoothScanner.start()
                    try? await externalReceiver.start()
                }
                refreshControllerRTMPURL()
                ridTracks.bind(to: bluetoothScanner.observations, sourceID: "bluetooth")
                ridTracks.bind(to: externalReceiver.observations, sourceID: "external-udp")
                ridTracks.configureCaltopo(caltopoSettings.configuration)
                clueStore.configure(caltopoSettings.configuration)
                if !caltopoSettings.mapID.isEmpty, caltopoSettings.mapTitle.isEmpty {
                    await caltopoSettings.loadTeamMaps()
                    if !caltopoSettings.mapTitle.isEmpty {
                        orgConfigSettings.setIncidentMapTitle(caltopoSettings.mapTitle)
                    }
                }
                orgConfigImporter.caltopoConfigurationHandler = { configuration in
                    ridTracks.configureCaltopo(configuration)
                    clueStore.configure(configuration)
                }
                ridTracks.configurePeerCoordination(
                    peerCoordinator,
                    identityProvider: droneConfirmations.identity,
                    peerConfirmationConsumer: droneConfirmations.applyPeerConfirmation,
                    peerConfirmationClearer: droneConfirmations.clearPeerConfirmation
                )
                configurePeerCoordinator()
                configureTrackArchive()
                if ProcessInfo.processInfo.arguments.contains("--demo-notam") {
                    notams.installSimulatorDemo()
                    airspace.installSimulatorDemo()
                } else {
                    notams.configure(orgConfigSettings.faaConfiguration)
                    notams.update(location: locationProvider.lastLocation)
                    airspace.update(location: locationProvider.lastLocation)
                    landRestrictions.update(location: locationProvider.lastLocation)
                }
                if !ProcessInfo.processInfo.arguments.contains("--manual-mediamtx") {
                    mediaMTX.start()
                }
                if ProcessInfo.processInfo.arguments.contains("--anomaly-color") {
                    videoFrames.setAnomalyMode(.colorUniqueness)
                } else if ProcessInfo.processInfo.arguments.contains("--anomaly-off") {
                    videoFrames.setAnomalyMode(.off)
                } else if ProcessInfo.processInfo.arguments.contains("--anomaly-infrared") {
                    videoFrames.setAnomalyMode(.infrared)
                }
                if ProcessInfo.processInfo.arguments.contains("--start-video"),
                   let url = endpoint.loopbackHlsURL {
                    videoFrames.start(url: url)
                }
                if ProcessInfo.processInfo.arguments.contains("--demo-streams") {
                    streamRegistry.handle(.streamConnecting(path: "demo"))
                    streamRegistry.handle(.streamConnecting(path: "RC2/Red1"))
                    streamRegistry.handle(.streamConnecting(path: "AUTEL/Blue2"))
                }
                if ProcessInfo.processInfo.arguments.contains("--start-external-rid") {
                    try? await externalReceiver.start()
                }
                if ProcessInfo.processInfo.arguments.contains("--demo-rid") {
                    ridTracks.startSimulatorDemo(
                        proximityAlert: ProcessInfo.processInfo.arguments.contains("--demo-proximity-alert"),
                        predictiveAlert: ProcessInfo.processInfo.arguments.contains("--demo-predictive-alert"),
                        altitudeAlert: ProcessInfo.processInfo.arguments.contains("--demo-altitude-alert")
                    )
                }
                if ProcessInfo.processInfo.arguments.contains("--demo-confirm-first-drone") {
                    try? await Task.sleep(for: .seconds(2))
                    if let remoteID = ridTracks.tracks.first?.aircraftID {
                        let identity = RidAircraftIdentity(
                            remoteID: remoteID,
                            organization: "NCSSAR",
                            pilotCallsign: "Apple1",
                            droneDescription: "Simulator Drone"
                        )
                        droneConfirmations.confirm(identity)
                        peerCoordinator.confirm(identity)
                    }
                }
                if ProcessInfo.processInfo.arguments.contains("--archive-demo") {
                    try? await Task.sleep(for: .seconds(3))
                    ridTracks.archiveActiveTracks()
                }
                if ProcessInfo.processInfo.arguments.contains("--show-map") {
                    try? await Task.sleep(for: .milliseconds(500))
                    showTrackMap = true
                }
                if ProcessInfo.processInfo.arguments.contains("--show-caltopo-settings") {
                    try? await Task.sleep(for: .milliseconds(500))
                    showCaltopoSettings = true
                }
                if ProcessInfo.processInfo.arguments.contains("--show-anomaly") {
                    try? await Task.sleep(for: .milliseconds(500))
                    UserDefaults.standard.set(OperationalMapVideoLayout.video.rawValue, forKey: "map.videoLayout")
                    showTrackMap = true
                }
                if ProcessInfo.processInfo.arguments.contains("--show-streams") {
                    try? await Task.sleep(for: .milliseconds(500))
                    UserDefaults.standard.set(OperationalMapVideoLayout.video.rawValue, forKey: "map.videoLayout")
                    showTrackMap = true
                }
                if ProcessInfo.processInfo.arguments.contains("--show-transfer") {
                    try? await Task.sleep(for: .milliseconds(500))
                    showConfigurationTransfer = true
                }
                if ProcessInfo.processInfo.arguments.contains("--show-logs") {
                    try? await Task.sleep(for: .milliseconds(500))
                    showDiagnosticLogs = true
                }
                if ProcessInfo.processInfo.arguments.contains("--show-status") {
                    try? await Task.sleep(for: .milliseconds(500))
                    showStatus = true
                }
                if ProcessInfo.processInfo.arguments.contains("--show-privacy") {
                    try? await Task.sleep(for: .milliseconds(500))
                    showAboutPrivacy = true
                }
                if ProcessInfo.processInfo.arguments.contains("--package-logs") {
                    await diagnostics.prepareSelectedBundle()
                }
                if ProcessInfo.processInfo.arguments.contains("--show-aircraft-detail") {
                    try? await Task.sleep(for: .seconds(2))
                    selectedAircraftID = ridTracks.tracks.first?.aircraftID
                }
                if ProcessInfo.processInfo.arguments.contains("--show-import-config") {
                    if ProcessInfo.processInfo.arguments.contains("--demo-org-token") {
                        pendingImportToken = "R2C1:UGedORwNSuM8Sk4NMR5dSs25A0m0NDWVPNAFSA0IcrmsQv0MDCPiKRszONFNFvEC"
                    }
                    try? await Task.sleep(for: .milliseconds(500))
                    showImportConfig = true
                }
            }
        }
    }

    private var lifecycleRoot: some View {
        startupRoot
            .task {
                await monitorOperationalState()
            }
            .onReceive(orgConfigSettings.objectWillChange) { _ in
                guard !ProcessInfo.processInfo.arguments.contains("--demo-notam") else { return }
                Task { @MainActor in
                    await Task.yield()
                    notams.configure(orgConfigSettings.faaConfiguration)
                    notams.update(location: locationProvider.lastLocation)
                }
            }
            .onOpenURL { url in
                let rawValue = url.absoluteString
                guard AndroidConfigTokenCodec.decode(rawValue) != nil else {
                    AppleLog.error("OrgConfig", "Ignored unrecognised URL scheme payload")
                    return
                }
                pendingImportToken = rawValue
                showImportConfig = true
            }
    }

    private var monitoredRoot: some View {
        lifecycleRoot
            .onChange(of: bluetoothStatus) { _, status in
                AppleLog.info("BluetoothRID", status)
            }
            .onChange(of: bluetoothScanner.lastAircraftID) { _, aircraftID in
                if let aircraftID { AppleLog.info("BluetoothRID", "Observation from \(aircraftID)") }
            }
            .onChange(of: bluetoothScanner.lastDecodeError) { _, error in
                if let error { AppleLog.error("BluetoothRID", "Rejected advertisement: \(error)") }
            }
            .onChange(of: externalReceiverStatus) { _, status in
                AppleLog.info("ExternalRID", status)
            }
            .onChange(of: externalReceiver.lastDecodeError) { _, error in
                if let error { AppleLog.error("ExternalRID", "Rejected datagram: \(error)") }
            }
            .onChange(of: mediaMTX.status) { _, status in
                AppleLog.info("MediaMTX", status)
            }
            .onChange(of: videoStatus) { _, status in
                AppleLog.info("Video", status)
            }
            .onChange(of: ridTracks.archiveStatus) { _, status in
                AppleLog.info("Archive", status)
            }
            .onChange(of: ridTracks.caltopoStatus) { _, status in
                AppleLog.info("CalTopo", status)
            }
            .onChange(of: locationProvider.statusText) { _, status in
                AppleLog.info("Location", status)
            }
            .onChange(of: locationProvider.lastLocation?.timestamp) { _, _ in
                peerCoordinator.updatePosition(locationProvider.lastLocation)
            }
            .onChange(of: ridTracks.caltopoRTTMilliseconds) { _, milliseconds in
                peerCoordinator.updateCaltopoRTT(milliseconds: milliseconds)
            }
            .onChange(of: peerConfigurationFingerprint) { _, _ in
                configurePeerCoordinator()
                configureTrackArchive()
            }
            .onChange(of: peerCoordinator.statusDetail) { _, detail in
                AppleLog.info("TrackerPeer", detail)
            }
            .onReceive(ridTracks.$tracks) { tracks in
                updateProximityAlerts()
                let remoteID = droneConfirmations.reconcileActiveFlights(tracks.map(\.aircraftID))
                if !ProcessInfo.processInfo.arguments.contains("--suppress-auto-confirmation"),
                   pendingDroneConfirmation == nil,
                   let remoteID {
                    pendingDroneConfirmation = DroneConfirmationRequest(id: remoteID)
                }
            }
            .onReceive(peerCoordinator.objectWillChange) { _ in
                Task { @MainActor in
                    await Task.yield()
                    updateProximityAlerts()
                }
            }
            .onReceive(droneConfirmations.objectWillChange) { _ in
                Task { @MainActor in
                    await Task.yield()
                    updateProximityAlerts()
                    configureTrackArchive()
                }
            }
            .onChange(of: proximityConfigurationFingerprint) { _, _ in
                updateProximityAlerts()
            }
        .overlay(alignment: .top) {
            VStack(spacing: 0) {
                if operationalAlerts.signalLossAlerts.first != nil || operationalAlerts.altitudeAlerts.first != nil {
                    OperationalAlertBanner(
                        signalLoss: operationalAlerts.signalLossAlerts.first,
                        altitude: operationalAlerts.altitudeAlerts.first,
                        onMap: { showTrackMap = true },
                        onMuteSignal: operationalAlerts.muteSignal,
                        onMuteAltitude: operationalAlerts.muteAltitude
                    )
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
                if let alert = proximityAlerts.activeAlert {
                    ProximityAlertBanner(
                        alert: alert,
                        onMap: { showTrackMap = true },
                        onSuspend: proximityAlerts.suspend
                    )
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .animation(.default, value: alert.alertInstanceID)
                }
            }
        }
    }

    var body: some View {
        monitoredRoot
    }

    private var rootScreen: some View {
        androidParityDashboard
    }

    private var bluetoothStatus: String {
        switch bluetoothScanner.state {
        case .idle: "Idle"
        case .waitingForBluetooth: "Waiting"
        case .scanning: "Scanning"
        case let .unavailable(reason): reason
        }
    }

    @ViewBuilder private func aircraftDestination(_ aircraftID: String) -> some View {
        if let track = ridTracks.tracks.first(where: { track in track.aircraftID == aircraftID }) {
            RIDAircraftDetailView(
                track: track,
                operatorLocation: locationProvider.lastLocation,
                identityStore: droneConfirmations,
                onConfirm: peerCoordinator.confirm
            )
        } else {
            ContentUnavailableView("Aircraft no longer active", systemImage: "airplane")
        }
    }

    private var incidentSection: some View {
        Section("Incident") {
            LabeledContent("Organization", value: orgConfigSettings.organizationName.isEmpty ? "Not configured" : orgConfigSettings.organizationName)
            LabeledContent("Incident", value: orgConfigSettings.incident.isEmpty ? "Not selected" : orgConfigSettings.incident)
            if !orgConfigSettings.operationalPeriod.isEmpty {
                LabeledContent("Operational period", value: orgConfigSettings.operationalPeriod)
            }
            LabeledContent("Coordinator", value: peerCoordinator.status.rawValue)
            if !peerCoordinator.peers.isEmpty { LabeledContent("Peer zones", value: String(peerCoordinator.peers.count)) }
            LabeledContent("Team drones", value: String(droneConfirmations.importedMappingCount))
            Button("Import Config", systemImage: "qrcode.viewfinder") { showImportConfig = true }
            NavigationLink {
                AppleConfigurationTransferView(caltopo: caltopoSettings, organization: orgConfigSettings, identities: droneConfirmations)
            } label: { Label("Backup & Transfer", systemImage: "shippingbox") }
        }
    }

    private var androidParityDashboard: some View {
        ScrollView(.vertical) {
            ScrollView(.horizontal) {
                VStack(alignment: .leading, spacing: 8) {
                    androidRestrictionStrip
                    androidIncidentEditor
                    androidOperationsHeader
                    androidAircraftTable
                }
                .padding(8)
                .frame(minWidth: 1_360, alignment: .topLeading)
            }
        }
        .background(Color(uiColor: .systemBackground))
    }

    private var androidIncidentEditor: some View {
        HStack(spacing: 10) {
            TextField("Incident", text: Binding(
                get: { orgConfigSettings.incident },
                set: { orgConfigSettings.setIncident($0) }
            ))
            .textFieldStyle(.roundedBorder)
            .frame(width: 360)
            TextField("Op Period", text: Binding(
                get: { orgConfigSettings.operationalPeriod },
                set: { orgConfigSettings.setOperationalPeriod($0) }
            ))
            .textFieldStyle(.roundedBorder)
            .frame(width: 220)
            Spacer(minLength: 0)
        }
        .padding(6)
        .background(Color.accentColor.opacity(0.12))
    }

    private var androidOperationsHeader: some View {
        HStack(spacing: 2) {
            Button { showCaltopoSettings = true } label: {
                androidHeaderCell(
                    "Map",
                    caltopoSettings.mapID.isEmpty
                        ? "Connect CalTopo"
                        : (caltopoSettings.mapTitle.isEmpty ? caltopoSettings.mapID : caltopoSettings.mapTitle),
                    width: 190
                )
            }
            .buttonStyle(.plain)
            androidHeaderCell("Coordinator", androidCoordinatorStatus, width: 150)
            androidHeaderCell("Team Drones", "\(droneConfirmations.importedMappingCount)", width: 110)
            androidHeaderCell("", deviceVersionText, width: 190)
            androidHeaderCell("Up Time", appUptimeText, width: 100)
            androidHeaderCell("Caltopo msg rtt", caltopoRTTText, width: 145)
            androidHeaderCell("Invalid RID msgs", "\(ridTracks.filteredObservationCount)", width: 125)
        }
        .padding(2)
        .background(Color.accentColor.opacity(0.18))
    }

    private var androidRestrictionStrip: some View {
        HStack(spacing: 8) {
            if airspace.enabled {
                NavigationLink { AppleAirspacePanel(center: airspace, location: locationProvider.lastLocation) } label: {
                    Label(airspace.state.chipLabel, systemImage: "building.columns")
                }
            }
            if notams.enabled {
                NavigationLink { AppleNotamPanel(center: notams, location: locationProvider.lastLocation) } label: {
                    Label(notams.state.chipLabel, systemImage: "exclamationmark.triangle")
                }
            }
            if landRestrictions.enabled {
                NavigationLink {
                    AppleLandRestrictionPanel(center: landRestrictions, location: locationProvider.lastLocation)
                } label: {
                    Label(landRestrictions.state.chipLabel, systemImage: "leaf")
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 6)
    }

    private var androidAircraftTable: some View {
        VStack(alignment: .leading, spacing: 1) {
            androidAircraftHeader
            if ridTracks.tracks.isEmpty {
                Text("No aircraft detected — Bluetooth and external Remote ID are monitoring.")
                    .foregroundStyle(.secondary)
                    .frame(width: 1_358, height: 58)
                    .background(Color(uiColor: .secondarySystemBackground))
            } else {
                ForEach(ridTracks.tracks) { track in androidAircraftRow(track) }
            }
        }
    }

    private var androidAircraftHeader: some View {
        HStack(spacing: 1) {
            androidGroupedHeader(top: "", bottom: "", width: 28)
            androidGroupedHeader(top: "", bottom: "Track Label:", width: 200)
            androidGroupedHeader(top: "", bottom: "Remote ID:", width: 240)
            VStack(spacing: 1) {
                Text("Waypoints Received")
                    .font(.caption.bold())
                    .frame(width: 640, height: 24)
                    .background(Color.accentColor.opacity(0.16))
                HStack(spacing: 1) {
                    ForEach(["BT4:", "BT5:", "WiFi:", "NaN:"], id: \.self) { label in
                        androidTableHeader(label, width: 120)
                    }
                    androidTableHeader("R2C:", width: 80)
                    androidTableHeader("Total:", width: 80)
                }
            }
            androidGroupedHeader(top: "Flight", bottom: "Duration:", width: 125)
            androidGroupedHeader(top: "", bottom: "R2C RTT:", width: 125)
        }
    }

    private func androidAircraftRow(_ track: RidAircraftTrack) -> some View {
        let identity = droneConfirmations.identity(for: track.aircraftID)
        let confirmed = droneConfirmations.isCurrentFlightConfirmed(track.aircraftID)
        return HStack(spacing: 1) {
            Group {
                if confirmed && caltopoSettings.configuration.liveConfiguration != nil {
                    Image(systemName: "globe.americas.fill")
                        .foregroundStyle(.tint)
                }
            }
                .frame(width: 28, height: 42)
                .background(Color(uiColor: .secondarySystemBackground))
            Button(identity?.mappedID ?? "Confirm Drone") { selectedAircraftID = track.aircraftID }
                .font(.caption.monospaced())
                .lineLimit(1)
                .buttonStyle(.bordered)
                .frame(width: 200, height: 42)
                .background(Color(uiColor: .secondarySystemBackground))
            androidTableValue(track.aircraftID, width: 240, monospaced: true)
            androidTransportCell(track, source: .bluetoothLegacy)
            androidTransportCell(track, source: .bluetoothExtended)
            androidTransportCell(track, source: .wifiBeacon)
            androidTransportCell(track, source: .wifiNan)
            androidTableValue("\(sourceCount(track, .externalReceiver))", width: 80)
            androidTableValue("\(track.points.count)", width: 80)
            androidTableValue(flightDuration(track), width: 125)
            androidTableValue("", width: 125)
        }
        .contentShape(Rectangle())
    }

    private func androidTransportCell(_ track: RidAircraftTrack, source: RidObservation.Source) -> some View {
        HStack(spacing: 1) {
            androidTableValue("\(sourceCount(track, source))", width: 80)
            androidSignalBars(
                rssi: track.lastObservation.source == source ? track.lastSignalStrengthDbm : nil
            )
            .frame(width: 40, height: 42)
            .background(Color(uiColor: .secondarySystemBackground))
        }
    }

    private func androidSignalBars(rssi: Int?) -> some View {
        let filled = rssi.map { value in
            if value >= -60 { return 4 }
            if value >= -70 { return 3 }
            if value >= -80 { return 2 }
            if value >= -90 { return 1 }
            return 0
        } ?? 0
        return HStack(alignment: .bottom, spacing: 2) {
            ForEach(0 ..< 4, id: \.self) { index in
                RoundedRectangle(cornerRadius: 1)
                    .fill(index < filled ? Color.green : Color.green.opacity(0.25))
                    .frame(width: 4, height: CGFloat(5 + index * 4))
            }
        }
    }

    private func androidHeaderCell(_ title: String, _ value: String, width: CGFloat) -> some View {
        VStack(spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.subheadline.bold()).lineLimit(2).minimumScaleFactor(0.7)
        }
        .multilineTextAlignment(.center)
        .frame(width: width, height: 58)
        .background(Color(uiColor: .secondarySystemBackground))
    }

    private func androidTableHeader(_ value: String, width: CGFloat) -> some View {
        Text(value)
            .font(.caption.bold())
            .multilineTextAlignment(.center)
            .frame(width: width, height: 36)
            .background(Color.accentColor.opacity(0.16))
    }

    private func androidGroupedHeader(top: String, bottom: String, width: CGFloat) -> some View {
        VStack(spacing: 1) {
            Text(top)
                .font(.caption.bold())
                .frame(width: width, height: 24)
                .background(Color.accentColor.opacity(0.16))
            androidTableHeader(bottom, width: width)
        }
    }

    private func androidTableValue(_ value: String, width: CGFloat, monospaced: Bool = false) -> some View {
        Text(value)
            .font(monospaced ? .caption.monospaced() : .caption)
            .lineLimit(1)
            .minimumScaleFactor(0.65)
            .frame(width: width, height: 42)
            .background(Color(uiColor: .secondarySystemBackground))
    }

    private func sourceCount(_ track: RidAircraftTrack, _ source: RidObservation.Source) -> Int {
        track.acceptedCountBySource[source] ?? 0
    }

    private func flightDuration(_ track: RidAircraftTrack) -> String {
        guard let first = track.points.first?.receivedAt else { return "0:00" }
        let seconds = max(0, Int(track.lastSignalAt.timeIntervalSince(first)))
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }

    private var deviceVersionText: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        return "\(ProcessInfo.processInfo.hostName)\n\(version)"
    }

    private var appUptimeText: String {
        let seconds = max(0, Int(Date().timeIntervalSince(appStartedAt)))
        return String(format: "%d:%02d:%02d", seconds / 3_600, (seconds / 60) % 60, seconds % 60)
    }

    private var androidCoordinatorStatus: String {
        switch peerCoordinator.status {
        case .healthy: "Tracker OK"
        case .degraded: "Tracker degraded"
        case .standalone: "Disabled"
        case .unconfigured: "Not configured"
        case .connecting: "Connecting"
        }
    }

    private var caltopoRTTText: String {
        ridTracks.caltopoRTTMilliseconds.map { String(format: "%.3f sec", Double($0) / 1_000) } ?? "—"
    }

    private func dashboardIdentityCard(title: String, value: String, detail: String, icon: String) -> some View {
        GroupBox {
            HStack(spacing: 14) {
                Image(systemName: icon).font(.title2).foregroundStyle(.tint)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.caption).foregroundStyle(.secondary)
                    Text(value).font(.title3.bold())
                    Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
            }
            .frame(maxWidth: .infinity, minHeight: 64)
        }
    }

    private func dashboardStatus(title: String, value: String, icon: String) -> some View {
        HStack(spacing: 9) {
            Image(systemName: icon).foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.caption).foregroundStyle(.secondary)
                Text(value).font(.headline).lineLimit(1).minimumScaleFactor(0.75)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var nearbyAircraftSection: some View {
        Section("Nearby Aircraft") {
            if ridTracks.tracks.isEmpty {
                ContentUnavailableView(
                    "No aircraft detected",
                    systemImage: "airplane",
                    description: Text("Bluetooth and the external receiver are monitoring for Remote ID observations.")
                )
            } else {
                ForEach(ridTracks.tracks) { track in
                    NavigationLink {
                        RIDAircraftDetailView(
                            track: track,
                            operatorLocation: locationProvider.lastLocation,
                            identityStore: droneConfirmations,
                            onConfirm: peerCoordinator.confirm
                        )
                    } label: {
                        RIDAircraftSummaryRow(
                            track: track,
                            operatorLocation: locationProvider.lastLocation,
                            identity: droneConfirmations.identity(for: track.aircraftID),
                            confirmedForCurrentFlight: droneConfirmations.isCurrentFlightConfirmed(track.aircraftID)
                        )
                    }
                }
            }
        }
    }

    @ViewBuilder private var trafficSeparationSection: some View {
        if let closestPair {
            Section("Traffic Separation") {
                LabeledContent("Closest pair", value: closestPair.firstAircraftID + " / " + closestPair.secondAircraftID)
                LabeledContent("Horizontal", value: separationFeet(closestPair.horizontalMeters))
                if let vertical = closestPair.verticalMeters { LabeledContent("Vertical", value: separationFeet(vertical)) }
                if let threeDimensional = closestPair.threeDimensionalMeters {
                    LabeledContent("3D separation", value: separationFeet(threeDimensional))
                }
                if proximityAlerts.canResume { Button("Resume Proximity Alert") { proximityAlerts.resume() } }
                Text("Alerts require at least one confirmed team drone and a current local tracker lease plus local Save confirmation.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder private var flightRestrictionsSection: some View {
        if notams.enabled || airspace.enabled {
            Section("Flight Restrictions") {
                if airspace.enabled {
                    NavigationLink { AppleAirspacePanel(center: airspace, location: locationProvider.lastLocation) } label: {
                        LabeledContent("Controlled airspace", value: airspace.state.chipLabel)
                    }
                }
                if notams.enabled {
                    NavigationLink { AppleNotamPanel(center: notams, location: locationProvider.lastLocation) } label: {
                        LabeledContent("Nearby NOTAMs", value: notams.state.chipLabel)
                    }
                    if notams.state.stale {
                        Label("NOTAM results are stale", systemImage: "clock.badge.exclamationmark").foregroundStyle(.orange)
                    }
                }
            }
        }
    }

    private var remoteIDSection: some View {
        Section("Remote ID") {
            HStack {
                Label("Bluetooth scanner", systemImage: "antenna.radiowaves.left.and.right")
                Spacer()
                Text(bluetoothStatus).foregroundStyle(.secondary)
            }
            Button(bluetoothScanner.state == .scanning ? "Stop Bluetooth Scan" : "Start Bluetooth Scan") {
                Task {
                    if bluetoothScanner.state == .scanning { await bluetoothScanner.stop() }
                    else { try? await bluetoothScanner.start() }
                }
            }
            if let aircraftID = bluetoothScanner.lastAircraftID { LabeledContent("Last aircraft", value: aircraftID) }
            if bluetoothScanner.rejectedAdvertisementCount > 0 {
                LabeledContent("Rejected Bluetooth packets", value: String(bluetoothScanner.rejectedAdvertisementCount))
            }
            HStack {
                Label("External Wi-Fi receiver", systemImage: "network")
                Spacer()
                Text(externalReceiverStatus).foregroundStyle(.secondary)
            }
            Button(externalReceiverIsRunning ? "Stop UDP Receiver" : "Start UDP Receiver") {
                Task {
                    if externalReceiverIsRunning { await externalReceiver.stop() }
                    else { try? await externalReceiver.start() }
                }
            }
            Text("UDP port 7654 accepts normalized JSON or compact raw ASTM OpenDroneID from an external Wi-Fi radio.")
                .font(.caption).foregroundStyle(.secondary)
            LabeledContent("Android relay destination", value: appleRidRelayDestination).textSelection(.enabled)
            Text("On Android, enable Apple Wi-Fi Remote ID Relay in Settings and enter this address. The broadcast address 255.255.255.255 may also work when both devices are on the same Wi-Fi network.")
                .font(.caption).foregroundStyle(.secondary)
            LabeledContent("External observations", value: String(externalReceiver.observationCount))
            if externalReceiver.rejectedDatagramCount > 0 {
                LabeledContent("Rejected UDP datagrams", value: String(externalReceiver.rejectedDatagramCount))
            }
            HStack {
                Label("Wi-Fi Aware host", systemImage: "wifi")
                Spacer()
                Text(wifiAwareStatus).foregroundStyle(.secondary)
            }
            HStack {
                Label("My location", systemImage: "location")
                Spacer()
                Text(locationProvider.statusText).foregroundStyle(.secondary)
            }
            if locationProvider.authorizationStatus == .denied {
                Text("Enable Location for RID2Caltopo in the Settings app to show the operator on the map.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            NavigationLink { operationalMapView } label: {
                LabeledContent("Live map", value: String(ridTracks.tracks.count) + " aircraft")
            }
            LabeledContent("Accepted track points", value: String(ridTracks.acceptedObservationCount))
            LabeledContent("Filtered observations", value: String(ridTracks.filteredObservationCount))
            LabeledContent("Archived tracks", value: String(ridTracks.archivedTrackCount))
            Text(ridTracks.archiveStatus).font(.caption).foregroundStyle(.secondary)
            LabeledContent("Tracker archive", value: ridTracks.trackerArchiveStatus)
            Text("Files › On My iPad › RID2Caltopo › RID2Caltopo › Tracks")
                .font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
            if let url = ridTracks.latestArchiveURL {
                ShareLink(item: url) { Label("Export Latest Track", systemImage: "square.and.arrow.up") }
            }
            Button("Archive Active Tracks") { ridTracks.archiveActiveTracks() }.disabled(ridTracks.tracks.isEmpty)
            NavigationLink {
                CaltopoSettingsView(settings: caltopoSettings, orgSettings: orgConfigSettings) { configuration in
                    ridTracks.configureCaltopo(configuration)
                    clueStore.configure(configuration)
                }
            } label: { LabeledContent("CalTopo publishing", value: ridTracks.caltopoStatus) }
            NavigationLink { DiagnosticLogView(diagnostics: diagnostics) } label: {
                Label("Send app log to Ken…", systemImage: "doc.zipper")
            }
        }
    }

    private var anomalySection: some View {
        Section("Anomaly Detector") {
            Picker("Detector mode", selection: Binding(
                get: { videoFrames.anomalyMode },
                set: { videoFrames.setAnomalyMode($0) }
            )) {
                ForEach(AppleAnomalyMode.allCases) { mode in Text(mode.label).tag(mode) }
            }
            LabeledContent("MediaMTX ingest", value: endpoint.loopbackHlsURL?.absoluteString ?? "Unavailable")
            LabeledContent("Controller RTMP target", value: controllerRTMPURL).textSelection(.enabled)
            HStack {
                Label("Native bridge", systemImage: "video")
                Spacer()
                Text(mediaMTX.status).foregroundStyle(.secondary)
            }
            Button(mediaMTX.isRunning ? "Stop MediaMTX" : "Start MediaMTX") {
                if mediaMTX.isRunning { mediaMTX.stop() } else { mediaMTX.start() }
            }
            LabeledContent("Apple decoder", value: videoStatus)
            LabeledContent("Decoded frames", value: String(videoFrames.frameCount))
            LabeledContent("Analyzed frames", value: String(videoFrames.analyzedFrameCount))
            LabeledContent("Analysis drops", value: String(videoFrames.droppedAnalysisFrameCount))
            LabeledContent("Anomaly boxes", value: String(videoFrames.anomalyCount))
            LabeledContent("Frame size", value: videoFrames.dimensions)
            LabeledContent("MediaMTX publisher", value: videoFrames.mediaPublisherStatus)
            LabeledContent("Video recoveries", value: String(videoFrames.recoveryCount))
            LabeledContent("Last recovery", value: videoFrames.lastRecoveryReason)
            Button(videoFrames.state == .idle ? "Connect Video Decoder" : "Disconnect Video Decoder") {
                if videoFrames.state == .idle, let url = endpoint.loopbackHlsURL { videoFrames.start(url: url) }
                else { videoFrames.stop() }
            }
            NavigationLink { AnomalyLiveView(model: videoFrames, streamURL: endpoint.loopbackHlsURL) } label: {
                Label("Open Live Anomaly View", systemImage: "rectangle.inset.filled.and.person.filled")
            }
            NavigationLink { AppleAnomalySettingsView(model: videoFrames) } label: {
                Label("Advanced Anomaly Settings", systemImage: "slider.horizontal.3")
            }
            NavigationLink { AppleStreamsGridView(registry: streamRegistry, ingestAddress: controllerRTMPURL) } label: {
                LabeledContent("Live streams", value: String(liveStreamCount) + " / " + String(AppleStreamRegistry.maximumStreams))
            }
            NavigationLink { AppleCapturedVideoReviewView() } label: {
                Label("Play Captured Video", systemImage: "film")
            }
        }
    }

    private var operationalNotesSection: some View {
        Section {
            Text("Bluetooth and the external UDP receiver start automatically, matching Android's scanner startup. iOS can discover Bluetooth devices in the background, but scans slow down and duplicate advertisements are coalesced. Continuous UDP, video, and anomaly processing require the app in the foreground.")
                .font(.footnote).foregroundStyle(.secondary)
            Text("The Simulator validates the UI and shared logic. Bluetooth, Wi-Fi Aware, background behavior, and live camera streaming require the iPad or iPhone hardware gate.")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    private var liveStreamCount: Int {
        streamRegistry.sessions.reduce(into: 0) { count, session in
            if session.state == .live { count += 1 }
        }
    }

    private var currentIncidentName: String {
        if !caltopoSettings.mapID.isEmpty, !caltopoSettings.mapTitle.isEmpty {
            return caltopoSettings.mapTitle
        }
        return orgConfigSettings.incident.isEmpty ? "Not selected" : orgConfigSettings.incident
    }

    private var operationalMapView: some View {
        RIDTrackMapView(
            model: ridTracks,
            locationProvider: locationProvider,
            caltopoConfiguration: caltopoSettings.configuration,
            videoModel: streamRegistry.focusedSession.model,
            clueStore: clueStore,
            identityStore: droneConfirmations,
            orgSettings: orgConfigSettings,
            notams: notams,
            streamURL: streamRegistry.focusedPlaybackURL,
            ingestAddress: controllerRTMPURL
        )
    }

    private var closestPair: RidPairSeparation? {
        RidTrafficSeparation.closestPair(in: ridTracks.tracks.map { track in
            RidTrafficPosition(
                aircraftID: track.aircraftID,
                latitude: track.lastObservation.latitude,
                longitude: track.lastObservation.longitude,
                altitudeMeters: track.lastObservation.altitudeMeters
            )
        })
    }

    private var statusSnapshot: AppleStatusSnapshot {
        AppleStatusSnapshot(
            bluetoothStatus: bluetoothStatus,
            bluetoothObservations: bluetoothScanner.observationCount,
            bluetoothRejected: bluetoothScanner.rejectedAdvertisementCount,
            externalReceiverStatus: externalReceiverStatus,
            externalRelayDestination: appleRidRelayDestination,
            externalObservations: externalReceiver.observationCount,
            externalRejected: externalReceiver.rejectedDatagramCount,
            wifiAwareStatus: wifiAwareStatus,
            locationStatus: locationProvider.statusText,
            configSource: orgConfigSettings.sourceDescription,
            organization: orgConfigSettings.organizationName,
            incident: orgConfigSettings.incident,
            operationalPeriod: orgConfigSettings.operationalPeriod,
            trackerStatus: peerCoordinator.status.rawValue,
            trackerDetail: peerCoordinator.statusDetail,
            trackerArchiveStatus: ridTracks.trackerArchiveStatus,
            peerCount: peerCoordinator.peers.count,
            caltopoStatus: ridTracks.caltopoStatus,
            activeAircraft: ridTracks.tracks.count,
            acceptedTrackPoints: ridTracks.acceptedObservationCount,
            filteredObservations: ridTracks.filteredObservationCount,
            archivedTracks: ridTracks.archivedTrackCount,
            mediaMTXStatus: mediaMTX.status,
            videoStatus: videoStatus,
            anomalyMode: videoFrames.anomalyMode.label,
            importedMappings: droneConfirmations.importedMappings
        )
    }

    private var peerConfigurationFingerprint: String {
        [
            orgConfigSettings.usePeers ? "1" : "0",
            orgConfigSettings.trackerURLPrefix,
            orgConfigSettings.trackerAPIKey.isEmpty ? "0" : "1",
            caltopoSettings.mapID,
        ].joined(separator: "|")
    }

    private var proximityConfigurationFingerprint: String {
        "\(orgConfigSettings.proximityAlertSpacingFeet)|\(orgConfigSettings.predictiveHeadEnabled)"
    }

    private func configurePeerCoordinator() {
        let arguments = ProcessInfo.processInfo.arguments
        peerCoordinator.updatePosition(locationProvider.lastLocation)
        peerCoordinator.configure(
            usePeers: arguments.contains("--tracker-use-peers") || orgConfigSettings.usePeers,
            trackerURLPrefix: argumentValue("--tracker-url") ?? orgConfigSettings.trackerURLPrefix,
            trackerAPIKey: argumentValue("--tracker-token") ?? orgConfigSettings.trackerAPIKey,
            mapID: argumentValue("--tracker-map") ?? caltopoSettings.mapID
        )
    }

    private func configureTrackArchive() {
        ridTracks.configureTrackArchive(
            trackerURLPrefix: argumentValue("--tracker-url") ?? orgConfigSettings.trackerURLPrefix,
            trackerAPIKey: argumentValue("--tracker-token") ?? orgConfigSettings.trackerAPIKey,
            organization: orgConfigSettings.organizationName,
            incident: currentIncidentName == "Not selected" ? "" : currentIncidentName,
            operationalPeriod: orgConfigSettings.operationalPeriod,
            mapID: caltopoSettings.mapID,
            identities: droneConfirmations.importedMappings
        )
    }

    private func queueNextDroneConfirmation() {
        guard pendingDroneConfirmation == nil,
              let remoteID = droneConfirmations.reconcileActiveFlights(ridTracks.tracks.map(\.aircraftID))
        else { return }
        pendingDroneConfirmation = DroneConfirmationRequest(id: remoteID)
    }

    private func updateProximityAlerts() {
        proximityAlerts.update(
            tracks: ridTracks.tracks,
            thresholdFeet: orgConfigSettings.proximityAlertSpacingFeet,
            predictiveEnabled: ProcessInfo.processInfo.arguments.contains("--demo-predictive-alert")
                || orgConfigSettings.predictiveHeadEnabled,
            operatorLocation: locationProvider.lastLocation,
            identityProvider: droneConfirmations.identity,
            alertEligibility: peerCoordinator.isLocalAlertEligible
        )
    }

    private func updateOperationalAlerts() {
        let demoAlerts = ProcessInfo.processInfo.arguments.contains("--demo-operational-alert")
        operationalAlerts.update(
            tracks: ridTracks.tracks,
            altitudeDisplay: ridTracks.altitudeDisplayByAircraftID,
            operatorLocation: locationProvider.lastLocation,
            identityProvider: droneConfirmations.identity,
            alertEligibility: demoAlerts ? { _ in true } : peerCoordinator.isLocalAlertEligible
        )
    }

    private func monitorOperationalState() async {
        while !Task.isCancelled {
            updateOperationalAlerts()
            if !ProcessInfo.processInfo.arguments.contains("--demo-notam") {
                notams.update(location: locationProvider.lastLocation)
                airspace.update(location: locationProvider.lastLocation)
                landRestrictions.update(location: locationProvider.lastLocation)
            }
            let altitudeText = operationalAlerts.altitudeAlerts.first.map {
                "Altitude Limit Exceeded: " + ($0.mappedID.isEmpty ? $0.remoteID : $0.mappedID)
            }
            let signalText = operationalAlerts.signalLossAlerts.first.map {
                "Drone Signal Lost: " + ($0.mappedID.isEmpty ? $0.remoteID : $0.mappedID)
            }
            let proximityText = proximityAlerts.activeAlert.map { _ in "Aircraft Proximity Alert" }
            AppleExternalDisplayData.shared.update(
                tracks: ridTracks.tracks,
                alertText: altitudeText ?? signalText ?? proximityText
            )
            try? await Task.sleep(for: .seconds(1))
        }
    }

    private func argumentValue(_ flag: String) -> String? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: flag), arguments.indices.contains(index + 1) else { return nil }
        return arguments[index + 1]
    }

    private func separationFeet(_ meters: Double) -> String {
        "\(Int((meters * 3.28084).rounded())) ft"
    }

    private var videoStatus: String {
        switch videoFrames.state {
        case .idle: "Idle"
        case .connecting: "Connecting"
        case .streaming: "Streaming"
        case let .waitingForPublisher(reason): "Waiting for publisher: \(reason)"
        case let .failed(reason): "Failed: \(reason)"
        }
    }

    private var wifiAwareStatus: String {
        switch WiFiAwareRIDCapability.current {
        case .supportedHost: "Hardware capable"
        case .unsupportedHost: "Unsupported hardware"
        case .unavailableOnOS: "Requires iPadOS 26"
        }
    }

    private func refreshControllerRTMPURL() {
        if let address = AppleNetworkAddress.preferredIPv4Address() {
            controllerRTMPURL = "rtmp://\(address):1935/<droneDesignator>"
            appleRidRelayDestination = address
        } else {
            controllerRTMPURL = "Connect this device to Wi-Fi"
            appleRidRelayDestination = "Connect this device to Wi-Fi"
        }
    }

    private var externalReceiverIsRunning: Bool {
        switch externalReceiver.state {
        case .starting, .listening: true
        case .idle, .failed: false
        }
    }

    private var externalReceiverStatus: String {
        switch externalReceiver.state {
        case .idle: "Idle (UDP 7654)"
        case .starting: "Starting"
        case let .listening(port): "Listening on UDP \(port)"
        case let .failed(reason): "Failed: \(reason)"
        }
    }
}

#Preview {
    ContentView()
}
