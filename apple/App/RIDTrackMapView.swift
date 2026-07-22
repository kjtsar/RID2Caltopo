import AVKit
import MapKit
import R2CCore
import SwiftUI

@MainActor
private final class ApplePilotDisplayStore: ObservableObject {
    @Published private(set) var revision = 0
    private let defaults = UserDefaults.standard

    func preference(for pilotCallsign: String?) -> PilotDisplayPreference {
        guard let pilotKey = PilotDisplayPreference.normalizePilotCallsign(pilotCallsign) else {
            return PilotDisplayPreference()
        }
        let key = storageKey(pilotKey)
        return PilotDisplayPreference(
            activeTrackColor: defaults.string(forKey: key + ".active") ?? defaultActiveTrackColor,
            archiveTrackColor: defaults.string(forKey: key + ".archive") ?? defaultArchiveTrackColor,
            bearingEnabled: defaults.bool(forKey: key + ".bearing")
        )
    }

    func save(_ preference: PilotDisplayPreference, for pilotCallsign: String) {
        guard let pilotKey = PilotDisplayPreference.normalizePilotCallsign(pilotCallsign) else { return }
        let sanitized = PilotDisplayPreference(
            activeTrackColor: preference.activeTrackColor,
            archiveTrackColor: preference.archiveTrackColor,
            bearingEnabled: preference.bearingEnabled
        )
        let key = storageKey(pilotKey)
        defaults.set(sanitized.activeTrackColor, forKey: key + ".active")
        defaults.set(sanitized.archiveTrackColor, forKey: key + ".archive")
        defaults.set(sanitized.bearingEnabled, forKey: key + ".bearing")
        revision += 1
    }

    func reset(_ pilotCallsign: String) {
        guard let pilotKey = PilotDisplayPreference.normalizePilotCallsign(pilotCallsign) else { return }
        let key = storageKey(pilotKey)
        defaults.removeObject(forKey: key + ".active")
        defaults.removeObject(forKey: key + ".archive")
        defaults.removeObject(forKey: key + ".bearing")
        revision += 1
    }

    private func storageKey(_ pilotKey: String) -> String {
        let safe = pilotKey.unicodeScalars.filter {
            CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_")).contains($0)
        }.map(String.init).joined()
        return "map.pilotDisplay.\(safe.isEmpty ? "pilot" : safe)"
    }
}

@MainActor
private final class AppleMapArtifactModel: ObservableObject {
    @Published private(set) var snapshot = CaltopoArtifactSnapshot()
    @Published private(set) var status = "Map artifacts not configured"
    @Published var hiddenFolderIDs: Set<String> = []
    @Published var hiddenItemIDs: Set<String> = []

    private var refreshTask: Task<Void, Never>?
    private var configurationFingerprint = ""
    private var configuredMapID = ""
    private var visibilityInitialized = false

    var visibleSnapshot: CaltopoArtifactSnapshot {
        snapshot.hiding(folderIDs: hiddenFolderIDs, itemIDs: hiddenItemIDs)
    }

    func configure(_ configuration: AppleCaltopoConfiguration) {
        let fingerprint = [
            configuration.domainAndPort,
            configuration.mapID,
            configuration.credentialID,
            String(configuration.credentialSecret.hashValue),
        ].joined(separator: "|")
        guard fingerprint != configurationFingerprint else { return }
        configurationFingerprint = fingerprint
        configuredMapID = configuration.mapID
        refreshTask?.cancel()
        hiddenFolderIDs = []
        hiddenItemIDs = []
        visibilityInitialized = false
        guard !configuration.domainAndPort.isEmpty,
              !configuration.mapID.isEmpty,
              !configuration.credentialID.isEmpty,
              !configuration.credentialSecret.isEmpty
        else {
            snapshot = CaltopoArtifactSnapshot()
            status = "Map artifacts not configured"
            return
        }
        if let cached = Self.loadCachedSnapshot(mapID: configuration.mapID) {
            snapshot = cached
            restoreVisibility(mapID: configuration.mapID, fallbackFolders: cached.folders)
            status = "Cached: \(cached.points.count) markers, \(cached.lines.count) lines, \(cached.polygons.count) areas"
        } else {
            restoreVisibility(mapID: configuration.mapID, fallbackFolders: [])
        }
        let live = CaltopoLiveConfiguration(
            domainAndPort: configuration.domainAndPort,
            mapID: configuration.mapID,
            credentialID: configuration.credentialID,
            credentialSecretBase64: configuration.credentialSecret
        )
        refreshTask = Task { [weak self] in
            guard let self else { return }
            do {
                let client = try CaltopoLiveClient(configuration: live)
                while !Task.isCancelled {
                    await self.refresh(using: client)
                    try? await Task.sleep(for: .seconds(90))
                }
            } catch {
                status = "Map artifacts: \(error.localizedDescription)"
            }
        }
    }

    func refresh(_ configuration: AppleCaltopoConfiguration) {
        configurationFingerprint = ""
        configure(configuration)
    }

    func toggleFolder(_ folder: CaltopoArtifactFolder) {
        if isFolderEffectivelyVisible(folder) {
            hiddenFolderIDs.insert(folder.id)
        } else {
            unhideFolderAndAncestors(folder.id)
        }
        persistVisibility()
    }

    func toggleItem(_ item: CaltopoArtifactItem) {
        if isItemEffectivelyVisible(item) {
            hiddenItemIDs.insert(item.id)
        } else {
            hiddenItemIDs.remove(item.id)
            unhideFolderAndAncestors(item.folderID)
        }
        persistVisibility()
    }

    func setItems(_ items: [CaltopoArtifactItem], visible: Bool) {
        let ids = Set(items.map(\.id))
        if visible {
            hiddenItemIDs.subtract(ids)
            if let folderID = items.first?.folderID { unhideFolderAndAncestors(folderID) }
        }
        else { hiddenItemIDs.formUnion(ids) }
        persistVisibility()
    }

    func isFolderEffectivelyVisible(_ folder: CaltopoArtifactFolder) -> Bool {
        var current: CaltopoArtifactFolder? = folder
        var visited: Set<String> = []
        while let value = current, visited.insert(value.id).inserted {
            if hiddenFolderIDs.contains(value.id) { return false }
            current = value.parentID.flatMap { parentID in snapshot.folders.first { $0.id == parentID } }
        }
        return true
    }

    func isItemEffectivelyVisible(_ item: CaltopoArtifactItem) -> Bool {
        guard !hiddenItemIDs.contains(item.id),
              let folder = snapshot.folders.first(where: { $0.id == item.folderID })
        else { return false }
        return isFolderEffectivelyVisible(folder)
    }

    private func unhideFolderAndAncestors(_ folderID: String) {
        var currentID: String? = folderID
        var visited: Set<String> = []
        while let id = currentID, visited.insert(id).inserted {
            hiddenFolderIDs.remove(id)
            currentID = snapshot.folders.first { $0.id == id }?.parentID
        }
    }

    private func refresh(using client: CaltopoLiveClient) async {
        status = "Refreshing CalTopo artifacts…"
        do {
            let value = try await client.fetchMapArtifacts()
            snapshot = value
            if !visibilityInitialized {
                hiddenFolderIDs = Set(value.folders.filter { !$0.initiallyVisible }.map(\.id))
                visibilityInitialized = true
                persistVisibility()
            }
            status = "\(value.points.count) markers, \(value.lines.count) lines, \(value.polygons.count) areas"
            Self.saveCachedSnapshot(value, mapID: configuredMapID)
            AppleLog.info("Map", "CalTopo artifact refresh features=\(value.totalFeatureCount) ignoredTracks=\(value.ignoredTrackCount) \(status)")
        } catch {
            status = "Map artifact refresh failed: \(error.localizedDescription)"
            AppleLog.error("Map", status)
        }
    }

    private static func cacheURL(mapID: String) -> URL {
        let root = (FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory)
            .appendingPathComponent("RID2Caltopo/MapArtifacts", isDirectory: true)
        let safeMapID = mapID.filter { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
        return root.appendingPathComponent("\(safeMapID.isEmpty ? "map" : safeMapID).json")
    }

    private func restoreVisibility(mapID: String, fallbackFolders: [CaltopoArtifactFolder]) {
        let keys = Self.visibilityKeys(mapID: mapID)
        let defaults = UserDefaults.standard
        let hasSavedFolders = defaults.object(forKey: keys.folders) != nil
        let hasSavedItems = defaults.object(forKey: keys.items) != nil
        if hasSavedFolders || hasSavedItems {
            hiddenFolderIDs = Set(defaults.stringArray(forKey: keys.folders) ?? [])
            hiddenItemIDs = Set(defaults.stringArray(forKey: keys.items) ?? [])
            visibilityInitialized = true
        } else if !fallbackFolders.isEmpty {
            hiddenFolderIDs = Set(fallbackFolders.filter { !$0.initiallyVisible }.map(\.id))
            visibilityInitialized = true
            persistVisibility()
        }
    }

    private func persistVisibility() {
        guard !configuredMapID.isEmpty else { return }
        let keys = Self.visibilityKeys(mapID: configuredMapID)
        UserDefaults.standard.set(hiddenFolderIDs.sorted(), forKey: keys.folders)
        UserDefaults.standard.set(hiddenItemIDs.sorted(), forKey: keys.items)
    }

    private static func visibilityKeys(mapID: String) -> (folders: String, items: String) {
        let safeMapID = mapID.filter { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }
        let suffix = safeMapID.isEmpty ? "map" : safeMapID
        return ("map.visibility.\(suffix).folders", "map.visibility.\(suffix).items")
    }

    private static func loadCachedSnapshot(mapID: String) -> CaltopoArtifactSnapshot? {
        guard let data = try? Data(contentsOf: cacheURL(mapID: mapID)) else { return nil }
        return try? JSONDecoder().decode(CaltopoArtifactSnapshot.self, from: data)
    }

    private static func saveCachedSnapshot(_ snapshot: CaltopoArtifactSnapshot, mapID: String) {
        let destination = cacheURL(mapID: mapID)
        do {
            try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(snapshot).write(to: destination, options: .atomic)
        } catch {
            AppleLog.warning("Map", "Artifact cache write failed: \(error.localizedDescription)")
        }
    }
}

struct RIDTrackMapView: View {
    @ObservedObject var model: RIDTrackViewModel
    @ObservedObject var locationProvider: AppleLocationProvider
    let caltopoConfiguration: AppleCaltopoConfiguration
    @ObservedObject var videoModel: AppleVideoFrameSource
    @ObservedObject var clueStore: AppleClueStore
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    @ObservedObject var orgSettings: AppleOrgConfigSettings
    @ObservedObject var notams: AppleNotamCenter
    @ObservedObject private var airspace = AppleAirspaceCenter.shared
    @ObservedObject private var landRestrictions = AppleLandRestrictionCenter.shared
    let streamURL: URL?
    let ingestAddress: String

