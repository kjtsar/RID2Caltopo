import R2CCore
import R2CAppleRadios
import SwiftUI

struct ContentView: View {
    private let endpoint = MediaStreamEndpoint(designator: "demo")
    @StateObject private var bluetoothScanner = BluetoothRIDScanner()
    @StateObject private var externalReceiver = ExternalRIDUDPReceiver()
    @StateObject private var mediaMTX = MediaMTXViewModel()
    @StateObject private var videoFrames = AppleVideoFrameSource()
    @StateObject private var ridTracks = RIDTrackViewModel()
    @StateObject private var locationProvider = AppleLocationProvider()
    @StateObject private var caltopoSettings = AppleCaltopoSettings()
    @StateObject private var diagnostics = AppleDiagnosticsCenter()
    @StateObject private var droneConfirmations = AppleDroneConfirmationStore()
    @StateObject private var orgConfigSettings = AppleOrgConfigSettings()
    @StateObject private var orgConfigImporter = AppleOrgConfigImporter()
    @StateObject private var peerCoordinator = AppleTrackerCoordinator()
    @StateObject private var proximityAlerts = AppleProximityAlertCenter()
    @State private var showTrackMap = false
    @State private var showCaltopoSettings = false
    @State private var showAnomalyView = false
    @State private var showDiagnosticLogs = false
    @State private var showStatus = false
    @State private var showAboutPrivacy = false
    @State private var showImportConfig = false
    @State private var pendingImportToken = ""
    @State private var selectedAircraftID: String?
    @State private var controllerRTMPURL = "Connect this device to Wi-Fi"
    @State private var appleRidRelayDestination = "Connect this device to Wi-Fi"