    @StateObject private var artifacts = AppleMapArtifactModel()
    @StateObject private var pilotDisplay = ApplePilotDisplayStore()
    @StateObject private var offlineMaps = AppleMapOfflineManager()
    @AppStorage("map.baseLayer") private var storedBaseLayer = OperationalMapBaseLayer.openStreetMap.rawValue
    @AppStorage("map.videoLayout") private var storedLayout = OperationalMapVideoLayout.map.rawValue
    @AppStorage("map.showContours") private var showContours = false
    @AppStorage("map.offlineOnly") private var offlineOnly = false
    @AppStorage("map.followFocusedDrone") private var followFocusedDrone = false
    @State private var viewport = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 39.7392, longitude: -104.9903),
        span: MKCoordinateSpan(latitudeDelta: 0.08, longitudeDelta: 0.08)
    )
    @State private var showMapItems = false
    @State private var showOfflinePreparation = false
    @State private var showMapManagement = false
    @State private var selectedPilotSettings: PilotDisplaySelection?
    @State private var focusedAircraftID: String?
    @State private var operatorAdjustedViewport = false
    @State private var pendingSnapshot: PendingClueSnapshot?
    @State private var selectedClueID: UUID?
    @State private var capturingSnapshot = false
    @State private var clueError: String?
    @State private var showNotams = false
    @State private var showAirspace = false
    @State private var showLandRestrictions = false
    @State private var showMutualAidExport = false

    private var baseLayer: OperationalMapBaseLayer {
        get { OperationalMapBaseLayer(rawValue: storedBaseLayer) ?? .openStreetMap }
        nonmutating set { storedBaseLayer = newValue.rawValue }
    }

    private var layout: OperationalMapVideoLayout {
        get { OperationalMapVideoLayout(rawValue: storedLayout) ?? .map }
        nonmutating set { storedLayout = newValue.rawValue }
    }

    var body: some View {
        GeometryReader { geometry in
            layoutContent(size: geometry.size)
        }
        .safeAreaInset(edge: .top) {
            VStack(spacing: 0) {
                streamTargetBar
                statusBar
            }
        }
        .navigationTitle("Live View")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { mapToolbar }
        .sheet(isPresented: $showMapItems) { MapItemsVisibilityView(model: artifacts) }
        .sheet(isPresented: $showOfflinePreparation) {
            AppleOfflineMapPreparationView(
                manager: offlineMaps,
                viewportBounds: viewport.offlineBounds,
                boundaries: offlineBoundaryOptions,
                baseLayer: baseLayer,
                contoursInitiallyEnabled: showContours
            )
        }
        .sheet(isPresented: $showMapManagement) { AppleMapCacheManagementView(manager: offlineMaps) }
        .sheet(isPresented: $showNotams) { AppleNotamPanel(center: notams, location: locationProvider.lastLocation) }
        .sheet(isPresented: $showAirspace) { AppleAirspacePanel(center: airspace, location: locationProvider.lastLocation) }
        .sheet(isPresented: $showLandRestrictions) {
            AppleLandRestrictionPanel(center: landRestrictions, location: locationProvider.lastLocation)
        }
        .sheet(isPresented: $showMutualAidExport) {
            AppleMutualAidExportView(
                bounds: viewport.offlineBounds,
                layer: baseLayer,
                caltopo: caltopoConfiguration,
                organization: orgSettings
            )
        }
        .sheet(item: $selectedPilotSettings) { selection in
            PilotDisplaySettingsView(
                selection: selection,
                track: model.tracks.first { $0.aircraftID == selection.remoteID },
                altitudeDisplay: model.altitudeDisplayByAircraftID[selection.remoteID],
                onCalibrateAltitude: { model.manualCalibrateAltitude(remoteID: selection.remoteID) },
                store: pilotDisplay
            )
        }
        .sheet(item: $pendingSnapshot) { pending in
            ClueSubmissionView(
                pending: pending,
                tracks: model.tracks,
                altitudeDisplay: model.altitudeDisplayByAircraftID,
                identityStore: identityStore,
                onSubmit: { draft, jpeg, publish in
                    do {
                        try clueStore.save(draft, jpegData: jpeg, publishToCaltopo: publish)
                        pendingSnapshot = nil
                    } catch {
                        clueError = "Clue could not be saved locally: \(error.localizedDescription)"
                    }
                },
                onCancel: { pendingSnapshot = nil }
            )
        }
        .sheet(item: Binding(
            get: { selectedClueID.flatMap { id in clueStore.records.first { $0.id == id } } },
            set: { selectedClueID = $0?.id }
        )) { clue in
            ClueDetailView(
                clue: clue,
                store: clueStore,
                onClose: { selectedClueID = nil }
            )
        }
        .alert("Clue Snapshot", isPresented: Binding(
            get: { clueError != nil },
            set: { if !$0 { clueError = nil } }
        )) { Button("OK") { clueError = nil } } message: { Text(clueError ?? "") }
        .task { artifacts.configure(caltopoConfiguration) }
        .task {
            guard ProcessInfo.processInfo.arguments.contains("--demo-focus-first-drone") else { return }
            try? await Task.sleep(for: .seconds(2))
            focusedAircraftID = model.tracks.first?.aircraftID
            followFocusedDrone = true
            operatorAdjustedViewport = false
        }
        .task {
            let arguments = ProcessInfo.processInfo.arguments
            guard arguments.contains("--demo-clue-sheet") || arguments.contains("--demo-local-clue") else { return }
            try? await Task.sleep(for: .seconds(2))
            guard let track = model.tracks.first, let snapshot = Self.demoClueSnapshot() else { return }
            if arguments.contains("--demo-clue-sheet") {
                pendingSnapshot = PendingClueSnapshot(snapshot: snapshot, defaultAircraftID: track.aircraftID)
            }
            if arguments.contains("--demo-local-clue"),
               !clueStore.records.contains(where: { $0.title == "Simulator clue" }) {
                let observation = track.lastObservation
                let display = model.altitudeDisplayByAircraftID[track.aircraftID]
                let aglMeters = display?.aglFeet.map { $0 * 0.3048 }
                let projection = OperationalClueGeometry.project(
                    droneLatitude: observation.latitude,
                    droneLongitude: observation.longitude,
                    droneAltitudeMeters: observation.altitudeMeters,
                    headingDegrees: observation.headingDegrees,
                    aglMeters: aglMeters,
                    gimbalAngleDegrees: -60
                )
                let identity = identityStore.identity(for: track.aircraftID)
                _ = try? clueStore.save(AppleClueDraft(
                    capturedAt: snapshot.capturedAt,
                    aircraftID: track.aircraftID,
                    designator: identity?.mappedID ?? track.aircraftID,
                    droneLatitude: observation.latitude,
                    droneLongitude: observation.longitude,
                    droneAltitudeMeters: observation.altitudeMeters,
                    clueLatitude: projection.latitude,
                    clueLongitude: projection.longitude,
                    clueAltitudeMeters: projection.altitudeMeters,
                    headingDegrees: observation.headingDegrees,
                    aglMeters: aglMeters,
                    atoMeters: display?.atoFeet.map { $0 * 0.3048 },
                    gimbalAngleDegrees: -60,
                    title: "Simulator clue",
                    description: "Deterministic local clue used for Apple MapPane qualification."
                ), jpegData: snapshot.jpegData, publishToCaltopo: false)
            }
        }
        .onChange(of: caltopoConfiguration) { _, configuration in
            artifacts.configure(configuration)
        }
    }

    @ViewBuilder
    private func layoutContent(size: CGSize) -> some View {
        switch layout {
        case .map:
            mapPane(inset: false)
        case .video:
            videoPane
        case .split:
            if size.width > size.height {
                HStack(spacing: 1) { mapPane(inset: false); videoPane }
            } else {
                VStack(spacing: 1) { mapPane(inset: false); videoPane }
            }
        case .mapPrimary:
            ZStack(alignment: .bottomTrailing) {
                mapPane(inset: false)
                insetFrame { videoPane }
                    .onTapGesture { layout = .videoPrimary }
            }
        case .videoPrimary:
            ZStack(alignment: .bottomTrailing) {
                videoPane
                insetFrame { mapPane(inset: true) }
                    .onTapGesture { layout = .mapPrimary }
            }
        }
    }

    private func mapPane(inset: Bool) -> some View {
        OperationalMKMapView(
            tracks: model.tracks,
            aircraftDisplay: aircraftDisplay,
            altitudeDisplay: model.altitudeDisplayByAircraftID,
            clues: inset ? [] : clueStore.records,
            artifacts: inset ? CaltopoArtifactSnapshot() : artifacts.visibleSnapshot,
            notamState: inset || !notams.showOnMap ? AppleNotamState() : notams.state,
            landRestrictionState: inset || !landRestrictions.showOnMap ? AppleLandRestrictionState() : landRestrictions.state,
            baseLayer: baseLayer,
            showContours: showContours && !inset,
            offlineOnly: offlineOnly,
            tileCacheRevision: offlineMaps.cacheStats.files,
            showsUserLocation: locationProvider.lastLocation != nil,
            viewport: $viewport,
            inset: inset,
            predictiveHeadEnabled: orgSettings.predictiveHeadEnabled,
            focusedAircraftID: focusedAircraftID,
            followFocusedDrone: followFocusedDrone,
            operatorAdjustedViewport: $operatorAdjustedViewport,
            onSelectClue: { selectedClueID = $0 },
            onSelectAircraft: { remoteID in
                focusedAircraftID = remoteID
                guard !inset else { return }
                let identity = identityStore.identity(for: remoteID)
                selectedPilotSettings = PilotDisplaySelection(
                    id: remoteID,
                    remoteID: remoteID,
                    displayName: identity?.mappedID ?? remoteID,
                    pilotCallsign: identity?.pilotCallsign ?? ""
                )
            }
        )
    }

    private var aircraftDisplay: [String: AircraftMapDisplay] {
        _ = pilotDisplay.revision
        return Dictionary(uniqueKeysWithValues: model.tracks.map { track in
            let identity = identityStore.identity(for: track.aircraftID)
            return (
                track.aircraftID,
                AircraftMapDisplay(
                    title: identity?.mappedID ?? track.aircraftID,
                    preference: pilotDisplay.preference(for: identity?.pilotCallsign),
                    showFullFlightTrack: identityStore.isCurrentFlightConfirmed(track.aircraftID)
                )
            )
        })
    }

    private var offlineBoundaryOptions: [AppleOfflineMapPreparationView.BoundaryOption] {
        artifacts.visibleSnapshot.polygons.map {
            .init(id: "polygon:\($0.id)", title: $0.title, coordinates: $0.coordinates)
        } + artifacts.visibleSnapshot.lines.map {
            .init(id: "line:\($0.id)", title: $0.title, coordinates: $0.coordinates)
        }
    }

    private var videoPane: some View {
        ZStack {
            Color.black
            if let player = videoModel.player {
                VideoPlayer(player: player)
            } else {
                ContentUnavailableView("Video disconnected", systemImage: "video.slash")
                    .foregroundStyle(.white)
            }
            VStack {
                HStack {
                    Spacer()
                    Button(action: beginSnapshotCapture) {
                        if capturingSnapshot {
                            ProgressView().tint(.white)
                        } else {
                            Image(systemName: "camera.fill")
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .clipShape(Circle())
                    .disabled(capturingSnapshot || model.tracks.isEmpty || videoModel.frameCount == 0)
                    .accessibilityLabel("Capture clue snapshot")
                }
                Spacer()
            }
            .padding(10)
        }
        .onAppear {
            if videoModel.state == .idle, let streamURL { videoModel.start(url: streamURL) }
        }
    }

    private func beginSnapshotCapture() {
        guard !capturingSnapshot else { return }
        capturingSnapshot = true
        Task {
            defer { capturingSnapshot = false }
            do {
                let snapshot = try await videoModel.captureSnapshot()
                guard !model.tracks.isEmpty else {
                    clueError = "No active aircraft is available to associate with this snapshot."
                    return
                }
                let defaultAircraftID = focusedAircraftID.flatMap { focused in
                    model.tracks.contains { $0.aircraftID == focused } ? focused : nil
                } ?? model.tracks.first!.aircraftID
                pendingSnapshot = PendingClueSnapshot(snapshot: snapshot, defaultAircraftID: defaultAircraftID)
            } catch {
                clueError = error.localizedDescription
            }
        }
    }

    private static func demoClueSnapshot() -> AppleVideoSnapshot? {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 960, height: 540))
        let image = renderer.image { context in
            UIColor(red: 0.05, green: 0.12, blue: 0.20, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 0, width: 960, height: 540))
            UIColor.systemOrange.setFill()
            context.cgContext.fillEllipse(in: CGRect(x: 385, y: 175, width: 190, height: 190))
            let text = "RID2Caltopo Snapshot"
            text.draw(
                at: CGPoint(x: 310, y: 390),
                withAttributes: [
                    .font: UIFont.boldSystemFont(ofSize: 32),
                    .foregroundColor: UIColor.white,
                ]
            )
        }
        guard let jpeg = image.jpegData(compressionQuality: 0.85) else { return nil }
        return AppleVideoSnapshot(jpegData: jpeg, capturedAt: Date(), width: 960, height: 540)
    }

    private func insetFrame<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .frame(width: 280, height: 158)
            .background(.black)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(.white.opacity(0.8), lineWidth: 2))
            .shadow(radius: 5)
            .padding(12)
    }

    private var statusBar: some View {
        HStack(spacing: 12) {
            Label("\(model.tracks.count) active", systemImage: "airplane.circle")
            Text("\(model.acceptedObservationCount) points")
            Spacer()
            Text(artifacts.status)
                .lineLimit(1)
            Text(clueStore.status).lineLimit(1)
            if airspace.enabled {
                Button(airspace.state.chipLabel) { showAirspace = true }
                    .foregroundStyle(airspaceColor)
                    .lineLimit(1)
            }
            if notams.enabled {
                Button(notams.state.chipLabel) { showNotams = true }
                    .foregroundStyle(notamColor)
                    .lineLimit(1)
            }
            if landRestrictions.enabled {
                Button(landRestrictions.state.chipLabel) { showLandRestrictions = true }
                    .foregroundStyle(landRestrictionColor)
                    .lineLimit(1)
            }
            if offlineOnly { Label("Offline", systemImage: "wifi.slash") }
        }
        .font(.caption.monospacedDigit())
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(.regularMaterial)
    }

    private var streamTargetBar: some View {
        HStack(spacing: 12) {
            Text("Stream video to:")
                .font(.caption.bold())
            Text(ingestAddress)
                .font(.subheadline.monospaced())
                .textSelection(.enabled)
            Button {
                UIPasteboard.general.string = ingestAddress
            } label: {
                Label("Copy", systemImage: "doc.on.doc")
            }
            .buttonStyle(.bordered)
            Spacer(minLength: 0)
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(Color.accentColor.opacity(0.12))
    }

    private var notamColor: Color {
        switch notams.state.chipSeverity {
        case .danger: .red
        case .caution: .orange
        case .normal: .green
        case .neutral: .secondary
        }
    }

    private var airspaceColor: Color {
        switch airspace.state.severity {
        case .caution: .orange
        case .normal: .green
        case .neutral: .secondary
        }
    }

    private var landRestrictionColor: Color {
        switch landRestrictions.state.severity {
        case .danger: .red
        case .caution: .orange
        case .normal: .green
        case .neutral: .secondary
        }
    }

    @ToolbarContentBuilder
    private var mapToolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            Menu {
                Picker("Layout", selection: Binding(get: { layout }, set: { layout = $0 })) {
                    ForEach(OperationalMapVideoLayout.allCases, id: \.self) { Text($0.label).tag($0) }
                }
                Divider()
                Picker("Base layer", selection: Binding(get: { baseLayer }, set: { baseLayer = $0 })) {
                    ForEach(OperationalMapBaseLayer.allCases, id: \.self) { Text($0.label).tag($0) }
                }
                Toggle("USGS contours", isOn: $showContours)
                Toggle("Offline tiles only", isOn: $offlineOnly)
                Toggle("Predictive Head", isOn: Binding(
                    get: { orgSettings.predictiveHeadEnabled },
                    set: { orgSettings.setPredictiveHeadEnabled($0) }
                ))
                Toggle("Follow Focused Drone", isOn: Binding(
                    get: { followFocusedDrone },
                    set: { enabled in
                        followFocusedDrone = enabled
                        if enabled { operatorAdjustedViewport = false }
                    }
                ))
                Divider()
                Button("Download Map…", systemImage: "arrow.down.map") { showOfflinePreparation = true }
                Button("Map items", systemImage: "folder.badge.gearshape") { showMapItems = true }
                Button("Map Management…", systemImage: "externaldrive.badge.gearshape") { showMapManagement = true }
                Button("Export MA Package…", systemImage: "shippingbox") { showMutualAidExport = true }
                Button("Refresh CalTopo artifacts") { artifacts.refresh(caltopoConfiguration) }
            } label: {
                Image(systemName: "map.circle")
            }
        }
    }
}

private struct PendingClueSnapshot: Identifiable {
    let id = UUID()
    let snapshot: AppleVideoSnapshot
    let defaultAircraftID: String
}

private struct ClueSubmissionView: View {
    let pending: PendingClueSnapshot
    let tracks: [RidAircraftTrack]
    let altitudeDisplay: [String: OperationalAircraftAltitudeDisplay]
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    let onSubmit: (AppleClueDraft, Data, Bool) -> Void
    let onCancel: () -> Void

    @State private var selectedAircraftID: String
    @State private var title: String
    @State private var description: String
    @State private var gimbalAngle = -90.0

    init(
        pending: PendingClueSnapshot,
        tracks: [RidAircraftTrack],
        altitudeDisplay: [String: OperationalAircraftAltitudeDisplay],
        identityStore: AppleDroneConfirmationStore,
        onSubmit: @escaping (AppleClueDraft, Data, Bool) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.pending = pending
        self.tracks = tracks
        self.altitudeDisplay = altitudeDisplay
        self.identityStore = identityStore
        self.onSubmit = onSubmit
        self.onCancel = onCancel
        _selectedAircraftID = State(initialValue: pending.defaultAircraftID)
        _title = State(initialValue: "Clue \(Self.timestampFormatter.string(from: pending.snapshot.capturedAt))")
        _description = State(initialValue: "Video snapshot captured \(pending.snapshot.capturedAt.formatted(date: .abbreviated, time: .standard)).")
    }