    var body: some View {
        NavigationStack {
            List {
                Section("Incident") {
                    LabeledContent(
                        "Organization",
                        value: orgConfigSettings.organizationName.isEmpty ? "Not configured" : orgConfigSettings.organizationName
                    )
                    LabeledContent(
                        "Incident",
                        value: orgConfigSettings.incident.isEmpty ? "Not selected" : orgConfigSettings.incident
                    )
                    if !orgConfigSettings.operationalPeriod.isEmpty {
                        LabeledContent("Operational period", value: orgConfigSettings.operationalPeriod)
                    }
                    LabeledContent("Coordinator", value: peerCoordinator.status.rawValue)
                    if !peerCoordinator.peers.isEmpty {
                        LabeledContent("Peer zones", value: "\(peerCoordinator.peers.count)")
                    }
                    LabeledContent("Team drones", value: "\(droneConfirmations.importedMappingCount)")
                    Button("Import Config", systemImage: "qrcode.viewfinder") { showImportConfig = true }
                }

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
                                    identity: droneConfirmations.identity(for: track.aircraftID)
                                )
                            }
                        }
                    }
                }

                if let closestPair {
                    Section("Traffic Separation") {
                        LabeledContent(
                            "Closest pair",
                            value: "\(closestPair.firstAircraftID) / \(closestPair.secondAircraftID)"
                        )
                        LabeledContent(
                            "Horizontal",
                            value: separationFeet(closestPair.horizontalMeters)
                        )
                        if let vertical = closestPair.verticalMeters {
                            LabeledContent("Vertical", value: separationFeet(vertical))
                        }
                        if let threeDimensional = closestPair.threeDimensionalMeters {
                            LabeledContent("3D separation", value: separationFeet(threeDimensional))
                        }
                        if proximityAlerts.canResume {
                            Button("Resume Proximity Alert") {
                                proximityAlerts.resume()
                            }
                        }
                        Text("Alerts require at least one confirmed team drone and a current local tracker lease plus local Save confirmation.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Remote ID") {
                    HStack {
                        Label("Bluetooth scanner", systemImage: "antenna.radiowaves.left.and.right")
                        Spacer()
                        Text(bluetoothStatus)
                            .foregroundStyle(.secondary)
                    }
                    Button(bluetoothScanner.state == .scanning ? "Stop Bluetooth Scan" : "Start Bluetooth Scan") {
                        Task {
                            if bluetoothScanner.state == .scanning {
                                await bluetoothScanner.stop()
                            } else {
                                try? await bluetoothScanner.start()
                            }
                        }
                    }
                    if let aircraftID = bluetoothScanner.lastAircraftID {
                        LabeledContent("Last aircraft", value: aircraftID)
                    }
                    if bluetoothScanner.rejectedAdvertisementCount > 0 {
                        LabeledContent("Rejected Bluetooth packets", value: "\(bluetoothScanner.rejectedAdvertisementCount)")
                    }
                    HStack {
                        Label("External Wi-Fi receiver", systemImage: "network")
                        Spacer()
                        Text(externalReceiverStatus)
                            .foregroundStyle(.secondary)
                    }
                    Button(externalReceiverIsRunning ? "Stop UDP Receiver" : "Start UDP Receiver") {
                        Task {
                            if externalReceiverIsRunning {
                                await externalReceiver.stop()
                            } else {
                                try? await externalReceiver.start()
                            }
                        }
                    }
                    Text("UDP port 7654 accepts normalized JSON or compact raw ASTM OpenDroneID from an external Wi-Fi radio.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    LabeledContent("Android relay destination", value: appleRidRelayDestination)
                        .textSelection(.enabled)
                    Text("On Android, enable Apple Wi-Fi Remote ID Relay in Settings and enter this address. The broadcast address 255.255.255.255 may also work when both devices are on the same Wi-Fi network.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    LabeledContent("External observations", value: "\(externalReceiver.observationCount)")
                    if externalReceiver.rejectedDatagramCount > 0 {
                        LabeledContent("Rejected UDP datagrams", value: "\(externalReceiver.rejectedDatagramCount)")
                    }
                    HStack {
                        Label("Wi-Fi Aware host", systemImage: "wifi")
                        Spacer()
                        Text(wifiAwareStatus)
                            .foregroundStyle(.secondary)
                    }
                    HStack {
                        Label("My location", systemImage: "location")
                        Spacer()
                        Text(locationProvider.statusText)
                            .foregroundStyle(.secondary)
                    }
                    if locationProvider.authorizationStatus == .denied {
                        Text("Enable Location for RID2Caltopo in the Settings app to show the operator on the map.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    NavigationLink {
                        RIDTrackMapView(model: ridTracks, locationProvider: locationProvider)
                    } label: {
                        LabeledContent("Live map", value: "\(ridTracks.tracks.count) aircraft")
                    }
                    LabeledContent("Accepted track points", value: "\(ridTracks.acceptedObservationCount)")
                    LabeledContent("Filtered observations", value: "\(ridTracks.filteredObservationCount)")
                    LabeledContent("Archived tracks", value: "\(ridTracks.archivedTrackCount)")
                    Text(ridTracks.archiveStatus)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button("Archive Active Tracks") {
                        ridTracks.archiveActiveTracks()
                    }
                    .disabled(ridTracks.tracks.isEmpty)
                    NavigationLink {
                        CaltopoSettingsView(settings: caltopoSettings, orgSettings: orgConfigSettings) { configuration in
                            ridTracks.configureCaltopo(configuration)
                        }
                    } label: {
                        LabeledContent("CalTopo publishing", value: ridTracks.caltopoStatus)
                    }
                    NavigationLink {
                        DiagnosticLogView(diagnostics: diagnostics)
                    } label: {
                        Label("Send app log to Ken…", systemImage: "doc.zipper")
                    }
                }

                Section("Anomaly Detector") {
                    Picker("Detector mode", selection: Binding(
                        get: { videoFrames.anomalyMode },
                        set: { videoFrames.setAnomalyMode($0) }
                    )) {
                        ForEach(AppleAnomalyMode.allCases) { mode in
                            Text(mode.label).tag(mode)
                        }
                    }
                    LabeledContent(
                        "MediaMTX ingest",
                        value: endpoint.loopbackHlsURL?.absoluteString ?? "Unavailable"
                    )
                    LabeledContent("Controller RTMP target", value: controllerRTMPURL)
                        .textSelection(.enabled)
                    HStack {
                        Label("Native bridge", systemImage: "video")
                        Spacer()
                        Text(mediaMTX.status)
                            .foregroundStyle(.secondary)
                    }
                    Button(mediaMTX.isRunning ? "Stop MediaMTX" : "Start MediaMTX") {
                        if mediaMTX.isRunning {
                            mediaMTX.stop()
                        } else {
                            mediaMTX.start()
                        }
                    }
                    LabeledContent("Apple decoder", value: videoStatus)
                    LabeledContent("Decoded frames", value: "\(videoFrames.frameCount)")
                    LabeledContent("Analyzed frames", value: "\(videoFrames.analyzedFrameCount)")
                    LabeledContent("Analysis drops", value: "\(videoFrames.droppedAnalysisFrameCount)")
                    LabeledContent("Anomaly boxes", value: "\(videoFrames.anomalyCount)")
                    LabeledContent("Frame size", value: videoFrames.dimensions)
                    Button(videoFrames.state == .idle ? "Connect Video Decoder" : "Disconnect Video Decoder") {
                        if videoFrames.state == .idle {
                            if let url = endpoint.loopbackHlsURL {
                                videoFrames.start(url: url)
                            }
                        } else {
                            videoFrames.stop()
                        }
                    }
                    NavigationLink {
                        AnomalyLiveView(model: videoFrames, streamURL: endpoint.loopbackHlsURL)
                    } label: {
                        Label("Open Live Anomaly View", systemImage: "rectangle.inset.filled.and.person.filled")
                    }
                }

                Section {
                    Text("Bluetooth and the external UDP receiver start automatically, matching Android's scanner startup. iOS can discover Bluetooth devices in the background, but scans slow down and duplicate advertisements are coalesced. Continuous UDP, video, and anomaly processing require the app in the foreground.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Text("The Simulator validates the UI and shared logic. Bluetooth, Wi-Fi Aware, background behavior, and live camera streaming require the iPad or iPhone hardware gate.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("RID-2-Caltopo")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Live View", systemImage: "video") { showAnomalyView = true }
                        Button("Send app log to Ken…", systemImage: "square.and.arrow.up") {
                            showDiagnosticLogs = true
                        }
                        Button("Status", systemImage: "info.circle") { showStatus = true }
                        Divider()
                        Button("Import Config", systemImage: "qrcode.viewfinder") { showImportConfig = true }
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
                RIDTrackMapView(model: ridTracks, locationProvider: locationProvider)
            }
            .navigationDestination(isPresented: $showCaltopoSettings) {
                CaltopoSettingsView(settings: caltopoSettings, orgSettings: orgConfigSettings) { configuration in
                    ridTracks.configureCaltopo(configuration)
                }
            }
            .navigationDestination(isPresented: $showAnomalyView) {
                AnomalyLiveView(model: videoFrames, streamURL: endpoint.loopbackHlsURL)
            }
            .navigationDestination(isPresented: $showDiagnosticLogs) {
                DiagnosticLogView(diagnostics: diagnostics)
            }
            .navigationDestination(isPresented: $showStatus) {
                AppleStatusView(snapshot: statusSnapshot)
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
                if let track = ridTracks.tracks.first(where: { $0.aircraftID == aircraftID }) {
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
            .task {
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
                ridTracks.configurePeerCoordination(
                    peerCoordinator,
                    identityProvider: droneConfirmations.identity,
                    peerConfirmationConsumer: droneConfirmations.applyPeerConfirmation,
                    peerConfirmationClearer: droneConfirmations.clearPeerConfirmation
                )
                configurePeerCoordinator()
                if ProcessInfo.processInfo.arguments.contains("--start-mediamtx") {
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
                if ProcessInfo.processInfo.arguments.contains("--start-external-rid") {
                    try? await externalReceiver.start()
                }
                if ProcessInfo.processInfo.arguments.contains("--demo-rid") {
                    ridTracks.startSimulatorDemo(
                        proximityAlert: ProcessInfo.processInfo.arguments.contains("--demo-proximity-alert"),
                        predictiveAlert: ProcessInfo.processInfo.arguments.contains("--demo-predictive-alert")
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
                    showAnomalyView = true
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
            .onOpenURL { url in
                let rawValue = url.absoluteString
                guard AndroidConfigTokenCodec.decode(rawValue) != nil else {
                    AppleLog.error("OrgConfig", "Ignored unrecognised URL scheme payload")
                    return
                }
                pendingImportToken = rawValue
                showImportConfig = true
            }
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
            .onChange(of: peerConfigurationFingerprint) { _, _ in
                configurePeerCoordinator()
            }
            .onChange(of: peerCoordinator.statusDetail) { _, detail in
                AppleLog.info("TrackerPeer", detail)
            }
            .onReceive(ridTracks.$tracks) { _ in
                updateProximityAlerts()
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
                }
            }
            .onChange(of: proximityConfigurationFingerprint) { _, _ in
                updateProximityAlerts()
            }
        }
        .overlay(alignment: .top) {
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

    private var bluetoothStatus: String {
        switch bluetoothScanner.state {
        case .idle: "Idle"
        case .waitingForBluetooth: "Waiting"
        case .scanning: "Scanning"
        case let .unavailable(reason): reason
        }
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
            controllerRTMPURL = "rtmp://\(address):1935/\(endpoint.designator)"
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