    private var track: RidAircraftTrack? { tracks.first { $0.aircraftID == selectedAircraftID } }
    private var display: OperationalAircraftAltitudeDisplay? { altitudeDisplay[selectedAircraftID] }
    private var designator: String {
        identityStore.identity(for: selectedAircraftID)?.mappedID ?? selectedAircraftID
    }
    private var aglMeters: Double? { display?.aglFeet.map { $0 * 0.3048 } }
    private var atoMeters: Double? { display?.atoFeet.map { $0 * 0.3048 } }
    private var projection: OperationalClueProjection? {
        guard let observation = track?.lastObservation else { return nil }
        return OperationalClueGeometry.project(
            droneLatitude: observation.latitude,
            droneLongitude: observation.longitude,
            droneAltitudeMeters: observation.altitudeMeters,
            headingDegrees: observation.headingDegrees,
            aglMeters: aglMeters,
            gimbalAngleDegrees: gimbalAngle
        )
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if let image = UIImage(data: pending.snapshot.jpegData) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 240)
                            .frame(maxWidth: .infinity)
                    }
                }
                Section("Aircraft") {
                    Picker("Source aircraft", selection: $selectedAircraftID) {
                        ForEach(tracks) { track in
                            Text(identityStore.identity(for: track.aircraftID)?.mappedID ?? track.aircraftID)
                                .tag(track.aircraftID)
                        }
                    }
                    if let observation = track?.lastObservation, let projection {
                        LabeledContent("Drone", value: coordinate(observation.latitude, observation.longitude))
                        LabeledContent("Clue", value: coordinate(projection.latitude, projection.longitude))
                        LabeledContent("Heading", value: measurement(observation.headingDegrees, suffix: "°"))
                        LabeledContent("AGL", value: measurement(display?.aglFeet, suffix: display?.aglStale == true ? "? ft" : " ft"))
                        LabeledContent("ATO", value: measurement(display?.atoFeet, suffix: " ft"))
                    }
                }
                Section("Camera projection") {
                    LabeledContent("Gimbal angle", value: "\(Int(gimbalAngle.rounded()))°")
                    Slider(value: $gimbalAngle, in: -90 ... 0, step: 1)
                    Text("-90° is straight down; 0° is the horizon.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Section("Report") {
                    TextField("Title", text: $title)
                    TextField("Description", text: $description, axis: .vertical)
                        .lineLimit(3 ... 8)
                }
                Section {
                    Button("Add to R2C Map", systemImage: "mappin.and.ellipse") { submit(publish: false) }
                    Button("Save and Submit to CalTopo", systemImage: "icloud.and.arrow.up") { submit(publish: true) }
                        .buttonStyle(.borderedProminent)
                }
            }
            .navigationTitle("Submit Clue")
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
            }
        }
    }

    private func submit(publish: Bool) {
        guard let observation = track?.lastObservation, let projection else { return }
        let summary = "Source \(designator); heading \(measurement(observation.headingDegrees, suffix: "°")); "
            + "AGL \(measurement(display?.aglFeet, suffix: display?.aglStale == true ? "? ft" : " ft")); "
            + "ATO \(measurement(display?.atoFeet, suffix: " ft")); gimbal \(Int(gimbalAngle.rounded()))°."
        let finalDescription = description.trimmingCharacters(in: .whitespacesAndNewlines)
            + "\n\n" + summary
        onSubmit(AppleClueDraft(
            capturedAt: pending.snapshot.capturedAt,
            aircraftID: selectedAircraftID,
            designator: designator,
            droneLatitude: observation.latitude,
            droneLongitude: observation.longitude,
            droneAltitudeMeters: observation.altitudeMeters,
            clueLatitude: projection.latitude,
            clueLongitude: projection.longitude,
            clueAltitudeMeters: projection.altitudeMeters,
            headingDegrees: observation.headingDegrees,
            aglMeters: aglMeters,
            atoMeters: atoMeters,
            gimbalAngleDegrees: gimbalAngle,
            title: title,
            description: finalDescription
        ), pending.snapshot.jpegData, publish)
    }

    private func coordinate(_ latitude: Double, _ longitude: Double) -> String {
        String(format: "%.5f, %.5f", latitude, longitude)
    }

    private func measurement(_ value: Double?, suffix: String) -> String {
        guard let value, value.isFinite else { return "--" }
        return String(format: "%.0f%@", value, suffix)
    }

    private static let timestampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HHmmss"
        return formatter
    }()
}

private struct ClueDetailView: View {
    let clue: OperationalClueRecord
    @ObservedObject var store: AppleClueStore
    let onClose: () -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if let image = UIImage(contentsOfFile: store.imageURL(for: clue).path) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 360)
                            .frame(maxWidth: .infinity)
                    }
                    ShareLink(item: store.imageURL(for: clue)) {
                        Label("Share Snapshot", systemImage: "square.and.arrow.up")
                    }
                }
                Section(clue.title) {
                    LabeledContent("Aircraft", value: clue.designator)
                    LabeledContent("Captured", value: clue.capturedAt.formatted(date: .abbreviated, time: .standard))
                    LabeledContent("Location", value: String(format: "%.5f, %.5f", clue.clueLatitude, clue.clueLongitude))
                    LabeledContent("CalTopo", value: clue.uploadState.rawValue)
                    if let error = clue.lastUploadError { Text(error).foregroundStyle(.red) }
                    Text(clue.clueDescription)
                }
                if clue.uploadState == .failed || clue.uploadState == .pending {
                    Section { Button("Retry CalTopo Upload") { store.retry(clue.id) } }
                }
                Section {
                    Button("Delete Local Clue", role: .destructive) {
                        store.delete(clue.id)
                        onClose()
                    }
                }
            }
            .navigationTitle("Clue")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done", action: onClose) } }
        }
    }
}

private struct MapVisibilityRow: Identifiable {
    enum Content {
        case folder(CaltopoArtifactFolder)
        case item(CaltopoArtifactItem)
    }

    let id: String
    let depth: Int
    let content: Content
}

private struct MapItemsVisibilityView: View {
    @ObservedObject var model: AppleMapArtifactModel
    @Environment(\.dismiss) private var dismiss
    @State private var expandedFolderIDs: Set<String> = []
    @State private var searchText = ""

    var body: some View {
        NavigationStack {
            List(rows) { row in
                switch row.content {
                case let .folder(folder): folderRow(folder, depth: row.depth)
                case let .item(item): itemRow(item, depth: row.depth)
                }
            }
            .overlay {
                if model.snapshot.folders.isEmpty {
                    ContentUnavailableView("No map items", systemImage: "folder", description: Text(model.status))
                }
            }
            .navigationTitle("Map Items")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchText, prompt: "Folders and map items")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .onAppear {
                if expandedFolderIDs.isEmpty {
                    expandedFolderIDs = Set(model.snapshot.folders.filter { $0.parentID == nil }.map(\.id))
                }
            }
        }
    }

    private var rows: [MapVisibilityRow] {
        let folders = model.snapshot.folders
        let folderIDs = Set(folders.map(\.id))
        let roots = folders.filter { $0.parentID == nil || !folderIDs.contains($0.parentID ?? "") }
            .sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        var result: [MapVisibilityRow] = []
        var visited: Set<String> = []
        for folder in roots { append(folder: folder, depth: 0, into: &result, visited: &visited) }
        for folder in folders where !visited.contains(folder.id) {
            append(folder: folder, depth: 0, into: &result, visited: &visited)
        }
        if searchText.isEmpty { return result }
        let query = searchText.localizedLowercase
        return result.filter { row in
            switch row.content {
            case let .folder(folder): folder.title.localizedLowercase.contains(query)
            case let .item(item): item.title.localizedLowercase.contains(query)
            }
        }
    }

    private func append(
        folder: CaltopoArtifactFolder,
        depth: Int,
        into rows: inout [MapVisibilityRow],
        visited: inout Set<String>
    ) {
        guard visited.insert(folder.id).inserted else { return }
        rows.append(.init(id: "folder:\(folder.id)", depth: depth, content: .folder(folder)))
        guard expandedFolderIDs.contains(folder.id) || !searchText.isEmpty else { return }
        guard model.isFolderEffectivelyVisible(folder) || !searchText.isEmpty else { return }
        let children = model.snapshot.folders.filter { $0.parentID == folder.id }
            .sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
        for child in children { append(folder: child, depth: depth + 1, into: &rows, visited: &visited) }
        for item in model.snapshot.items.filter({ $0.folderID == folder.id }) {
            rows.append(.init(id: "item:\(item.id)", depth: depth + 1, content: .item(item)))
        }
    }

    private func folderRow(_ folder: CaltopoArtifactFolder, depth: Int) -> some View {
        let items = model.snapshot.items.filter { $0.folderID == folder.id }
        let hasChildren = !items.isEmpty || model.snapshot.folders.contains { $0.parentID == folder.id }
        return HStack(spacing: 10) {
            Button {
                if expandedFolderIDs.contains(folder.id) { expandedFolderIDs.remove(folder.id) }
                else { expandedFolderIDs.insert(folder.id) }
            } label: {
                Image(systemName: hasChildren ? (expandedFolderIDs.contains(folder.id) ? "chevron.down" : "chevron.right") : "minus")
                    .frame(width: 14)
            }
            .buttonStyle(.plain)
            Button { model.toggleFolder(folder) } label: {
                Label(folder.title, systemImage: model.isFolderEffectivelyVisible(folder) ? "checkmark.square.fill" : "square")
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            if !items.isEmpty {
                Button {
                    let allVisible = items.allSatisfy(model.isItemEffectivelyVisible)
                    model.setItems(items, visible: !allVisible)
                } label: {
                    Image(systemName: items.allSatisfy(model.isItemEffectivelyVisible) ? "eye" : "eye.slash")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Toggle all items in \(folder.title)")
            }
        }
        .padding(.leading, CGFloat(depth) * 18)
        .fontWeight(.semibold)
    }

    private func itemRow(_ item: CaltopoArtifactItem, depth: Int) -> some View {
        Button { model.toggleItem(item) } label: {
            Label(item.title, systemImage: model.isItemEffectivelyVisible(item) ? "checkmark.square.fill" : "square")
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.plain)
        .padding(.leading, CGFloat(depth) * 18 + 24)
    }
}

private struct PilotDisplaySelection: Identifiable {
    let id: String
    let remoteID: String
    let displayName: String
    let pilotCallsign: String
}

private struct AircraftMapDisplay: Equatable {
    let title: String
    let preference: PilotDisplayPreference
    let showFullFlightTrack: Bool
}

private struct PilotDisplaySettingsView: View {
    let selection: PilotDisplaySelection
    let track: RidAircraftTrack?
    let altitudeDisplay: OperationalAircraftAltitudeDisplay?
    let onCalibrateAltitude: () -> Void
    @ObservedObject var store: ApplePilotDisplayStore
    @Environment(\.dismiss) private var dismiss

    private let palette = [
        defaultActiveTrackColor, defaultArchiveTrackColor, "#E53935", "#FB8C00",
        "#FDD835", "#43A047", "#00ACC1", "#3949AB", "#8E24AA",
        "#6D4C41", "#FFFFFF", "#212121",
    ]

    private var preference: PilotDisplayPreference {
        store.preference(for: selection.pilotCallsign)
    }

    var body: some View {
        NavigationStack {
            Form {
                if let observation = track?.lastObservation {
                    Section(selection.displayName) {
                        LabeledContent(
                            "Location",
                            value: String(format: "%.6f, %.6f", observation.latitude, observation.longitude)
                        )
                        LabeledContent("Altitude MSL", value: measurement(observation.altitudeMeters, scale: 3.28084, unit: "ft"))
                        LabeledContent("ATO", value: feet(altitudeDisplay?.atoFeet))
                        LabeledContent(
                            "AGL",
                            value: feet(altitudeDisplay?.aglFeet, stale: altitudeDisplay?.aglStale == true)
                        )
                        LabeledContent("Range from takeoff", value: feet(altitudeDisplay?.rangeFeet))
                        LabeledContent("Heading", value: measurement(observation.headingDegrees, scale: 1, unit: "°"))
                        LabeledContent("Speed", value: measurement(observation.speedMetersPerSecond, scale: 1.94384, unit: "kt"))
                        Button("Calibrate ATO + AGL at 50 ft", action: onCalibrateAltitude)
                            .disabled(observation.altitudeMeters == nil)
                    }
                }
                if !selection.pilotCallsign.isEmpty {
                    Section("Pilot Display: \(selection.pilotCallsign)") {
                        colorRow("Active", selected: preference.activeTrackColor) { color in
                            var updated = preference
                            updated.activeTrackColor = color
                            store.save(updated, for: selection.pilotCallsign)
                        }
                        colorRow("Archive", selected: preference.archiveTrackColor) { color in
                            var updated = preference
                            updated.archiveTrackColor = color
                            store.save(updated, for: selection.pilotCallsign)
                        }
                        Toggle("Bearing", isOn: Binding(
                            get: { preference.bearingEnabled },
                            set: { enabled in
                                var updated = preference
                                updated.bearingEnabled = enabled
                                store.save(updated, for: selection.pilotCallsign)
                            }
                        ))
                        Button("Reset", role: .destructive) { store.reset(selection.pilotCallsign) }
                    }
                }
            }
            .navigationTitle("Pilot Display")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }

    private func measurement(_ value: Double?, scale: Double, unit: String) -> String {
        guard let value, value.isFinite else { return "--" }
        return String(format: "%.0f %@", value * scale, unit)
    }

    private func feet(_ value: Double?, stale: Bool = false) -> String {
        guard let value, value.isFinite else { return "--" }
        return String(format: "%.0f%@ ft", value, stale ? "?" : "")
    }

    private func colorRow(
        _ title: String,
        selected: String,
        onSelect: @escaping (String) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Circle().fill(Color(uiColor: UIColor(hex: selected) ?? .systemBlue)).frame(width: 28, height: 28)
                Text(title)
                Spacer()
                Text(selected).foregroundStyle(.secondary).monospaced()
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 6), spacing: 10) {
                ForEach(palette, id: \.self) { color in
                    Button { onSelect(color) } label: {
                        Circle()
                            .fill(Color(uiColor: UIColor(hex: color) ?? .systemBlue))
                            .frame(width: 36, height: 36)
                            .overlay(Circle().stroke(.primary, lineWidth: selected == color ? 3 : 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(title) \(color)")
                }
            }
        }
    }
}

private struct OperationalMKMapView: UIViewRepresentable {
    let tracks: [RidAircraftTrack]
    let aircraftDisplay: [String: AircraftMapDisplay]
    let altitudeDisplay: [String: OperationalAircraftAltitudeDisplay]
    let clues: [OperationalClueRecord]
    let artifacts: CaltopoArtifactSnapshot
    let notamState: AppleNotamState
    let landRestrictionState: AppleLandRestrictionState
    let baseLayer: OperationalMapBaseLayer
    let showContours: Bool
    let offlineOnly: Bool
    let tileCacheRevision: Int
    let showsUserLocation: Bool
    @Binding var viewport: MKCoordinateRegion
    let inset: Bool
    let predictiveHeadEnabled: Bool
    let focusedAircraftID: String?
    let followFocusedDrone: Bool
    @Binding var operatorAdjustedViewport: Bool
    let onSelectClue: (UUID) -> Void
    let onSelectAircraft: (String) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(
            viewport: $viewport,
            operatorAdjustedViewport: $operatorAdjustedViewport,
            onSelectClue: onSelectClue,
            onSelectAircraft: onSelectAircraft
        )
    }

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.showsCompass = !inset
        map.showsScale = !inset
        map.pointOfInterestFilter = .excludingAll
        map.setRegion(viewport, animated: false)
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        context.coordinator.onSelectAircraft = onSelectAircraft
        context.coordinator.onSelectClue = onSelectClue
        map.showsUserLocation = showsUserLocation
        let shouldFollowOperator = showsUserLocation && !inset && !followFocusedDrone && !operatorAdjustedViewport
        if shouldFollowOperator {
            if map.userTrackingMode != .follow { map.setUserTrackingMode(.follow, animated: false) }
        } else if map.userTrackingMode != .none {
            map.setUserTrackingMode(.none, animated: false)
        }
        context.coordinator.updateTiles(
            on: map,
            baseLayer: baseLayer,
            contours: showContours,
            offlineOnly: offlineOnly,
            revision: tileCacheRevision
        )
        context.coordinator.updateOperationalOverlays(
            on: map,
            tracks: tracks,
            aircraftDisplay: aircraftDisplay,
            altitudeDisplay: altitudeDisplay,
            clues: clues,
            artifacts: artifacts,
            notamState: notamState,
            landRestrictionState: landRestrictionState,
            inset: inset,
            predictiveHeadEnabled: predictiveHeadEnabled,
            focusedAircraftID: focusedAircraftID,
            followFocusedDrone: followFocusedDrone
        )
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        @Binding private var viewport: MKCoordinateRegion
        @Binding private var operatorAdjustedViewport: Bool
        private var tileFingerprint = ""
        private var updating = false
        private var currentInset = false
        var onSelectClue: (UUID) -> Void
        var onSelectAircraft: (String) -> Void

        init(
            viewport: Binding<MKCoordinateRegion>,
            operatorAdjustedViewport: Binding<Bool>,
            onSelectClue: @escaping (UUID) -> Void,
            onSelectAircraft: @escaping (String) -> Void
        ) {
            _viewport = viewport
            _operatorAdjustedViewport = operatorAdjustedViewport
            self.onSelectClue = onSelectClue
            self.onSelectAircraft = onSelectAircraft
        }

        func updateTiles(
            on map: MKMapView,
            baseLayer: OperationalMapBaseLayer,
            contours: Bool,
            offlineOnly: Bool,
            revision: Int
        ) {
            let fingerprint = "\(baseLayer.rawValue)|\(contours)|\(offlineOnly)|\(revision)"
            guard fingerprint != tileFingerprint else { return }
            tileFingerprint = fingerprint
            map.removeOverlays(map.overlays.filter { $0 is CachedMapTileOverlay })
            let base = CachedMapTileOverlay(baseLayer: baseLayer, offlineOnly: offlineOnly)
            base.canReplaceMapContent = true
            map.insertOverlay(base, at: 0, level: .aboveRoads)
            if contours {
                map.addOverlay(CachedMapTileOverlay(contoursOfflineOnly: offlineOnly), level: .aboveLabels)
            }
        }

        func updateOperationalOverlays(
            on map: MKMapView,
            tracks: [RidAircraftTrack],
            aircraftDisplay: [String: AircraftMapDisplay],
            altitudeDisplay: [String: OperationalAircraftAltitudeDisplay],
            clues: [OperationalClueRecord],
            artifacts: CaltopoArtifactSnapshot,
            notamState: AppleNotamState,
            landRestrictionState: AppleLandRestrictionState,
            inset: Bool,
            predictiveHeadEnabled: Bool,
            focusedAircraftID: String?,
            followFocusedDrone: Bool
        ) {
            updating = true
            currentInset = inset
            defer { updating = false }
            map.removeOverlays(map.overlays.filter { !($0 is CachedMapTileOverlay) })
            map.removeAnnotations(map.annotations.filter { !($0 is MKUserLocation) })
            let now = Date()
            let renderCoordinates = Dictionary(uniqueKeysWithValues: tracks.compactMap { track -> (String, CLLocationCoordinate2D)? in
                guard let latest = track.points.last else { return nil }
                let actual = MapCoordinate(latitude: latest.latitude, longitude: latest.longitude)
                let predicted = predictiveHeadEnabled && track.points.count >= 2
                    ? OperationalAircraftDisplay.predictedCoordinate(
                        previous: MapCoordinate(
                            latitude: track.points[track.points.count - 2].latitude,
                            longitude: track.points[track.points.count - 2].longitude
                        ),
                        previousTime: track.points[track.points.count - 2].receivedAt,
                        current: actual,
                        currentTime: latest.receivedAt,
                        now: now
                    )
                    : nil
                return (track.aircraftID, (predicted ?? actual).clCoordinate)
            })
            let statusLabels = Dictionary(uniqueKeysWithValues: tracks.map { track in
                let altitude = altitudeDisplay[track.aircraftID]
                return (
                    track.aircraftID,
                    OperationalAircraftDisplay.statusLabel(
                        atoFeet: altitude?.atoFeet,
                        aglFeet: altitude?.aglFeet,
                        aglStale: altitude?.aglStale == true,
                        rangeFeet: altitude?.rangeFeet,
                        headingDegrees: track.points.last?.headingDegrees
                    )
                )
            })
            let labelLayouts: [String: MapAircraftLabelLayout]
            if inset {
                labelLayouts = [:]
            } else {
                let nameFont = UIFont.boldSystemFont(ofSize: 16)
                let statusFont = UIFont.boldSystemFont(ofSize: 13)
                let inputs = tracks.compactMap { track -> MapAircraftLabelInput? in
                    guard let coordinate = renderCoordinates[track.aircraftID] else { return nil }
                    let anchor = map.convert(coordinate, toPointTo: map)
                    let title = aircraftDisplay[track.aircraftID]?.title ?? track.aircraftID
                    let status = statusLabels[track.aircraftID] ?? ""
                    let nameSize = (title as NSString).size(withAttributes: [.font: nameFont])
                    let statusSize = (status as NSString).size(withAttributes: [.font: statusFont])
                    return MapAircraftLabelInput(
                        id: track.aircraftID,
                        anchor: MapScreenPoint(x: anchor.x, y: anchor.y),
                        nameWidth: nameSize.width + 12,
                        nameHeight: nameSize.height + 6,
                        statusWidth: statusSize.width + 10,
                        statusHeight: statusSize.height + 5
                    )
                }
                labelLayouts = Dictionary(uniqueKeysWithValues: OperationalAircraftDisplay.layoutLabels(
                    inputs,
                    viewportWidth: map.bounds.width,
                    viewportHeight: map.bounds.height
                ).map { ($0.id, $0) })
            }
            for (index, track) in tracks.enumerated() {
                let display = aircraftDisplay[track.aircraftID]
                    ?? AircraftMapDisplay(title: track.aircraftID, preference: PilotDisplayPreference(), showFullFlightTrack: false)
                let activeColor = UIColor(hex: display.preference.activeTrackColor) ?? .systemBlue
                let archiveColor = UIColor(hex: display.preference.archiveTrackColor) ?? .systemPink
                let iconColor: UIColor = {
                    guard let aglFeet = altitudeDisplay[track.aircraftID]?.aglFeet else { return activeColor }
                    if aglFeet >= 200 { return UIColor(hex: "#D32F2F") ?? .systemRed }
                    if aglFeet >= 180 { return UIColor(hex: "#FBC02D") ?? .systemYellow }
                    return activeColor
                }()
                let coordinates = track.points.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
                if display.showFullFlightTrack, coordinates.count > 1 {
                    map.addOverlay(
                        StyledPolyline(coordinates: coordinates, count: coordinates.count, color: archiveColor, width: inset ? 1 : 2),
                        level: .aboveLabels
                    )
                }
                let cutoff = Date().addingTimeInterval(-30)
                let firstRecentIndex = track.points.firstIndex { $0.receivedAt >= cutoff }
                let activeStartIndex = max((firstRecentIndex ?? max(track.points.count - 1, 0)) - 1, 0)
                let activeCoordinates = Array(coordinates.dropFirst(activeStartIndex))
                if activeCoordinates.count > 1 {
                    map.addOverlay(
                        StyledPolyline(
                            coordinates: activeCoordinates,
                            count: activeCoordinates.count,
                            color: activeColor,
                            width: inset ? 2 : 4
                        ),
                        level: .aboveLabels
                    )
                }
                if let latest = track.points.last,
                   let aircraftCoordinate = renderCoordinates[track.aircraftID] {
                    if display.preference.bearingEnabled,
                       let end = bearingEndpoint(on: map, from: aircraftCoordinate, headingDegrees: latest.headingDegrees) {
                        map.addOverlay(
                            StyledPolyline(
                                coordinates: [aircraftCoordinate, end],
                                count: 2,
                                color: activeColor,
                                width: inset ? 1 : 2
                            ),
                            level: .aboveLabels
                        )
                    }
                    map.addAnnotation(AircraftAnnotation(
                        remoteID: track.aircraftID,
                        coordinate: aircraftCoordinate,
                        title: display.title,
                        heading: latest.headingDegrees ?? 0,
                        color: iconColor,
                        inset: inset,
                        labelSide: index.isMultiple(of: 2) ? -1 : 1,
                        statusText: statusLabels[track.aircraftID] ?? "",
                        labelLayout: labelLayouts[track.aircraftID],
                        anchorScreen: {
                            let point = map.convert(aircraftCoordinate, toPointTo: map)
                            return MapScreenPoint(x: point.x, y: point.y)
                        }(),
                        focused: focusedAircraftID == track.aircraftID
                    ))
                }
                if let latitude = track.lastObservation.operatorLatitude,
                   let longitude = track.lastObservation.operatorLongitude,
                   latitude != 0, longitude != 0 {
                    map.addAnnotation(ArtifactAnnotation(
                        coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
                        title: "Operator \(display.title)",
                        symbol: "person.wave.2",
                        color: activeColor
                    ))
                }
            }
            if followFocusedDrone,
               let focusedAircraftID,
               let coordinate = renderCoordinates[focusedAircraftID],
               inset || !operatorAdjustedViewport {
                map.setCenter(coordinate, animated: false)
            }
            for artifact in artifacts.points {
                map.addAnnotation(ArtifactAnnotation(
                    coordinate: artifact.coordinate.clCoordinate,
                    title: artifact.title,
                    symbol: artifact.symbol,
                    color: UIColor(hex: artifact.colorHex) ?? ArtifactAnnotation.defaultColor(for: artifact.symbol)
                ))
            }
            for artifact in artifacts.lines {
                let coordinates = artifact.coordinates.map(\.clCoordinate)
                map.addOverlay(
                    StyledPolyline(
                        coordinates: coordinates,
                        count: coordinates.count,
                        color: UIColor(hex: artifact.colorHex) ?? .systemOrange,
                        width: artifact.width
                    ),
                    level: .aboveLabels
                )
            }
            for artifact in artifacts.polygons {
                let coordinates = artifact.coordinates.map(\.clCoordinate)
                map.addOverlay(
                    StyledPolygon(
                        coordinates: coordinates,
                        count: coordinates.count,
                        stroke: UIColor(hex: artifact.strokeHex) ?? .systemOrange,
                        fill: UIColor(hex: artifact.fillHex) ?? .systemOrange.withAlphaComponent(0.2),
                        width: artifact.width
                    ),
                    level: .aboveLabels
                )
            }
            if notamState.enabled, notamState.visible {
                for notice in notamState.notices {
                    addNotam(notice, to: map)
                }
            }
            if landRestrictionState.enabled, landRestrictionState.visible {
                for area in landRestrictionState.areas {
                    addLandRestriction(area, to: map)
                }
            }
            for clue in clues {
                map.addAnnotation(ClueAnnotation(clue: clue))
            }
        }

        private func addNotam(_ notice: OperationalNotam, to map: MKMapView) {
            let style: (UIColor, UIColor) = switch notice.severity {
            case .danger: (.systemRed, UIColor.systemRed.withAlphaComponent(0.2))
            case .caution: (.systemOrange, UIColor.systemOrange.withAlphaComponent(0.2))
            case .normal: (.systemGreen, UIColor.systemGreen.withAlphaComponent(0.2))
            case .neutral: (.systemGray, UIColor.systemGray.withAlphaComponent(0.15))
            }
            func add(_ geometry: OperationalNotamGeometry) {
                switch geometry {
                case let .point(coordinate):
                    map.addAnnotation(NotamAnnotation(notice: notice, coordinate: coordinate.clCoordinate))
                case let .line(values):
                    let coordinates = values.map(\.clCoordinate)
                    if coordinates.count >= 2 {
                        map.addOverlay(StyledPolyline(coordinates: coordinates, count: coordinates.count, color: style.0, width: 5), level: .aboveLabels)
                    }
                case let .polygon(rings):
                    guard let outer = rings.first else { return }
                    let coordinates = outer.map(\.clCoordinate)
                    if coordinates.count >= 3 {
                        map.addOverlay(StyledPolygon(coordinates: coordinates, count: coordinates.count, stroke: style.0, fill: style.1, width: 4), level: .aboveLabels)
                    }
                case let .collection(values): values.forEach(add)
                }
            }
            notice.geometries.forEach(add)
        }

        private func addLandRestriction(_ area: OperationalLandArea, to map: MKMapView) {
            let stroke: UIColor = switch area.agency {
            case .nationalParkService: .systemBrown
            case .fishAndWildlifeService: .systemPurple
            case .forestService: .systemGreen
            case .coloradoParksAndWildlife: .systemTeal
            }
            let fill = stroke.withAlphaComponent(area.containsOperator ? 0.22 : 0.12)
            for polygon in area.polygons {
                guard let outer = polygon.first else { continue }
                let coordinates = outer.map(\.clCoordinate)
                guard coordinates.count >= 3 else { continue }
                map.addOverlay(
                    StyledPolygon(
                        coordinates: coordinates,
                        count: coordinates.count,
                        stroke: stroke,
                        fill: fill,
                        width: area.containsOperator ? 4 : 2
                    ),
                    level: .aboveLabels
                )
            }
        }

        private func bearingEndpoint(
            on map: MKMapView,
            from coordinate: CLLocationCoordinate2D,
            headingDegrees: Double?
        ) -> CLLocationCoordinate2D? {
            let start = map.convert(coordinate, toPointTo: map)
            guard let end = OperationalMapGeometry.bearingLineToViewportEdge(
                start: MapScreenPoint(x: start.x, y: start.y),
                headingDegrees: headingDegrees,
                viewportWidth: map.bounds.width,
                viewportHeight: map.bounds.height
            ) else { return nil }
            let coordinate = map.convert(CGPoint(x: end.x, y: end.y), toCoordinateFrom: map)
            guard CLLocationCoordinate2DIsValid(coordinate) else { return nil }
            return coordinate
        }

        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            guard !updating else { return }
            viewport = mapView.region
        }

        func mapView(_ mapView: MKMapView, regionWillChangeAnimated animated: Bool) {
            guard !currentInset,
                  mapView.gestureRecognizers?.contains(where: {
                      $0.state == .began || $0.state == .changed
                  }) == true
            else { return }
            operatorAdjustedViewport = true
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let tiles = overlay as? MKTileOverlay { return MKTileOverlayRenderer(tileOverlay: tiles) }
            if let line = overlay as? StyledPolyline {
                let renderer = MKPolylineRenderer(polyline: line)
                renderer.strokeColor = line.color
                renderer.lineWidth = line.width
                return renderer
            }
            if let polygon = overlay as? StyledPolygon {
                let renderer = MKPolygonRenderer(polygon: polygon)
                renderer.strokeColor = polygon.stroke
                renderer.fillColor = polygon.fill
                renderer.lineWidth = polygon.width
                return renderer
            }
            return MKOverlayRenderer(overlay: overlay)
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            if annotation is MKUserLocation { return nil }
            if let aircraft = annotation as? AircraftAnnotation {
                let identifier = "aircraft"
                let view = (mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? AircraftAnnotationView)
                    ?? AircraftAnnotationView(annotation: aircraft, reuseIdentifier: identifier)
                view.configure(aircraft)
                return view
            }
            if let clue = annotation as? ClueAnnotation {
                let identifier = "local-clue"
                let view = (mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? MKMarkerAnnotationView)
                    ?? MKMarkerAnnotationView(annotation: clue, reuseIdentifier: identifier)
                view.annotation = clue
                view.markerTintColor = clue.published ? UIColor.systemGreen : UIColor.systemPink
                view.glyphImage = UIImage(systemName: "camera.fill")
                view.canShowCallout = true
                return view
            }
            if let notam = annotation as? NotamAnnotation {
                let identifier = "notam"
                let view = (mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? MKMarkerAnnotationView)
                    ?? MKMarkerAnnotationView(annotation: notam, reuseIdentifier: identifier)
                view.annotation = notam
                view.markerTintColor = notam.color
                view.glyphImage = UIImage(systemName: "exclamationmark.triangle.fill")
                view.canShowCallout = true
                return view
            }
            guard let artifact = annotation as? ArtifactAnnotation else { return nil }
            let identifier = "artifact"
            let view = (mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? ArtifactAnnotationView)
                ?? ArtifactAnnotationView(annotation: artifact, reuseIdentifier: identifier)
            view.configure(artifact)
            return view
        }

        func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
            if let clue = view.annotation as? ClueAnnotation {
                onSelectClue(clue.clueID)
                return
            }
            guard let aircraft = view.annotation as? AircraftAnnotation else { return }
            onSelectAircraft(aircraft.remoteID)
        }
    }
}

private final class AircraftAnnotationView: MKAnnotationView {
    private let iconView = UIImageView()
    private let nameLabel = UILabel()
    private let statusLabel = UILabel()
    private let leaderLayer = CAShapeLayer()

    override init(annotation: (any MKAnnotation)?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 800, height: 360)
        centerOffset = .zero
        canShowCallout = false
        leaderLayer.strokeColor = UIColor.white.cgColor
        leaderLayer.lineWidth = 2
        leaderLayer.lineCap = .round
        leaderLayer.shadowColor = UIColor.black.cgColor
        leaderLayer.shadowOpacity = 0.8
        leaderLayer.shadowRadius = 1
        layer.addSublayer(leaderLayer)
        iconView.contentMode = .center
        iconView.clipsToBounds = true
        addSubview(iconView)
        configureLabel(nameLabel, fontSize: 16, cornerRadius: 6)
        configureLabel(statusLabel, fontSize: 13, cornerRadius: 5)
        addSubview(nameLabel)
        addSubview(statusLabel)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    private func configureLabel(_ label: UILabel, fontSize: CGFloat, cornerRadius: CGFloat) {
        label.font = .boldSystemFont(ofSize: fontSize)
        label.textAlignment = .center
        label.textColor = .white
        label.backgroundColor = UIColor.black.withAlphaComponent(0.58)
        label.layer.cornerRadius = cornerRadius
        label.clipsToBounds = true
    }

    func configure(_ aircraft: AircraftAnnotation) {
        annotation = aircraft
        let iconSize: CGFloat = aircraft.inset ? 22 : 30
        let iconX = (bounds.width - iconSize) / 2
        let iconY = (bounds.height - iconSize) / 2
        iconView.frame = CGRect(x: iconX, y: iconY, width: iconSize, height: iconSize)
        iconView.backgroundColor = aircraft.color
        iconView.layer.cornerRadius = iconSize / 2
        iconView.layer.borderColor = UIColor.systemYellow.cgColor
        iconView.layer.borderWidth = aircraft.focused ? 3 : 0
        iconView.tintColor = .white
        let pointSize = aircraft.inset ? 12.0 : 17.0
        iconView.image = UIImage(
            systemName: "airplane",
            withConfiguration: UIImage.SymbolConfiguration(pointSize: pointSize, weight: .bold)
        )
        iconView.transform = CGAffineTransform(rotationAngle: CGFloat((aircraft.heading - 90) * .pi / 180))
        nameLabel.isHidden = aircraft.inset
        statusLabel.isHidden = aircraft.inset
        leaderLayer.isHidden = aircraft.inset
        nameLabel.text = aircraft.title
        statusLabel.text = aircraft.statusText
        if let layout = aircraft.labelLayout {
            nameLabel.frame = localRect(layout.nameBounds, anchor: aircraft.anchorScreen)
            statusLabel.frame = localRect(layout.statusBounds, anchor: aircraft.anchorScreen)
        } else {
            let labelX = aircraft.labelSide < 0 ? bounds.midX - 125 : bounds.midX + 25
            nameLabel.frame = CGRect(x: labelX, y: bounds.midY + 28, width: 100, height: 25)
            statusLabel.frame = CGRect(x: labelX - 55, y: bounds.midY + 56, width: 210, height: 23)
        }
        let path = UIBezierPath()
        path.move(to: CGPoint(x: bounds.midX, y: bounds.midY))
        if let end = aircraft.labelLayout?.leaderEnd {
            path.addLine(to: CGPoint(
                x: bounds.midX + end.x - aircraft.anchorScreen.x,
                y: bounds.midY + end.y - aircraft.anchorScreen.y
            ))
            leaderLayer.isHidden = aircraft.inset
        } else {
            leaderLayer.isHidden = true
        }
        leaderLayer.path = path.cgPath
    }

    private func localRect(_ rect: MapLabelRect, anchor: MapScreenPoint) -> CGRect {
        CGRect(
            x: bounds.midX + rect.left - anchor.x,
            y: bounds.midY + rect.top - anchor.y,
            width: rect.width,
            height: rect.height
        )
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        iconView.frame.insetBy(dx: -10, dy: -10).contains(point)
            || (!nameLabel.isHidden && nameLabel.frame.contains(point))
            || (!statusLabel.isHidden && statusLabel.frame.contains(point))
    }
}

private final class CachedMapTileOverlay: MKTileOverlay {
    private let baseLayer: OperationalMapBaseLayer?
    private let contours: Bool
    private let offlineOnly: Bool
    private let cacheRoot: URL

    init(baseLayer: OperationalMapBaseLayer, offlineOnly: Bool) {
        self.baseLayer = baseLayer
        contours = false
        self.offlineOnly = offlineOnly
        cacheRoot = AppleMapCachePaths.root.appendingPathComponent(baseLayer.cacheKey, isDirectory: true)
        super.init(urlTemplate: nil)
        minimumZ = 0
        maximumZ = 19
        tileSize = CGSize(width: 256, height: 256)
    }

    init(contoursOfflineOnly offlineOnly: Bool) {
        baseLayer = nil
        contours = true
        self.offlineOnly = offlineOnly
        cacheRoot = AppleMapCachePaths.root.appendingPathComponent("usgsContours", isDirectory: true)
        super.init(urlTemplate: nil)
        minimumZ = 0
        maximumZ = 22
        tileSize = CGSize(width: 256, height: 256)
    }

    override func url(forTilePath path: MKTileOverlayPath) -> URL {
        if let baseLayer, let url = baseLayer.tileURL(zoom: path.z, x: path.x, y: path.y) { return url }
        return AppleMapTileRequest.contourURL(zoom: path.z, x: path.x, y: path.y)
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        let completion = TileResultTransfer(result: result)
        let ext = contours ? "png" : (baseLayer?.fileExtension ?? "tile")
        let destination = cacheRoot
            .appendingPathComponent(String(path.z), isDirectory: true)
            .appendingPathComponent(String(path.x), isDirectory: true)
            .appendingPathComponent("\(path.y).\(ext)")
        if let data = try? Data(contentsOf: destination) {
            if AppleMapOfflineManager.dataIsUsableTile(data), !AppleBadTilePolicy.isBlocked(data) {
                completion.result(data, nil)
                return
            }
            if UserDefaults.standard.object(forKey: "map.autoRemoveBadTiles") as? Bool ?? true {
                try? FileManager.default.removeItem(at: destination)
            }
        }
        guard !offlineOnly else {
            completion.result(nil, CocoaError(.fileNoSuchFile))
            return
        }
        var request = URLRequest(url: url(forTilePath: path))
        request.setValue("RID2Caltopo/Apple (contact: kjtsar@kjt.us)", forHTTPHeaderField: "User-Agent")
        URLSession.shared.dataTask(with: request) { data, response, error in
            guard let data, (response as? HTTPURLResponse)?.statusCode == 200,
                  AppleMapOfflineManager.dataIsUsableTile(data), !AppleBadTilePolicy.isBlocked(data)
            else {
                if let data { AppleBadTilePolicy.record(data) }
                completion.result(nil, error ?? CocoaError(.fileReadUnknown))
                return
            }
            do {
                try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
                try data.write(to: destination, options: .atomic)
            } catch {
                AppleLog.warning("MapTiles", "Tile cache write failed: \(error.localizedDescription)")
            }
            completion.result(data, nil)
        }.resume()
    }

}

private struct TileResultTransfer: @unchecked Sendable {
    let result: (Data?, Error?) -> Void
}

private final class StyledPolyline: MKPolyline {
    var color: UIColor = .systemBlue
    var width: CGFloat = 3

    convenience init(coordinates: [CLLocationCoordinate2D], count: Int, color: UIColor, width: Double) {
        self.init(coordinates: coordinates, count: count)
        self.color = color
        self.width = width
    }
}

private final class StyledPolygon: MKPolygon {
    var stroke: UIColor = .systemOrange
    var fill: UIColor = .systemOrange.withAlphaComponent(0.2)
    var width: CGFloat = 3

    convenience init(coordinates: [CLLocationCoordinate2D], count: Int, stroke: UIColor, fill: UIColor, width: Double) {
        self.init(coordinates: coordinates, count: count)
        self.stroke = stroke
        self.fill = fill
        self.width = width
    }
}

private final class AircraftAnnotation: NSObject, MKAnnotation {
    let remoteID: String
    dynamic let coordinate: CLLocationCoordinate2D
    let title: String?
    let heading: Double
    let color: UIColor
    let inset: Bool
    let labelSide: Int
    let statusText: String
    let labelLayout: MapAircraftLabelLayout?
    let anchorScreen: MapScreenPoint
    let focused: Bool

    init(
        remoteID: String,
        coordinate: CLLocationCoordinate2D,
        title: String,
        heading: Double,
        color: UIColor,
        inset: Bool,
        labelSide: Int,
        statusText: String,
        labelLayout: MapAircraftLabelLayout?,
        anchorScreen: MapScreenPoint,
        focused: Bool
    ) {
        self.remoteID = remoteID
        self.coordinate = coordinate
        self.title = title
        self.heading = heading
        self.color = color
        self.inset = inset
        self.labelSide = labelSide
        self.statusText = statusText
        self.labelLayout = labelLayout
        self.anchorScreen = anchorScreen
        self.focused = focused
        super.init()
    }
}

private final class ClueAnnotation: NSObject, MKAnnotation {
    let clueID: UUID
    dynamic let coordinate: CLLocationCoordinate2D
    let title: String?
    let subtitle: String?
    let published: Bool

    init(clue: OperationalClueRecord) {
        clueID = clue.id
        coordinate = CLLocationCoordinate2D(latitude: clue.clueLatitude, longitude: clue.clueLongitude)
        title = clue.title
        subtitle = clue.uploadState == .published ? "Published to CalTopo" : "Local R2C clue"
        published = clue.uploadState == .published
        super.init()
    }
}

private final class NotamAnnotation: NSObject, MKAnnotation {
    dynamic let coordinate: CLLocationCoordinate2D
    let title: String?
    let subtitle: String?
    let color: UIColor

    init(notice: OperationalNotam, coordinate: CLLocationCoordinate2D) {
        self.coordinate = coordinate
        title = notice.title
        subtitle = notice.summary
        color = switch notice.severity {
        case .danger: .systemRed
        case .caution: .systemOrange
        case .normal: .systemGreen
        case .neutral: .systemGray
        }
        super.init()
    }
}

private extension OperationalNotamCoordinate {
    var clCoordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

private extension OperationalLandCoordinate {
    var clCoordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

private final class ArtifactAnnotation: NSObject, MKAnnotation {
    dynamic let coordinate: CLLocationCoordinate2D
    let title: String?
    let systemSymbol: String
    let caltopoSymbol: String
    let color: UIColor

    init(coordinate: CLLocationCoordinate2D, title: String, symbol: String, color: UIColor) {
        self.coordinate = coordinate
        self.title = title
        caltopoSymbol = symbol
        systemSymbol = Self.systemSymbol(for: symbol)
        self.color = color
        super.init()
    }

    private static func systemSymbol(for caltopoSymbol: String) -> String {
        switch caltopoSymbol.lowercased() {
        case "aperture", "camera": "camera.fill"
        case "radiotower": "antenna.radiowaves.left.and.right"
        case "drone": "airplane"
        case "person", "rescue": "figure.walk"
        case "fuel": "fuelpump.fill"
        case "automobile", "4wd": "car.fill"
        case "camping": "tent.fill"
        case "hut": "house.fill"
        case "waterfalls": "water.waves"
        case "medevac-site": "cross.fill"
        default: "mappin.circle.fill"
        }
    }

    static func defaultColor(for caltopoSymbol: String) -> UIColor {
        switch caltopoSymbol.lowercased() {
        case "cp", "clue", "medevac-site": UIColor(red: 0.18, green: 0.31, blue: 0.68, alpha: 1)
        case "heatsource", "fire-hotspot", "c:ring", "c:target1", "c:target2", "c:target3", "point": .systemRed
        default: .label
        }
    }
}

private final class ArtifactAnnotationView: MKAnnotationView {
    private let background = UIView()
    private let imageView = UIImageView()
    private let glyphLabel = UILabel()

    override init(annotation: (any MKAnnotation)?, reuseIdentifier: String?) {
        super.init(annotation: annotation, reuseIdentifier: reuseIdentifier)
        frame = CGRect(x: 0, y: 0, width: 38, height: 38)
        centerOffset = CGPoint(x: 0, y: -19)
        canShowCallout = true
        collisionMode = .circle
        background.frame = CGRect(x: 3, y: 3, width: 32, height: 32)
        background.layer.cornerRadius = 16
        background.layer.borderWidth = 2
        background.layer.borderColor = UIColor.white.cgColor
        background.layer.shadowOpacity = 0.35
        background.layer.shadowRadius = 2
        background.layer.shadowOffset = .zero
        addSubview(background)
        imageView.frame = CGRect(x: 8, y: 8, width: 22, height: 22)
        imageView.contentMode = .scaleAspectFit
        imageView.tintColor = .white
        addSubview(imageView)
        glyphLabel.frame = bounds
        glyphLabel.textAlignment = .center
        glyphLabel.font = .boldSystemFont(ofSize: 13)
        glyphLabel.textColor = .white
        addSubview(glyphLabel)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(_ artifact: ArtifactAnnotation) {
        annotation = artifact
        background.backgroundColor = artifact.color
        let style = Self.style(for: artifact.caltopoSymbol)
        imageView.image = style.systemImage.flatMap(UIImage.init(systemName:))
        imageView.isHidden = style.systemImage == nil
        glyphLabel.text = style.glyph
        glyphLabel.isHidden = style.glyph == nil
    }

    private static func style(for raw: String) -> (systemImage: String?, glyph: String?) {
        let symbol = raw.lowercased()
        switch symbol {
        case "aperture", "camera": return ("camera.fill", nil)
        case "radiotower": return ("antenna.radiowaves.left.and.right", nil)
        case "drone": return ("airplane", nil)
        case "person", "rescue": return ("figure.walk", nil)
        case "fuel": return ("fuelpump.fill", nil)
        case "automobile", "4wd": return ("car.fill", nil)
        case "camping": return ("tent.fill", nil)
        case "hut": return ("house.fill", nil)
        case "waterfalls": return ("water.waves", nil)
        case "medevac-site": return ("cross.fill", nil)
        case "point": return (nil, "•")
        case "c:ring": return (nil, "○")
        case "c:target1": return (nil, "1")
        case "c:target2": return (nil, "2")
        case "c:target3": return (nil, "3")
        case "cp": return (nil, "CP")
        case "clue": return (nil, "?")
        case "heatsource": return (nil, "×")
        case "fire-hotspot": return (nil, "⊙")
        default:
            let compact = raw.filter(\.isLetter).uppercased()
            return (nil, compact.isEmpty ? "?" : String(compact.prefix(2)))
        }
    }
}

private extension MapCoordinate {
    var clCoordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}

private extension MKCoordinateRegion {
    var offlineBounds: OperationalMapBounds {
        OperationalMapBounds(
            north: center.latitude + span.latitudeDelta / 2,
            south: center.latitude - span.latitudeDelta / 2,
            west: center.longitude - span.longitudeDelta / 2,
            east: center.longitude + span.longitudeDelta / 2
        )
    }
}

private extension UIColor {
    convenience init?(hex: String?) {
        guard var value = hex?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        value.removeAll(where: { $0 == "#" })
        guard let raw = UInt64(value, radix: 16) else { return nil }
        switch value.count {
        case 6:
            self.init(red: CGFloat((raw >> 16) & 0xff) / 255, green: CGFloat((raw >> 8) & 0xff) / 255, blue: CGFloat(raw & 0xff) / 255, alpha: 1)
        case 8:
            self.init(red: CGFloat((raw >> 16) & 0xff) / 255, green: CGFloat((raw >> 8) & 0xff) / 255, blue: CGFloat(raw & 0xff) / 255, alpha: CGFloat((raw >> 24) & 0xff) / 255)
        default:
            return nil
        }
    }
}
