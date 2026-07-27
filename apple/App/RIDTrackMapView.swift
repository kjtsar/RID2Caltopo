import AVKit
import CryptoKit
import MapKit
import R2CCore
import SwiftUI

enum AppleOperationalStatusChipTone {
    case danger
    case caution
    case normal
    case neutral

    var backgroundColor: Color {
        switch self {
        case .danger: Color(red: 0.827, green: 0.184, blue: 0.184)
        case .caution: Color(red: 0.961, green: 0.486, blue: 0)
        case .normal: Color(red: 0.180, green: 0.490, blue: 0.196)
        case .neutral: Color.secondary.opacity(0.18)
        }
    }

    var foregroundColor: Color {
        self == .neutral ? .primary : .white
    }
}

struct AppleOperationalStatusChipLabel: View {
    let title: String
    let tone: AppleOperationalStatusChipTone

    var body: some View {
        Text(title)
            .fontWeight(.medium)
            .foregroundStyle(tone.foregroundColor)
            .lineLimit(1)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(
                tone.backgroundColor,
                in: RoundedRectangle(cornerRadius: 8, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .stroke(
                        tone == .neutral ? Color.secondary.opacity(0.35) : Color.clear,
                        lineWidth: 1
                    )
            }
    }
}

@MainActor
private final class AppleMapViewportMemory: ObservableObject {
    var region = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 39.7392, longitude: -104.9903),
        span: MKCoordinateSpan(latitudeDelta: 0.08, longitudeDelta: 0.08)
    )
    var visibleMapRect: MKMapRect?
}

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
    private var lastPollAtByMapID: [String: Date] = [:]

    private static let minimumPollInterval: TimeInterval = 30
    private static let automaticPollInterval: Duration = .seconds(90)

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
                    try? await Task.sleep(for: Self.automaticPollInterval)
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
        let mapID = configuredMapID
        if let lastPollAt = lastPollAtByMapID[mapID] {
            let remaining = Self.minimumPollInterval - Date().timeIntervalSince(lastPollAt)
            if remaining > 0 {
                status = "CalTopo refresh available in \(Int(remaining.rounded(.up)))s"
                do {
                    try await Task.sleep(for: .milliseconds(Int(remaining * 1_000)))
                } catch {
                    return
                }
            }
        }
        guard !Task.isCancelled, mapID == configuredMapID else { return }
        lastPollAtByMapID[mapID] = Date()
        status = "Refreshing CalTopo artifacts…"
        do {
            let value = try await client.fetchMapArtifacts()
            snapshot = value
            let serverHiddenFolders = Set(value.folders.filter { !$0.initiallyVisible }.map(\.id))
            let visibilityBeforeRefresh = hiddenFolderIDs
            if !visibilityInitialized {
                visibilityInitialized = true
            }
            // Match Android: folders hidden by CalTopo (notably the dated
            // completed-track archive) stay hidden after reconnects, while
            // local hides of otherwise-visible folders are preserved.
            hiddenFolderIDs.formUnion(serverHiddenFolders)
            if hiddenFolderIDs != visibilityBeforeRefresh {
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
            hiddenFolderIDs.formUnion(fallbackFolders.filter { !$0.initiallyVisible }.map(\.id))
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
    @ObservedObject var streamRegistry: AppleStreamRegistry
    @ObservedObject var videoModel: AppleVideoFrameSource
    @ObservedObject var clueStore: AppleClueStore
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    @ObservedObject var orgSettings: AppleOrgConfigSettings
    @ObservedObject var notams: AppleNotamCenter
    @ObservedObject var peerCoordinator: AppleTrackerCoordinator
    @ObservedObject private var airspace = AppleAirspaceCenter.shared
    @ObservedObject private var landRestrictions = AppleLandRestrictionCenter.shared
    let streamURL: URL?
    let ingestAddress: String
    let networkSSID: String
    let onMapStatusTap: () -> Void
    let onRestartStreams: () -> Void

    @StateObject private var artifacts = AppleMapArtifactModel()
    @StateObject private var pilotDisplay = ApplePilotDisplayStore()
    @StateObject private var offlineMaps = AppleMapOfflineManager()
    @StateObject private var viewportMemory = AppleMapViewportMemory()
    @AppStorage("map.baseLayer") private var storedBaseLayer = OperationalMapBaseLayer.openStreetMap.rawValue
    // Match Android's session-scoped StreamsLayoutMode: every app process starts
    // in Split, while changes remain local to the current Live View session.
    @State private var storedLayout = OperationalMapVideoLayout.split.rawValue
    @AppStorage("map.showContours") private var showContours = false
    @AppStorage("map.offlineOnly") private var offlineOnly = false
    @AppStorage("map.followFocusedDrone") private var followFocusedDrone = true
    @AppStorage("map.videoPipEnabled") private var videoPipEnabled = false
    @AppStorage("map.videoPipInsetFraction")
    private var videoPipInsetFraction = OperationalPipSizing.defaultInsetFraction
    @AppStorage("video.coordinateDisplayFormat")
    private var coordinateDisplayFormatRaw = OperationalCoordinateDisplayFormat.decimal.rawValue
    @State private var viewport = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 39.7392, longitude: -104.9903),
        span: MKCoordinateSpan(latitudeDelta: 0.08, longitudeDelta: 0.08)
    )
    @State private var showMapItems = false
    @State private var showOfflinePreparation = false
    @State private var showMapManagement = false
    @State private var selectedBadTile: AppleCachedMapTileSelection?
    @State private var mapTileNotice: String?
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
    @State private var splitFraction: CGFloat = 0.5
    @State private var splitDragStartFraction: CGFloat?
    @State private var streamsFullScreen = false
    @State private var automaticallyExpandedStreamID: String?
    @State private var pipEditorMode = false
    @State private var pipResizeDragStartFraction: Double?
    @State private var expandedStreamID: String?
    @State private var pairingStreamID: String?
    @State private var streamAircraftBindings: [String: String] = [:]

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
            ZStack(alignment: .top) {
                if streamsFullScreen {
                    videoPane
                } else {
                    layoutContent(size: geometry.size)
                }
                if streamsFullScreen {
                    Button("Exit FS") { streamsFullScreen = false }
                        .buttonStyle(.borderedProminent)
                        .padding(10)
                        .accessibilityLabel("Exit full screen")
                }
            }
        }
        .safeAreaInset(edge: .top) {
            if !streamsFullScreen {
                androidLiveViewStatusBar
            }
        }
        .navigationTitle("Live View")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(streamsFullScreen ? .hidden : .visible, for: .navigationBar)
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
        .sheet(isPresented: $showMapManagement) {
            AppleMapCacheManagementView(
                manager: offlineMaps,
                offlineOnly: $offlineOnly,
                followFocusedDrone: Binding(
                    get: { followFocusedDrone },
                    set: { enabled in
                        followFocusedDrone = enabled
                        if enabled { operatorAdjustedViewport = false }
                    }
                ),
                canReloadMap: !caltopoConfiguration.mapID.isEmpty,
                onReloadMap: { artifacts.refresh(caltopoConfiguration) },
                onExportMutualAid: {
                    showMapManagement = false
                    Task { @MainActor in showMutualAidExport = true }
                }
            )
        }
        .sheet(item: $selectedBadTile) { selection in
            BadTileRemovalView(
                selection: selection,
                onRemove: { quarantine in
                    offlineMaps.removeCachedTile(selection, quarantineMatchingHash: quarantine)
                    selectedBadTile = nil
                }
            )
        }
        .alert(
            "Bad Tile",
            isPresented: Binding(
                get: { mapTileNotice != nil },
                set: { if !$0 { mapTileNotice = nil } }
            )
        ) {
            Button("OK") { mapTileNotice = nil }
        } message: {
            Text(mapTileNotice ?? "")
        }
        .sheet(isPresented: pairingSheetPresented) {
            if let pairingStreamID {
                StreamAircraftPairingView(
                    streamID: pairingStreamID,
                    tracks: model.tracks,
                    identityStore: identityStore,
                    selectedAircraftID: streamAircraftBindings[pairingStreamID],
                    onSelect: { aircraftID in
                        streamAircraftBindings[pairingStreamID] = aircraftID
                        self.pairingStreamID = nil
                    },
                    onUnpair: {
                        streamAircraftBindings.removeValue(forKey: pairingStreamID)
                        self.pairingStreamID = nil
                    }
                )
            }
        }
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
                model: model,
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
                pendingSnapshot = PendingClueSnapshot(
                    snapshot: snapshot,
                    defaultAircraftID: track.aircraftID,
                    gimbalAngleDegrees: -60,
                    observation: track.lastObservation,
                    altitudeDisplay: model.altitudeDisplayByAircraftID[track.aircraftID],
                    heading: OperationalClueGeometry.selectedHeading(
                        cameraYawDegrees: nil,
                        streamHeadingDegrees: nil,
                        ridHeadingDegrees: track.lastObservation.headingDegrees
                    )
                )
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
        .onAppear {
            if ProcessInfo.processInfo.arguments.contains("--show-anomaly")
                || ProcessInfo.processInfo.arguments.contains("--show-streams") {
                layout = .video
            } else {
                applyPipPreference()
            }
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
            splitLayout(size: size)
        case .mapPrimary:
            ZStack(alignment: .bottomTrailing) {
                mapPane(inset: false)
                insetFrame(size: size, onTap: { layout = .videoPrimary }) { videoPane }
            }
        case .videoPrimary:
            ZStack(alignment: .bottomTrailing) {
                videoPane
                insetFrame(size: size, onTap: { layout = .mapPrimary }) { mapPane(inset: true) }
            }
        }
    }

    @ViewBuilder
    private func splitLayout(size: CGSize) -> some View {
        let dividerThickness: CGFloat = 22
        if size.width > size.height {
            let available = max(0, size.width - dividerThickness)
            HStack(spacing: 0) {
                videoPane
                    .frame(width: available * splitFraction)
                    .contentShape(Rectangle())
                    .simultaneousGesture(TapGesture().onEnded {
                        layout = OperationalMapVideoLayout.video.withPictureInPicture(videoPipEnabled)
                    })
                splitDivider(vertical: true, available: available)
                mapPane(inset: false)
                    .frame(width: available * (1 - splitFraction))
                    .contentShape(Rectangle())
                    .simultaneousGesture(TapGesture().onEnded {
                        layout = OperationalMapVideoLayout.map.withPictureInPicture(videoPipEnabled)
                    })
            }
        } else {
            let available = max(0, size.height - dividerThickness)
            VStack(spacing: 0) {
                videoPane
                    .frame(height: available * splitFraction)
                    .contentShape(Rectangle())
                    .simultaneousGesture(TapGesture().onEnded {
                        layout = OperationalMapVideoLayout.video.withPictureInPicture(videoPipEnabled)
                    })
                splitDivider(vertical: false, available: available)
                mapPane(inset: false)
                    .frame(height: available * (1 - splitFraction))
                    .contentShape(Rectangle())
                    .simultaneousGesture(TapGesture().onEnded {
                        layout = OperationalMapVideoLayout.map.withPictureInPicture(videoPipEnabled)
                    })
            }
        }
    }

    private func splitDivider(vertical: Bool, available: CGFloat) -> some View {
        Rectangle()
            .fill(.clear)
            .frame(
                width: vertical ? 22 : nil,
                height: vertical ? nil : 22
            )
            .overlay {
                Rectangle()
                    .fill(Color.accentColor.opacity(0.75))
                    .frame(
                        width: vertical ? 4 : nil,
                        height: vertical ? nil : 4
                    )
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture()
                    .onChanged { value in
                        guard available > 0 else { return }
                        let start = splitDragStartFraction ?? splitFraction
                        if splitDragStartFraction == nil { splitDragStartFraction = start }
                        let delta = vertical ? value.translation.width : value.translation.height
                        splitFraction = min(0.9, max(0.1, start + delta / available))
                    }
                    .onEnded { _ in splitDragStartFraction = nil }
            )
            .accessibilityLabel("Resize video and map panes")
    }

    private func mapPane(inset: Bool) -> some View {
        let renderedArtifacts = artifacts.visibleSnapshot.excludingRenderedPointIDs(
            [peerCoordinator.localZoneID]
        )
        return ZStack(alignment: .bottomTrailing) {
            OperationalMKMapView(
                tracks: model.tracks,
                aircraftDisplay: aircraftDisplay,
                altitudeDisplay: model.altitudeDisplayByAircraftID,
                clues: clueStore.records,
                artifacts: renderedArtifacts,
                airspaceState: airspace.enabled ? airspace.state : OperationalAirspaceState(),
                notamState: notams.showOnMap ? notams.state : AppleNotamState(),
                landRestrictionState: landRestrictions.showOnMap
                    ? landRestrictions.state
                    : AppleLandRestrictionState(),
                baseLayer: baseLayer,
                showContours: showContours,
                offlineOnly: offlineOnly,
                tileCacheRevision: offlineMaps.cacheStats.files,
                operatorCoordinate: locationProvider.lastLocation?.coordinate,
                operatorStatusLines: peerCoordinator.localDeviceStatusLines,
                viewport: $viewport,
                viewportMemory: viewportMemory,
                inset: inset,
                predictiveHeadEnabled: orgSettings.predictiveHeadEnabled,
                focusedAircraftID: focusedAircraftID,
                followFocusedDrone: followFocusedDrone,
                operatorAdjustedViewport: $operatorAdjustedViewport,
                onSelectClue: { selectedClueID = $0 },
                onSelectAircraft: { remoteID in
                    focusedAircraftID = remoteID
                    if followFocusedDrone { operatorAdjustedViewport = false }
                    guard !inset else { return }
                    let identity = identityStore.identity(for: remoteID)
                    selectedPilotSettings = PilotDisplaySelection(
                        id: remoteID,
                        remoteID: remoteID,
                        displayName: identity?.mappedID ?? remoteID,
                        pilotCallsign: identity?.pilotCallsign ?? ""
                    )
                },
                onLongPressTile: { zoom, x, y in
                    Task {
                        if let selection = await offlineMaps.cachedTileSelection(
                            zoom: zoom,
                            x: x,
                            y: y,
                            baseLayer: baseLayer
                        ) {
                            selectedBadTile = selection
                        } else {
                            mapTileNotice = "Selected tile z=\(zoom) x=\(x) y=\(y) is not cached yet."
                        }
                    }
                }
            )
            Text(mapAttribution(inset: inset))
                .font(.system(size: inset ? 8 : 10))
                .foregroundStyle(.primary)
                .padding(.horizontal, 5)
                .padding(.vertical, 3)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 4))
                .padding(5)
                .allowsHitTesting(false)
            if !inset {
                VStack {
                    HStack {
                        Spacer()
                        mapSettingsMenu
                    }
                    Spacer()
                }
                .padding(6)
            }
        }
    }

    private var mapSettingsMenu: some View {
        Menu {
            Menu("Layer: \(baseLayer.label)") {
                ForEach(OperationalMapBaseLayer.allCases, id: \.self) { option in
                    Button {
                        baseLayer = option
                    } label: {
                        if option == baseLayer {
                            Label(option.label, systemImage: "checkmark")
                        } else {
                            Text(option.label)
                        }
                    }
                }
                Divider()
                Button {
                    showContours.toggle()
                } label: {
                    Text("Contours: \(showContours ? "On" : "Off")")
                }
            }
            Button("Predictive Head: \(orgSettings.predictiveHeadEnabled ? "On" : "Off")") {
                orgSettings.setPredictiveHeadEnabled(!orgSettings.predictiveHeadEnabled)
            }
            Button("Download Map…") { showOfflinePreparation = true }
            Button("Map Folders…") { showMapItems = true }
                .disabled(artifacts.snapshot.folders.isEmpty)
            Button("Map Management…") { showMapManagement = true }
        } label: {
            Image(systemName: "gearshape.fill")
                .font(.title3)
                .frame(width: 42, height: 42)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 8))
        }
        .accessibilityLabel("Map settings")
    }

    private func mapAttribution(inset: Bool) -> String {
        let base = baseLayer == .openStreetMap ? "© OpenStreetMap contributors" : "Tiles © Esri"
        return showContours && !inset ? "\(base) · Contours: USGS" : base
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
            AppleStreamsGridView(
                registry: streamRegistry,
                ingestAddress: ingestAddress,
                showsSetupHeader: false,
                showsNavigationTitle: false,
                expandedSessionID: expandedStreamID,
                onSelectSession: toggleExpandedStream,
                onLongPressSession: { pairingStreamID = $0 },
                onDoubleTapSession: { streamID, zoomScale, normalizedPan in
                    expandedStreamID = streamID
                    beginSnapshotCapture(
                        streamID: streamID,
                        zoomScale: zoomScale,
                        normalizedPan: normalizedPan
                    )
                },
                onCloseSession: { streamID in
                    streamRegistry.close(streamID)
                    if expandedStreamID == streamID { expandedStreamID = nil }
                },
                onRestartStreams: onRestartStreams,
                telemetryText: streamTelemetryText,
                coordinateText: streamCoordinateText,
                coordinateDisplayFormat: coordinateDisplayFormat,
                onCoordinateDisplayFormatChange: { coordinateDisplayFormat = $0 },
                telemetryPairingState: streamTelemetryPairingState
            )
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    Button(action: { beginSnapshotCapture() }) {
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
            reconcileOperationalStreamSelection()
        }
        .onChange(of: operationalStreamIDs) { _, _ in
            reconcileOperationalStreamSelection()
        }
    }

    private var operationalStreamIDs: [String] {
        streamRegistry.sessions.filter { $0.id != "demo" }.map(\.id)
    }

    private func toggleExpandedStream(_ id: String) {
        automaticallyExpandedStreamID = nil
        if expandedStreamID == id {
            expandedStreamID = nil
        } else {
            expandedStreamID = id
        }
    }

    private func reconcileOperationalStreamSelection() {
        let ids = operationalStreamIDs
        if ids.isEmpty {
            expandedStreamID = nil
            automaticallyExpandedStreamID = nil
        } else if ids.count == 1 {
            let streamID = ids[0]
            if expandedStreamID != streamID || automaticallyExpandedStreamID != nil {
                expandedStreamID = streamID
                automaticallyExpandedStreamID = streamID
            }
        } else {
            if automaticallyExpandedStreamID != nil {
                expandedStreamID = nil
                automaticallyExpandedStreamID = nil
            } else if let expandedStreamID, !ids.contains(expandedStreamID) {
                self.expandedStreamID = nil
            }
        }
    }

    private var pairingSheetPresented: Binding<Bool> {
        Binding(
            get: { pairingStreamID != nil },
            set: { if !$0 { pairingStreamID = nil } }
        )
    }

    private func aircraftID(for streamID: String) -> String? {
        if let bound = streamAircraftBindings[streamID],
           model.tracks.contains(where: { $0.aircraftID == bound }) {
            return bound
        }
        let normalizedStream = streamID.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let exact = model.tracks.filter { track in
            let identity = identityStore.identity(for: track.aircraftID)
            return track.aircraftID.uppercased() == normalizedStream
                || identity?.mappedID.uppercased() == normalizedStream
        }
        if exact.count == 1 { return exact[0].aircraftID }
        return nil
    }

    private func streamTelemetryText(_ streamID: String) -> String? {
        guard let aircraftID = aircraftID(for: streamID),
              model.tracks.contains(where: { $0.aircraftID == aircraftID })
        else {
            return model.tracks.isEmpty ? nil : "Long-press to pair"
        }
        let identity = identityStore.identity(for: aircraftID)
        let label = identity?.mappedID.isEmpty == false ? identity!.mappedID : aircraftID
        let altitude = model.altitudeDisplayByAircraftID[aircraftID]
        let ato = altitude?.atoFeet.map { String(format: "%.0f", $0) } ?? "--"
        let agl = altitude?.aglFeet.map { String(format: "%.0f", $0) } ?? "--"
        let range = altitude?.rangeFeet.map { String(format: "%.0f", $0) } ?? "--"
        return "\(label)  ATO \(ato)  AGL \(agl)  RNG \(range) ft"
    }

    private var coordinateDisplayFormat: OperationalCoordinateDisplayFormat {
        get { OperationalCoordinateDisplayFormat(rawValue: coordinateDisplayFormatRaw) ?? .decimal }
        nonmutating set { coordinateDisplayFormatRaw = newValue.rawValue }
    }

    private func streamCoordinateText(_ streamID: String) -> String? {
        guard let aircraftID = aircraftID(for: streamID),
              let observation = model.tracks.first(where: { $0.aircraftID == aircraftID })?.lastObservation
        else { return nil }
        return OperationalCoordinateFormatter.format(
            latitude: observation.latitude,
            longitude: observation.longitude,
            as: coordinateDisplayFormat
        )
    }

    private func streamTelemetryPairingState(_ streamID: String) -> AppleStreamTelemetryPairingState {
        if aircraftID(for: streamID) != nil { return .paired }
        return model.tracks.isEmpty ? .noTelemetry : .available
    }

    private func beginSnapshotCapture(
        streamID requestedStreamID: String? = nil,
        zoomScale: Double = 1,
        normalizedPan: CGPoint = .zero
    ) {
        guard !capturingSnapshot else { return }
        let session = requestedStreamID.flatMap { requested in
            streamRegistry.sessions.first(where: { $0.id == requested })
        } ?? streamRegistry.focusedSession
        guard !model.tracks.isEmpty else {
            clueError = "No active aircraft is available to associate with this snapshot."
            return
        }
        let streamID = session.id
        guard let defaultAircraftID = aircraftID(for: streamID) else {
            clueError = "Long-press stream \(streamID) and pair it with a drone before capturing a clue."
            return
        }
        guard let captureTrack = model.tracks.first(where: { $0.aircraftID == defaultAircraftID }) else {
            clueError = "The paired aircraft telemetry is no longer available."
            return
        }
        let captureObservation = captureTrack.lastObservation
        let captureAltitudeDisplay = model.altitudeDisplayByAircraftID[defaultAircraftID]
        let captureGimbalPitch = session.model.latestGimbalPitchDegrees
        let captureHeading = OperationalClueGeometry.selectedHeading(
            cameraYawDegrees: session.model.latestCameraYawDegrees,
            streamHeadingDegrees: session.model.latestStreamHeadingDegrees,
            ridHeadingDegrees: captureObservation.headingDegrees
        )
        capturingSnapshot = true
        Task {
            defer { capturingSnapshot = false }
            do {
                let snapshot = try await session.model.captureSnapshot(
                    zoomScale: zoomScale,
                    normalizedPan: normalizedPan
                )
                pendingSnapshot = PendingClueSnapshot(
                    snapshot: snapshot,
                    defaultAircraftID: defaultAircraftID,
                    gimbalAngleDegrees: OperationalClueGeometry.selectedGimbalAngleDegrees(
                        streamPitchDegrees: captureGimbalPitch
                    ),
                    observation: captureObservation,
                    altitudeDisplay: captureAltitudeDisplay,
                    heading: captureHeading
                )
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

    private func insetFrame<Content: View>(
        size: CGSize,
        onTap: @escaping () -> Void,
        @ViewBuilder content: () -> Content
    ) -> some View {
        let fullFrameAspectRatio = size.height > 0
            ? Double(size.width / size.height)
            : OperationalPipSizing.aspectRatio
        let insetSize = OperationalPipSizing.insetSize(
            containerWidth: size.width,
            containerHeight: size.height,
            insetFraction: videoPipInsetFraction,
            aspectRatio: fullFrameAspectRatio
        )
        let maximumInsetWidth = max(1, Double(size.width) - OperationalPipSizing.framePadding)

        return ZStack(alignment: .topLeading) {
            content()
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture(perform: onTap)
                .simultaneousGesture(
                    LongPressGesture(minimumDuration: 0.5)
                        .onEnded { _ in pipEditorMode.toggle() }
                )
            if pipEditorMode {
                ZStack {
                    Color.accentColor.opacity(0.85)
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                        .font(.headline)
                        .foregroundStyle(.white)
                }
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture()
                        .onChanged { value in
                            if pipResizeDragStartFraction == nil {
                                pipResizeDragStartFraction = videoPipInsetFraction
                            }
                            let start = pipResizeDragStartFraction
                                ?? OperationalPipSizing.defaultInsetFraction
                            let delta = (-Double(value.translation.width) - Double(value.translation.height))
                                / maximumInsetWidth
                            videoPipInsetFraction = OperationalPipSizing.clampInsetFraction(start + delta)
                        }
                        .onEnded { _ in pipResizeDragStartFraction = nil }
                )
                .accessibilityLabel("Resize picture in picture")
                .accessibilityHint("Drag diagonally to resize")
            }
        }
            .frame(width: insetSize.width, height: insetSize.height)
            .background(.black)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .stroke(pipEditorMode ? Color.accentColor : .white.opacity(0.8), lineWidth: 2)
            )
            .shadow(radius: 5)
            .padding(12)
    }

    private var androidLiveViewStatusBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                Text(ingestAddress.hasPrefix("rtmp://")
                    ? "🟢 In => \(ingestAddress)/<droneDesig>"
                    : "🟡 \(ingestAddress)")
                    .lineLimit(1)
                Button(action: onMapStatusTap) {
                    Text(caltopoConfiguration.mapTitle.isEmpty
                        ? (caltopoConfiguration.mapID.isEmpty ? "STANDALONE" : caltopoConfiguration.mapID)
                        : caltopoConfiguration.mapTitle)
                        .fontWeight(.semibold)
                        .lineLimit(1)
                }
                .buttonStyle(.bordered)
                Text("on \(networkSSID)")
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Label("\(model.tracks.count) active", systemImage: "airplane.circle")
                Text("\(model.acceptedObservationCount) points")
            if airspace.enabled || notams.enabled {
                operationalStatusChip(
                    usesAirspaceRestrictionStatus
                        ? airspace.state.chipLabel
                        : notams.state.chipLabel,
                    tone: usesAirspaceRestrictionStatus ? airspaceTone : notamTone
                ) {
                    if usesAirspaceRestrictionStatus { showAirspace = true }
                    else { showNotams = true }
                }
            }
            if landRestrictions.enabled {
                operationalStatusChip(
                    landRestrictions.state.chipLabel,
                    tone: landRestrictionTone
                ) {
                    showLandRestrictions = true
                }
            }
            if offlineOnly { Label("Offline", systemImage: "wifi.slash") }
            }
        }
        .font(.caption.monospacedDigit())
        .padding(.horizontal)
        .padding(.vertical, 6)
        .background(.regularMaterial)
    }

    private var usesAirspaceRestrictionStatus: Bool {
        !notams.state.visible || airspace.state.severity != .normal
    }

    private func operationalStatusChip(
        _ title: String,
        tone: AppleOperationalStatusChipTone,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            AppleOperationalStatusChipLabel(title: title, tone: tone)
        }
        .buttonStyle(.plain)
    }

    private var notamTone: AppleOperationalStatusChipTone {
        switch notams.state.chipSeverity {
        case .danger: .danger
        case .caution: .caution
        case .normal: .normal
        case .neutral: .neutral
        }
    }

    private var airspaceTone: AppleOperationalStatusChipTone {
        switch airspace.state.severity {
        case .danger: .danger
        case .caution: .caution
        case .normal: .normal
        case .neutral: .neutral
        }
    }

    private var landRestrictionTone: AppleOperationalStatusChipTone {
        switch landRestrictions.state.severity {
        case .danger: .danger
        case .caution: .caution
        case .normal: .normal
        case .neutral: .neutral
        }
    }

    @ToolbarContentBuilder
    private var mapToolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            if !streamsFullScreen {
                Button("Enter FS") { streamsFullScreen = true }
                Button(videoPipEnabled ? "PiP:On" : "PiP:Off") {
                    videoPipEnabled.toggle()
                    if !videoPipEnabled { pipEditorMode = false }
                    applyPipPreference()
                }
                if layout != .split {
                    Button("Split") {
                        videoPipEnabled = false
                        pipEditorMode = false
                        layout = .split
                    }
                }
            }
        }
    }

    private func applyPipPreference() {
        if !videoPipEnabled { pipEditorMode = false }
        layout = layout.withPictureInPicture(videoPipEnabled)
    }

}

private struct BadTileRemovalView: View {
    let selection: AppleCachedMapTileSelection
    let onRemove: (Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var quarantineMatchingHash = true

    var body: some View {
        NavigationStack {
            Form {
                Section("Remove Bad Tile?") {
                    LabeledContent("Tile", value: "z=\(selection.zoom) x=\(selection.x) y=\(selection.y)")
                    LabeledContent("Hash", value: String(selection.hash.prefix(12)) + "…")
                    Toggle("Also quarantine same-hash tiles", isOn: $quarantineMatchingHash)
                }
                Section {
                    Button("Remove", role: .destructive) {
                        onRemove(quarantineMatchingHash)
                    }
                }
            }
            .navigationTitle("Remove Bad Tile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

private struct StreamAircraftPairingView: View {
    let streamID: String
    let tracks: [RidAircraftTrack]
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    let selectedAircraftID: String?
    let onSelect: (String) -> Void
    let onUnpair: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var pendingMismatchedTrack: RidAircraftTrack?

    var body: some View {
        NavigationStack {
            List {
                Section("Stream \(streamID)") {
                    if tracks.isEmpty {
                        ContentUnavailableView(
                            "No drones available",
                            systemImage: "airplane.slash",
                            description: Text("Wait for a Remote ID update, then try again.")
                        )
                    } else {
                        ForEach(tracks) { track in
                            Button {
                                if pairingMatchesStream(track) {
                                    onSelect(track.aircraftID)
                                } else {
                                    pendingMismatchedTrack = track
                                }
                            } label: {
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(identityStore.identity(for: track.aircraftID)?.mappedID ?? track.aircraftID)
                                        Text(track.aircraftID)
                                            .font(.caption.monospaced())
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if selectedAircraftID == track.aircraftID {
                                        Image(systemName: "checkmark")
                                    }
                                }
                            }
                        }
                    }
                }
                if selectedAircraftID != nil {
                    Section {
                        Button("Unpair Stream", role: .destructive, action: onUnpair)
                    }
                }
                Section {
                    Text("Pairing lasts for the current app session. Use the stream designator configured in the aircraft profile whenever possible so RID2Caltopo can match it automatically.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Pair Stream")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .alert(
                "Stream Designator Mismatch",
                isPresented: Binding(
                    get: { pendingMismatchedTrack != nil },
                    set: { if !$0 { pendingMismatchedTrack = nil } }
                ),
                presenting: pendingMismatchedTrack
            ) { track in
                Button("Cancel", role: .cancel) { pendingMismatchedTrack = nil }
                Button("Pair Anyway") {
                    pendingMismatchedTrack = nil
                    onSelect(track.aircraftID)
                }
            } message: { track in
                Text(
                    "Stream “\(streamID)” does not match “\(displayName(for: track))”. "
                    + "Pair only if you have positively identified this video source."
                )
            }
        }
    }

    private func pairingMatchesStream(_ track: RidAircraftTrack) -> Bool {
        let stream = normalized(streamID)
        return stream == normalized(track.aircraftID)
            || stream == normalized(identityStore.identity(for: track.aircraftID)?.mappedID ?? "")
    }

    private func displayName(for track: RidAircraftTrack) -> String {
        identityStore.identity(for: track.aircraftID)?.mappedID ?? track.aircraftID
    }

    private func normalized(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }
}

private struct PendingClueSnapshot: Identifiable {
    let id = UUID()
    let snapshot: AppleVideoSnapshot
    let defaultAircraftID: String
    let gimbalAngleDegrees: Double
    let observation: RidObservation
    let altitudeDisplay: OperationalAircraftAltitudeDisplay?
    let heading: OperationalClueHeadingSelection
}

private struct ClueSubmissionView: View {
    private enum FocusedField: Hashable {
        case title
        case description
    }

    let pending: PendingClueSnapshot
    let model: RIDTrackViewModel
    let tracks: [RidAircraftTrack]
    let altitudeDisplay: [String: OperationalAircraftAltitudeDisplay]
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    let onSubmit: (AppleClueDraft, Data, Bool) -> Void
    let onCancel: () -> Void

    @State private var selectedAircraftID: String
    @State private var title: String
    @State private var description: String
    @State private var gimbalAngle: Double
    @State private var terrainProjection: OperationalClueProjection?
    @State private var terrainProjectionPending = false
    @FocusState private var focusedField: FocusedField?

    init(
        pending: PendingClueSnapshot,
        model: RIDTrackViewModel,
        tracks: [RidAircraftTrack],
        altitudeDisplay: [String: OperationalAircraftAltitudeDisplay],
        identityStore: AppleDroneConfirmationStore,
        onSubmit: @escaping (AppleClueDraft, Data, Bool) -> Void,
        onCancel: @escaping () -> Void
    ) {
        self.pending = pending
        self.model = model
        self.tracks = tracks
        self.altitudeDisplay = altitudeDisplay
        self.identityStore = identityStore
        self.onSubmit = onSubmit
        self.onCancel = onCancel
        _selectedAircraftID = State(initialValue: pending.defaultAircraftID)
        _gimbalAngle = State(initialValue: pending.gimbalAngleDegrees)
        // Android deliberately opens on an empty, focused title so the
        // operator can immediately type the clue name without clearing a
        // generated value.
        _title = State(initialValue: "")
        _description = State(initialValue: Self.clueDescriptionTemplate(pending.snapshot.capturedAt))
    }

    private var track: RidAircraftTrack? { tracks.first { $0.aircraftID == selectedAircraftID } }
    private var usesCaptureTelemetry: Bool { selectedAircraftID == pending.defaultAircraftID }
    private var observation: RidObservation? {
        usesCaptureTelemetry ? pending.observation : track?.lastObservation
    }
    private var display: OperationalAircraftAltitudeDisplay? {
        usesCaptureTelemetry ? pending.altitudeDisplay : altitudeDisplay[selectedAircraftID]
    }
    private var heading: OperationalClueHeadingSelection {
        if usesCaptureTelemetry {
            return pending.heading
        }
        return OperationalClueGeometry.selectedHeading(
            cameraYawDegrees: nil,
            streamHeadingDegrees: nil,
            ridHeadingDegrees: observation?.headingDegrees
        )
    }
    private var designator: String {
        identityStore.identity(for: selectedAircraftID)?.mappedID ?? selectedAircraftID
    }
    private var aglMeters: Double? { display?.aglFeet.map { $0 * 0.3048 } }
    private var atoMeters: Double? { display?.atoFeet.map { $0 * 0.3048 } }
    private var flatProjection: OperationalClueProjection? {
        guard let observation else { return nil }
        return OperationalClueGeometry.project(
            droneLatitude: observation.latitude,
            droneLongitude: observation.longitude,
            droneAltitudeMeters: observation.altitudeMeters,
            headingDegrees: heading.degrees,
            aglMeters: aglMeters,
            gimbalAngleDegrees: gimbalAngle
        )
    }
    private var projection: OperationalClueProjection? { terrainProjection ?? flatProjection }
    private var projectionInput: ClueProjectionInput? {
        guard let observation else { return nil }
        return ClueProjectionInput(
            aircraftID: selectedAircraftID,
            latitude: observation.latitude,
            longitude: observation.longitude,
            altitudeMeters: observation.altitudeMeters,
            headingDegrees: heading.degrees,
            aglMeters: aglMeters,
            gimbalAngleDegrees: gimbalAngle
        )
    }
    private var clueDistanceFeet: Double? {
        guard let observation, let projection else { return nil }
        return CLLocation(
            latitude: observation.latitude,
            longitude: observation.longitude
        ).distance(from: CLLocation(
            latitude: projection.latitude,
            longitude: projection.longitude
        )) * 3.28084
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
                    if let observation, let projection {
                        LabeledContent("Drone", value: coordinate(observation.latitude, observation.longitude))
                        LabeledContent("Clue", value: coordinate(projection.latitude, projection.longitude))
                        LabeledContent("Heading", value: headingMeasurement(heading.degrees))
                        LabeledContent("Heading source", value: heading.sourceLabel ?? "Unavailable")
                        LabeledContent("AGL", value: measurement(display?.aglFeet, suffix: display?.aglStale == true ? "? ft" : " ft"))
                        LabeledContent("ATO", value: measurement(display?.atoFeet, suffix: " ft"))
                        LabeledContent("Distance", value: measurement(clueDistanceFeet, suffix: " ft"))
                    }
                }
                Section("Camera projection") {
                    LabeledContent("Gimbal angle", value: "\(Int(gimbalAngle.rounded()))°")
                    Slider(value: $gimbalAngle, in: -90 ... 0, step: 1)
                    Text("-90° is straight down; 0° is the horizon.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if terrainProjectionPending {
                        ProgressView("Intersecting camera sightline with DEM…")
                    } else if terrainProjection != nil {
                        Label("DEM terrain projection applied", systemImage: "mountain.2")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Section("Report") {
                    TextField("Title", text: $title)
                        .focused($focusedField, equals: .title)
                        .submitLabel(.next)
                        .onSubmit { focusedField = .description }
                    TextField("Description", text: $description, axis: .vertical)
                        .lineLimit(3 ... 8)
                        .focused($focusedField, equals: .description)
                }
                Section {
                    Button("Local Marker Only", systemImage: "mappin.and.ellipse") {
                        submit(publish: false)
                    }
                    .disabled(terrainProjectionPending)
                    Button {
                        submit(publish: true)
                    } label: {
                        Label("Submit", systemImage: "icloud.and.arrow.up")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(
                        title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            || terrainProjectionPending
                    )
                } footer: {
                    Text("Submit saves the clue locally before starting its CalTopo upload.")
                }
            }
            .navigationTitle("Submit Clue")
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
            }
            .onAppear {
                DispatchQueue.main.async {
                    focusedField = .title
                }
            }
            .task(id: projectionInput) {
                terrainProjection = nil
                guard let input = projectionInput, let observation else { return }
                terrainProjectionPending = true
                let refined = await model.projectClueWithTerrain(
                    observation: observation,
                    headingDegrees: input.headingDegrees,
                    aglMeters: input.aglMeters,
                    gimbalAngleDegrees: input.gimbalAngleDegrees
                )
                guard !Task.isCancelled, projectionInput == input else { return }
                terrainProjection = refined
                terrainProjectionPending = false
                AppleLog.info(
                    "Clue",
                    String(
                        format: "DEM projection aircraft=%@ lat=%.6f lng=%.6f heading=%@ aglM=%@ gimbal=%.1f",
                        input.aircraftID,
                        refined.latitude,
                        refined.longitude,
                        input.headingDegrees.map { String(format: "%.1f", $0) } ?? "nil",
                        input.aglMeters.map { String(format: "%.1f", $0) } ?? "nil",
                        input.gimbalAngleDegrees
                    )
                )
            }
        }
    }

    private func submit(publish: Bool) {
        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        AppleLog.info(
            "Clue",
            "Clue form action publish=\(publish) aircraft=\(selectedAircraftID) titleLength=\(trimmedTitle.count)"
        )
        guard let observation, let projection else {
            AppleLog.error(
                "Clue",
                "Clue form submission blocked because aircraft telemetry is no longer available aircraft=\(selectedAircraftID)"
            )
            return
        }
        let clueAltitude = projection.altitudeMeters.map { String(format: "%.0f'", $0 * 3.28084) } ?? "N/A"
        let usng = OperationalCoordinateFormatter.format(
            latitude: projection.latitude,
            longitude: projection.longitude,
            as: .usng
        ).replacingOccurrences(of: "loc:", with: "")
        let summary = """
        Projected clue location:
          Position: \(String(format: "%.6f, %.6f", projection.latitude, projection.longitude)) alt \(clueAltitude)
          USNG: \(usng)
          Heading used for clue: \(headingMeasurement(heading.degrees))
          Heading source: \(heading.sourceLabel ?? "N/A")
          Gimbal angle at capture: \(String(format: "%.1f°", gimbalAngle))
          AGL: \(measurement(display?.aglFeet, suffix: display?.aglStale == true ? "? ft" : " ft"))
          ATO: \(measurement(display?.atoFeet, suffix: " ft"))
          Distance to clue: \(measurement(clueDistanceFeet, suffix: " ft"))
        """
        let trimmedDescription = description.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalDescription = trimmedDescription.isEmpty ? summary : trimmedDescription + "\n\n" + summary
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
            headingDegrees: heading.degrees,
            aglMeters: aglMeters,
            atoMeters: atoMeters,
            gimbalAngleDegrees: gimbalAngle,
            title: trimmedTitle,
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

    private func headingMeasurement(_ value: Double?) -> String {
        guard let heading = RidHeading.roundedWholeDegrees(value) else { return "--" }
        return "\(heading)°"
    }

    private static let timestampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter
    }()

    private static func clueDescriptionTemplate(_ date: Date) -> String {
        "time: \(timestampFormatter.string(from: date))\nfound by: \nreported to IC: yes|no\n"
    }
}

private struct ClueProjectionInput: Hashable {
    let aircraftID: String
    let latitude: Double
    let longitude: Double
    let altitudeMeters: Double?
    let headingDegrees: Double?
    let aglMeters: Double?
    let gimbalAngleDegrees: Double
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
            .navigationTitle("Map Folders")
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

private struct StaticMapRenderState: Equatable {
    let clues: [OperationalClueRecord]
    let artifacts: CaltopoArtifactSnapshot
    let airspaceRecords: [OperationalFacilityMapRecord]
    let notamsEnabled: Bool
    let notamsVisible: Bool
    let notices: [OperationalNotam]
    let landRestrictionsEnabled: Bool
    let landRestrictionsVisible: Bool
    let landAreas: [OperationalLandArea]
    let inset: Bool
}

private struct AircraftTrackRenderInput: Equatable {
    let aircraftID: String
    let points: [RidTrackPoint]
    let operatorLatitude: Double?
    let operatorLongitude: Double?

    init(_ track: RidAircraftTrack) {
        aircraftID = track.aircraftID
        points = track.points
        operatorLatitude = track.lastObservation.operatorLatitude
        operatorLongitude = track.lastObservation.operatorLongitude
    }
}

private struct AircraftMapRenderState: Equatable {
    let tracks: [AircraftTrackRenderInput]
    let display: [String: AircraftMapDisplay]
    let altitude: [String: OperationalAircraftAltitudeDisplay]
    let inset: Bool
    let predictiveHeadEnabled: Bool
    let focusedAircraftID: String?
    let followFocusedDrone: Bool
    let centerLatitude: Double
    let centerLongitude: Double
    let latitudeDelta: Double
    let longitudeDelta: Double
    let width: Double
    let height: Double
    let predictionSecond: Int?
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

private final class ViewportPreservingMKMapView: MKMapView {
    var onBoundsLayout: ((MKMapView) -> Void)?

    override func layoutSubviews() {
        super.layoutSubviews()
        onBoundsLayout?(self)
    }
}

private struct OperationalMKMapView: UIViewRepresentable {
    let tracks: [RidAircraftTrack]
    let aircraftDisplay: [String: AircraftMapDisplay]
    let altitudeDisplay: [String: OperationalAircraftAltitudeDisplay]
    let clues: [OperationalClueRecord]
    let artifacts: CaltopoArtifactSnapshot
    let airspaceState: OperationalAirspaceState
    let notamState: AppleNotamState
    let landRestrictionState: AppleLandRestrictionState
    let baseLayer: OperationalMapBaseLayer
    let showContours: Bool
    let offlineOnly: Bool
    let tileCacheRevision: Int
    let operatorCoordinate: CLLocationCoordinate2D?
    let operatorStatusLines: [String]
    @Binding var viewport: MKCoordinateRegion
    let viewportMemory: AppleMapViewportMemory
    let inset: Bool
    let predictiveHeadEnabled: Bool
    let focusedAircraftID: String?
    let followFocusedDrone: Bool
    @Binding var operatorAdjustedViewport: Bool
    let onSelectClue: (UUID) -> Void
    let onSelectAircraft: (String) -> Void
    let onLongPressTile: (Int, Int, Int) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(
            viewport: $viewport,
            viewportMemory: viewportMemory,
            operatorAdjustedViewport: $operatorAdjustedViewport,
            onSelectClue: onSelectClue,
            onSelectAircraft: onSelectAircraft,
            onLongPressTile: onLongPressTile
        )
    }

    func makeUIView(context: Context) -> MKMapView {
        let map = ViewportPreservingMKMapView()
        map.delegate = context.coordinator
        map.showsCompass = false
        map.showsScale = !inset
        map.pointOfInterestFilter = .excludingAll
        map.setRegion(viewportMemory.region, animated: false)
        if !inset {
            let compass = MKCompassButton(mapView: map)
            compass.compassVisibility = .adaptive
            compass.translatesAutoresizingMaskIntoConstraints = false
            compass.accessibilityLabel = "Return map to north up"
            map.addSubview(compass)
            NSLayoutConstraint.activate([
                compass.trailingAnchor.constraint(equalTo: map.trailingAnchor, constant: -8),
                compass.topAnchor.constraint(equalTo: map.safeAreaLayoutGuide.topAnchor, constant: 58),
            ])
        }
        context.coordinator.installLongPress(on: map)
        map.onBoundsLayout = { [weak coordinator = context.coordinator] map in
            coordinator?.restoreViewportBoundsIfNeeded(on: map)
        }
        return map
    }

    static func dismantleUIView(_ map: MKMapView, coordinator: Coordinator) {
        // Capture synchronously into reference storage so a full/PiP replacement
        // cannot outrun SwiftUI binding delivery. Do not write a Binding here:
        // doing so re-enters StoredLocation teardown and can trigger an
        // exclusivity trap when leaving Live View.
        coordinator.captureViewportForTransition(from: map)
        (map as? ViewportPreservingMKMapView)?.onBoundsLayout = nil
        map.delegate = nil
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        context.coordinator.onSelectAircraft = onSelectAircraft
        context.coordinator.onSelectClue = onSelectClue
        context.coordinator.onLongPressTile = onLongPressTile
        map.showsUserLocation = false
        context.coordinator.updateOperatorLocation(
            on: map,
            coordinate: operatorCoordinate,
            inset: inset,
            statusLines: operatorStatusLines
        )
        let startupCoordinates =
            [operatorCoordinate].compactMap { $0 }
            + artifacts.points.map { $0.coordinate.clCoordinate }
            + artifacts.lines.flatMap { $0.coordinates.map(\.clCoordinate) }
            + artifacts.polygons.flatMap { $0.coordinates.map(\.clCoordinate) }
        context.coordinator.rescueInitialOperationalViewport(
            on: map,
            coordinates: startupCoordinates,
            allowed: !operatorAdjustedViewport
                && focusedAircraftID == nil
        )
        let shouldFollowOperator = operatorCoordinate != nil && !inset && !followFocusedDrone && !operatorAdjustedViewport
        if shouldFollowOperator {
            if let operatorCoordinate {
                context.coordinator.setCenterAndPersist(operatorCoordinate, on: map)
            }
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
            airspaceState: airspaceState,
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
        private let viewportMemory: AppleMapViewportMemory
        @Binding private var operatorAdjustedViewport: Bool
        private var tileFingerprint = ""
        private var staticRenderState: StaticMapRenderState?
        private var aircraftRenderState: AircraftMapRenderState?
        private var operatorCoordinate: CLLocationCoordinate2D?
        private var operatorCircle: MKCircle?
        private var operatorAnnotation: OperatorDeviceAnnotation?
        private var operatorViewportRescueApplied = false
        private var updating = false
        private var currentInset = false
        private var currentFollowFocusedDrone = false
        private var currentFocusedAircraftID: String?
        private let pendingVisibleMapRect: MKMapRect?
        private var restoredViewportBounds = false
        private var regionChangeWasUserGesture = false
        var onSelectClue: (UUID) -> Void
        var onSelectAircraft: (String) -> Void
        var onLongPressTile: (Int, Int, Int) -> Void

        init(
            viewport: Binding<MKCoordinateRegion>,
            viewportMemory: AppleMapViewportMemory,
            operatorAdjustedViewport: Binding<Bool>,
            onSelectClue: @escaping (UUID) -> Void,
            onSelectAircraft: @escaping (String) -> Void,
            onLongPressTile: @escaping (Int, Int, Int) -> Void
        ) {
            _viewport = viewport
            self.viewportMemory = viewportMemory
            _operatorAdjustedViewport = operatorAdjustedViewport
            pendingVisibleMapRect = viewportMemory.visibleMapRect
            self.onSelectClue = onSelectClue
            self.onSelectAircraft = onSelectAircraft
            self.onLongPressTile = onLongPressTile
        }

        func installLongPress(on map: MKMapView) {
            let gesture = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
            gesture.minimumPressDuration = 0.5
            map.addGestureRecognizer(gesture)
        }

        @objc private func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
            guard gesture.state == .began,
                  let map = gesture.view as? MKMapView,
                  map.bounds.width > 0,
                  map.visibleMapRect.width > 0
            else { return }
            let coordinate = map.convert(gesture.location(in: map), toCoordinateFrom: map)
            let zoom = OperationalVisibleMapTile.zoomLevel(
                worldMapWidth: MKMapSize.world.width,
                visibleMapWidth: map.visibleMapRect.width,
                viewportWidth: map.bounds.width
            )
            let tile = OperationalVisibleMapTile.tile(
                latitude: coordinate.latitude,
                longitude: coordinate.longitude,
                zoom: zoom
            )
            onLongPressTile(tile.zoom, tile.x, tile.y)
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

        func updateOperatorLocation(
            on map: MKMapView,
            coordinate: CLLocationCoordinate2D?,
            inset: Bool,
            statusLines: [String]
        ) {
            currentInset = inset
            guard let coordinate, CLLocationCoordinate2DIsValid(coordinate) else {
                if let operatorCircle { map.removeOverlay(operatorCircle) }
                if let operatorAnnotation { map.removeAnnotation(operatorAnnotation) }
                operatorCircle = nil
                operatorAnnotation = nil
                operatorCoordinate = nil
                return
            }

            let movementMeters = self.operatorCoordinate.map {
                CLLocation(latitude: $0.latitude, longitude: $0.longitude).distance(
                    from: CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
                )
            } ?? .greatestFiniteMagnitude
            let insetChanged = operatorAnnotation?.inset != inset
            operatorAnnotation?.coordinate = coordinate
            operatorAnnotation?.statusLines = statusLines
            if let operatorAnnotation,
               let view = map.view(for: operatorAnnotation) as? MKMarkerAnnotationView {
                configureOperatorCallout(view, annotation: operatorAnnotation)
            }
            if movementMeters <= 5, !insetChanged, operatorCircle != nil, operatorAnnotation != nil {
                return
            }

            if let operatorCircle { map.removeOverlay(operatorCircle) }
            operatorCircle = MKCircle(
                center: coordinate,
                radius: OperationalFacilityMap.operatingRadiusStatuteMiles * 1_609.344
            )
            self.operatorCoordinate = coordinate
            if let operatorCircle { map.addOverlay(operatorCircle, level: .aboveLabels) }

            if operatorAnnotation == nil || insetChanged {
                if let operatorAnnotation { map.removeAnnotation(operatorAnnotation) }
                let annotation = OperatorDeviceAnnotation(
                    coordinate: coordinate,
                    inset: inset,
                    statusLines: statusLines
                )
                operatorAnnotation = annotation
                map.addAnnotation(annotation)
            }
        }

        func rescueInitialOperationalViewport(
            on map: MKMapView,
            coordinates: [CLLocationCoordinate2D],
            allowed: Bool
        ) {
            guard allowed,
                  !operatorViewportRescueApplied,
                  map.bounds.width > 0,
                  map.bounds.height > 0
            else { return }
            let valid = coordinates.filter(CLLocationCoordinate2DIsValid)
            guard let first = valid.first else { return }
            operatorViewportRescueApplied = true
            let points = valid.map(MKMapPoint.init)
            var rect = points.dropFirst().reduce(
                MKMapRect(origin: points[0], size: MKMapSize(width: 0, height: 0))
            ) { partial, point in
                partial.union(MKMapRect(origin: point, size: MKMapSize(width: 0, height: 0)))
            }
            if points.count > 1, rect.width > 0 || rect.height > 0 {
                if rect.width == 0 { rect = rect.insetBy(dx: -max(1, rect.height * 0.05), dy: 0) }
                if rect.height == 0 { rect = rect.insetBy(dx: 0, dy: -max(1, rect.width * 0.05)) }
                map.setVisibleMapRect(
                    rect,
                    edgePadding: UIEdgeInsets(top: 48, left: 48, bottom: 48, right: 48),
                    animated: false
                )
                viewport = map.region
                viewportMemory.region = map.region
                viewportMemory.visibleMapRect = validVisibleMapRect(from: map)
            } else {
                let span = MKCoordinateSpan(latitudeDelta: 0.08, longitudeDelta: 0.08)
                let region = MKCoordinateRegion(center: first, span: span)
                map.setRegion(region, animated: false)
                viewport = region
                viewportMemory.region = region
                viewportMemory.visibleMapRect = validVisibleMapRect(from: map)
            }
        }

        func updateOperationalOverlays(
            on map: MKMapView,
            tracks: [RidAircraftTrack],
            aircraftDisplay: [String: AircraftMapDisplay],
            altitudeDisplay: [String: OperationalAircraftAltitudeDisplay],
            clues: [OperationalClueRecord],
            artifacts: CaltopoArtifactSnapshot,
            airspaceState: OperationalAirspaceState,
            notamState: AppleNotamState,
            landRestrictionState: AppleLandRestrictionState,
            inset: Bool,
            predictiveHeadEnabled: Bool,
            focusedAircraftID: String?,
            followFocusedDrone: Bool
        ) {
            updating = true
            currentInset = inset
            currentFollowFocusedDrone = followFocusedDrone
            currentFocusedAircraftID = focusedAircraftID
            defer { updating = false }
            let nextStaticState = StaticMapRenderState(
                clues: clues,
                artifacts: artifacts,
                airspaceRecords: airspaceState.records,
                notamsEnabled: notamState.enabled,
                notamsVisible: notamState.visible,
                notices: notamState.notices,
                landRestrictionsEnabled: landRestrictionState.enabled,
                landRestrictionsVisible: landRestrictionState.visible,
                landAreas: landRestrictionState.areas,
                inset: inset
            )
            let staticChanged = nextStaticState != staticRenderState
            if staticChanged {
                staticRenderState = nextStaticState
                map.removeOverlays(map.overlays.filter { overlay in
                    guard !(overlay is CachedMapTileOverlay) else { return false }
                    return (overlay as? StyledPolyline)?.layer == .staticMap
                        || (overlay as? StyledPolygon)?.layer == .staticMap
                })
                map.removeAnnotations(map.annotations.filter { annotation in
                    (annotation as? MapLayerAnnotation)?.mapLayer == .staticMap
                })
            }

            let now = Date()
            let region = map.region
            let nextAircraftState = AircraftMapRenderState(
                tracks: tracks.map(AircraftTrackRenderInput.init),
                display: aircraftDisplay,
                altitude: altitudeDisplay,
                inset: inset,
                predictiveHeadEnabled: predictiveHeadEnabled,
                focusedAircraftID: focusedAircraftID,
                followFocusedDrone: followFocusedDrone,
                centerLatitude: region.center.latitude,
                centerLongitude: region.center.longitude,
                latitudeDelta: region.span.latitudeDelta,
                longitudeDelta: region.span.longitudeDelta,
                width: map.bounds.width,
                height: map.bounds.height,
                predictionSecond: predictiveHeadEnabled ? Int(now.timeIntervalSinceReferenceDate) : nil
            )
            let aircraftChanged = nextAircraftState != aircraftRenderState
            if aircraftChanged {
                aircraftRenderState = nextAircraftState
                map.removeOverlays(map.overlays.filter { overlay in
                    (overlay as? StyledPolyline)?.layer == .aircraft
                        || (overlay as? StyledPolygon)?.layer == .aircraft
                })
                map.removeAnnotations(map.annotations.filter { annotation in
                    (annotation as? MapLayerAnnotation)?.mapLayer == .aircraft
                })
            }

            if aircraftChanged {
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
                        StyledPolyline(
                            coordinates: coordinates,
                            count: coordinates.count,
                            color: archiveColor,
                            width: inset ? 1 : 2,
                            layer: .aircraft
                        ),
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
                            width: inset ? 2 : 4,
                            layer: .aircraft
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
                                width: inset ? 1 : 2,
                                layer: .aircraft
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
                        color: activeColor,
                        colorHex: nil,
                        mapLayer: .aircraft
                    ))
                }
            }
            if followFocusedDrone,
               let focusedAircraftID,
               let coordinate = renderCoordinates[focusedAircraftID] {
                setCenterAndPersist(coordinate, on: map)
            }
            }

            if staticChanged {
            for artifact in artifacts.points {
                map.addAnnotation(ArtifactAnnotation(
                    coordinate: artifact.coordinate.clCoordinate,
                    title: artifact.title,
                    symbol: artifact.symbol,
                    color: UIColor(hex: artifact.colorHex) ?? ArtifactAnnotation.defaultColor(for: artifact.symbol),
                    colorHex: artifact.colorHex
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
            for record in airspaceState.records {
                addFacilityMapRecord(record, to: map)
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
        }

        private func addFacilityMapRecord(_ record: OperationalFacilityMapRecord, to map: MKMapView) {
            let stroke: UIColor = record.ceilingFeet == 0 ? .systemRed : .systemOrange
            let fill = stroke.withAlphaComponent(record.ceilingFeet == 0 ? 0.22 : 0.12)
            for ring in record.rings {
                let coordinates = ring.map {
                    CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude)
                }
                guard coordinates.count >= 3 else { continue }
                map.addOverlay(
                    StyledPolygon(
                        coordinates: coordinates,
                        count: coordinates.count,
                        stroke: stroke,
                        fill: fill,
                        width: record.ceilingFeet == 0 ? 4 : 2
                    ),
                    level: .aboveLabels
                )
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
            persistViewport(from: mapView)
            regionChangeWasUserGesture = false
        }

        func mapViewDidChangeVisibleRegion(_ mapView: MKMapView) {
            guard !updating, hasActiveUserGesture(in: mapView) else { return }
            regionChangeWasUserGesture = true
            persistViewport(from: mapView)
        }

        func setCenterAndPersist(_ coordinate: CLLocationCoordinate2D, on map: MKMapView) {
            guard CLLocationCoordinate2DIsValid(coordinate) else { return }
            updating = true
            map.setCenter(coordinate, animated: false)
            updating = false
            viewport.center = coordinate
            viewportMemory.region.center = coordinate
            viewportMemory.visibleMapRect = validVisibleMapRect(from: map)
        }

        func persistViewport(from map: MKMapView) {
            guard restoredViewportBounds,
                  map.bounds.width >= 32,
                  map.bounds.height >= 32
            else { return }
            let region = map.region
            guard CLLocationCoordinate2DIsValid(region.center),
                  region.span.latitudeDelta.isFinite,
                  region.span.longitudeDelta.isFinite,
                  region.span.latitudeDelta > 0,
                  region.span.longitudeDelta > 0
            else { return }
            viewport = region
            viewportMemory.region = region
            viewportMemory.visibleMapRect = validVisibleMapRect(from: map)
        }

        func captureViewportForTransition(from map: MKMapView) {
            guard map.bounds.width >= 32,
                  map.bounds.height >= 32
            else { return }
            let region = map.region
            guard CLLocationCoordinate2DIsValid(region.center),
                  region.span.latitudeDelta.isFinite,
                  region.span.longitudeDelta.isFinite,
                  region.span.latitudeDelta > 0,
                  region.span.longitudeDelta > 0
            else { return }
            viewportMemory.region = region
            viewportMemory.visibleMapRect = validVisibleMapRect(from: map)
        }

        func restoreViewportBoundsIfNeeded(on map: MKMapView) {
            guard !restoredViewportBounds,
                  map.bounds.width >= 32,
                  map.bounds.height >= 32
            else { return }
            guard let rect = pendingVisibleMapRect,
                  rect.width.isFinite,
                  rect.height.isFinite,
                  rect.width > 0,
                  rect.height > 0
            else {
                restoredViewportBounds = true
                persistViewport(from: map)
                return
            }
            restoredViewportBounds = true
            updating = true
            map.setVisibleMapRect(rect, animated: false)
            updating = false
            persistViewport(from: map)
        }

        private func validVisibleMapRect(from map: MKMapView) -> MKMapRect? {
            let rect = map.visibleMapRect
            guard rect.width.isFinite,
                  rect.height.isFinite,
                  rect.width > 0,
                  rect.height > 0
            else { return nil }
            return rect
        }

        func mapView(_ mapView: MKMapView, regionWillChangeAnimated animated: Bool) {
            let userGesture = hasActiveUserGesture(in: mapView)
            regionChangeWasUserGesture = userGesture
            guard !(currentFollowFocusedDrone && currentFocusedAircraftID != nil),
                  userGesture
            else { return }
            operatorAdjustedViewport = true
        }

        private func hasActiveUserGesture(in view: UIView) -> Bool {
            if view.gestureRecognizers?.contains(where: {
                $0.state == .began || $0.state == .changed
            }) == true {
                return true
            }
            return view.subviews.contains(where: hasActiveUserGesture)
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let tiles = overlay as? MKTileOverlay { return MKTileOverlayRenderer(tileOverlay: tiles) }
            if let circle = overlay as? MKCircle, circle === operatorCircle {
                let renderer = MKCircleRenderer(circle: circle)
                renderer.strokeColor = .systemBlue
                renderer.fillColor = UIColor.systemBlue.withAlphaComponent(0.07)
                renderer.lineWidth = currentInset ? 1 : 2
                return renderer
            }
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
            if let operatorDevice = annotation as? OperatorDeviceAnnotation {
                let identifier = "operator-device"
                let view = (mapView.dequeueReusableAnnotationView(withIdentifier: identifier) as? MKMarkerAnnotationView)
                    ?? MKMarkerAnnotationView(annotation: operatorDevice, reuseIdentifier: identifier)
                view.annotation = operatorDevice
                view.markerTintColor = .systemBlue
                view.glyphTintColor = .white
                view.glyphImage = UIImage(systemName: "antenna.radiowaves.left.and.right")
                view.canShowCallout = true
                // Match Android: keep the tablet marker icon-only until the
                // operator selects it, then reveal the device name in the
                // standard MapKit callout.
                view.titleVisibility = .hidden
                view.subtitleVisibility = .hidden
                view.displayPriority = .required
                view.accessibilityLabel = operatorDevice.title
                configureOperatorCallout(view, annotation: operatorDevice)
                return view
            }
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

        private func configureOperatorCallout(
            _ view: MKMarkerAnnotationView,
            annotation: OperatorDeviceAnnotation
        ) {
            let stack = UIStackView()
            stack.axis = .vertical
            stack.alignment = .leading
            stack.spacing = 2
            for line in annotation.statusLines {
                let label = UILabel()
                label.font = .preferredFont(forTextStyle: .footnote)
                label.textColor = .label
                label.numberOfLines = 0
                label.text = line
                stack.addArrangedSubview(label)
            }
            stack.widthAnchor.constraint(lessThanOrEqualToConstant: 320).isActive = true
            view.detailCalloutAccessoryView = stack
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
    private static let baseSourceMaximumZ = 19
    private static let displayMaximumZ = 22
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
        maximumZ = Self.displayMaximumZ
        tileSize = CGSize(width: 256, height: 256)
    }

    init(contoursOfflineOnly offlineOnly: Bool) {
        baseLayer = nil
        contours = true
        self.offlineOnly = offlineOnly
        cacheRoot = AppleMapCachePaths.root.appendingPathComponent("usgsContours", isDirectory: true)
        super.init(urlTemplate: nil)
        minimumZ = 0
        maximumZ = Self.displayMaximumZ
        tileSize = CGSize(width: 256, height: 256)
    }

    override func url(forTilePath path: MKTileOverlayPath) -> URL {
        if let baseLayer, let url = baseLayer.tileURL(zoom: path.z, x: path.x, y: path.y) { return url }
        return AppleMapTileRequest.contourURL(zoom: path.z, x: path.x, y: path.y)
    }

    override func loadTile(at path: MKTileOverlayPath, result: @escaping (Data?, Error?) -> Void) {
        let completion = TileResultTransfer(result: result)
        let ext = contours ? "png" : (baseLayer?.fileExtension ?? "tile")
        let requestedDestination = cacheDestination(for: path, fileExtension: ext)
        if let data = usableCachedTile(at: requestedDestination) {
            completion.result(data, nil)
            return
        }

        let overzoom = contours ? nil : OperationalOverzoomTile.resolve(
            requestedZoom: path.z,
            requestedX: path.x,
            requestedY: path.y,
            sourceMaximumZoom: Self.baseSourceMaximumZ
        )
        let sourcePath = overzoom.map {
            MKTileOverlayPath(
                x: $0.sourceX,
                y: $0.sourceY,
                z: $0.sourceZoom,
                contentScaleFactor: path.contentScaleFactor
            )
        } ?? path
        let sourceDestination = cacheDestination(for: sourcePath, fileExtension: ext)
        if let sourceData = usableCachedTile(at: sourceDestination) {
            guard let output = Self.displayTileData(
                sourceData: sourceData,
                overzoom: overzoom,
                fileExtension: ext
            ) else {
                completion.result(nil, CocoaError(.fileReadCorruptFile))
                return
            }
            cache(output, at: requestedDestination)
            completion.result(output, nil)
            return
        }
        guard !offlineOnly else {
            completion.result(nil, CocoaError(.fileNoSuchFile))
            return
        }

        let requestURL = url(forTilePath: sourcePath)
        var request = URLRequest(url: requestURL)
        request.setValue("RID2Caltopo/Apple (contact: kjtsar@kjt.us)", forHTTPHeaderField: "User-Agent")
        URLSession.shared.dataTask(with: request) { data, response, error in
            guard let data, (response as? HTTPURLResponse)?.statusCode == 200,
                  AppleMapOfflineManager.dataIsUsableTile(data), !AppleBadTilePolicy.isBlocked(data)
            else {
                if let data { AppleBadTilePolicy.record(data) }
                completion.result(nil, error ?? CocoaError(.fileReadUnknown))
                return
            }
            Self.cache(data, at: sourceDestination)
            guard let output = Self.displayTileData(
                sourceData: data,
                overzoom: overzoom,
                fileExtension: ext
            ) else {
                completion.result(nil, CocoaError(.fileReadCorruptFile))
                return
            }
            Self.cache(output, at: requestedDestination)
            completion.result(output, nil)
        }.resume()
    }

    private func cacheDestination(for path: MKTileOverlayPath, fileExtension: String) -> URL {
        cacheRoot
            .appendingPathComponent(String(path.z), isDirectory: true)
            .appendingPathComponent(String(path.x), isDirectory: true)
            .appendingPathComponent("\(path.y).\(fileExtension)")
    }

    private func usableCachedTile(at destination: URL) -> Data? {
        if let data = try? Data(contentsOf: destination),
           AppleMapOfflineManager.dataIsUsableTile(data),
           !AppleBadTilePolicy.isBlocked(data) {
            return data
        }
        if FileManager.default.fileExists(atPath: destination.path) {
            if UserDefaults.standard.object(forKey: "map.autoRemoveBadTiles") as? Bool ?? true {
                try? FileManager.default.removeItem(at: destination)
            }
        }
        return nil
    }

    private static func displayTileData(
        sourceData: Data,
        overzoom: OperationalOverzoomTile?,
        fileExtension: String
    ) -> Data? {
        guard let overzoom else { return sourceData }
        guard let sourceImage = UIImage(data: sourceData) else { return nil }
        let scale = CGFloat(1 << overzoom.zoomDelta)
        let sourceSize = sourceImage.size
        let cropWidth = sourceSize.width / scale
        let cropHeight = sourceSize.height / scale
        let cropOrigin = CGPoint(
            x: CGFloat(overzoom.childX) * cropWidth,
            y: CGFloat(overzoom.childY) * cropHeight
        )
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = fileExtension == "jpg"
        let renderer = UIGraphicsImageRenderer(
            size: CGSize(width: 256, height: 256),
            format: format
        )
        let tile = renderer.image { _ in
            sourceImage.draw(in: CGRect(
                x: -cropOrigin.x * scale,
                y: -cropOrigin.y * scale,
                width: sourceSize.width * scale,
                height: sourceSize.height * scale
            ))
        }
        return fileExtension == "jpg"
            ? tile.jpegData(compressionQuality: 0.9)
            : tile.pngData()
    }

    private func cache(_ data: Data, at destination: URL) {
        Self.cache(data, at: destination)
    }

    private static func cache(_ data: Data, at destination: URL) {
        do {
            try FileManager.default.createDirectory(
                at: destination.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try data.write(to: destination, options: .atomic)
        } catch {
            AppleLog.warning("MapTiles", "Tile cache write failed: \(error.localizedDescription)")
        }
    }

}

private struct TileResultTransfer: @unchecked Sendable {
    let result: (Data?, Error?) -> Void
}

private enum OperationalMapRenderLayer {
    case staticMap
    case aircraft
    case operatorDevice
}

private protocol MapLayerAnnotation: AnyObject {
    var mapLayer: OperationalMapRenderLayer { get }
}

private final class StyledPolyline: MKPolyline {
    var color: UIColor = .systemBlue
    var width: CGFloat = 3
    var layer: OperationalMapRenderLayer = .staticMap

    convenience init(
        coordinates: [CLLocationCoordinate2D],
        count: Int,
        color: UIColor,
        width: Double,
        layer: OperationalMapRenderLayer = .staticMap
    ) {
        self.init(coordinates: coordinates, count: count)
        self.color = color
        self.width = width
        self.layer = layer
    }
}

private final class StyledPolygon: MKPolygon {
    var stroke: UIColor = .systemOrange
    var fill: UIColor = .systemOrange.withAlphaComponent(0.2)
    var width: CGFloat = 3
    var layer: OperationalMapRenderLayer = .staticMap

    convenience init(
        coordinates: [CLLocationCoordinate2D],
        count: Int,
        stroke: UIColor,
        fill: UIColor,
        width: Double,
        layer: OperationalMapRenderLayer = .staticMap
    ) {
        self.init(coordinates: coordinates, count: count)
        self.stroke = stroke
        self.fill = fill
        self.width = width
        self.layer = layer
    }
}

private final class AircraftAnnotation: NSObject, MKAnnotation, MapLayerAnnotation {
    let mapLayer = OperationalMapRenderLayer.aircraft
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

private final class OperatorDeviceAnnotation: NSObject, MKAnnotation, MapLayerAnnotation {
    let mapLayer = OperationalMapRenderLayer.operatorDevice
    @objc dynamic var coordinate: CLLocationCoordinate2D
    let title: String?
    let subtitle: String? = OperationalFacilityMap.operatingAreaLabel
    let inset: Bool
    var statusLines: [String]

    init(coordinate: CLLocationCoordinate2D, inset: Bool, statusLines: [String]) {
        self.coordinate = coordinate
        self.inset = inset
        self.statusLines = statusLines
        self.title = "R2C: \(AppleDeviceIdentity.displayName)"
        super.init()
    }
}

private final class ClueAnnotation: NSObject, MKAnnotation, MapLayerAnnotation {
    let mapLayer = OperationalMapRenderLayer.staticMap
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

private final class NotamAnnotation: NSObject, MKAnnotation, MapLayerAnnotation {
    let mapLayer = OperationalMapRenderLayer.staticMap
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

private final class ArtifactAnnotation: NSObject, MKAnnotation, MapLayerAnnotation {
    let mapLayer: OperationalMapRenderLayer
    dynamic let coordinate: CLLocationCoordinate2D
    let title: String?
    let systemSymbol: String
    let caltopoSymbol: String
    let color: UIColor
    let colorHex: String?

    init(
        coordinate: CLLocationCoordinate2D,
        title: String,
        symbol: String,
        color: UIColor,
        colorHex: String?,
        mapLayer: OperationalMapRenderLayer = .staticMap
    ) {
        self.mapLayer = mapLayer
        self.coordinate = coordinate
        self.title = title
        caltopoSymbol = symbol
        systemSymbol = Self.systemSymbol(for: symbol)
        self.color = color
        self.colorHex = colorHex
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
    private static let iconLoader = CaltopoMarkerIconDataLoader()
    private let background = UIView()
    private let imageView = UIImageView()
    private let glyphLabel = UILabel()
    private var iconTask: Task<Void, Never>?
    private var representedIconURL: URL?

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

    override func prepareForReuse() {
        super.prepareForReuse()
        iconTask?.cancel()
        iconTask = nil
        representedIconURL = nil
    }

    func configure(_ artifact: ArtifactAnnotation) {
        annotation = artifact
        iconTask?.cancel()
        background.backgroundColor = artifact.color
        let style = Self.style(for: artifact.caltopoSymbol)
        showFallback(style)

        guard case .staticMap = artifact.mapLayer else { return }
        guard let iconURL = CaltopoMarkerIcon.url(
            symbol: artifact.caltopoSymbol,
            colorHex: artifact.colorHex
        ) else { return }
        representedIconURL = iconURL
        iconTask = Task { [weak self] in
            guard let image = await Self.iconLoader.image(for: iconURL),
                  !Task.isCancelled,
                  let self,
                  self.representedIconURL == iconURL
            else { return }
            self.background.isHidden = true
            self.glyphLabel.isHidden = true
            self.imageView.frame = self.bounds
            self.imageView.tintColor = nil
            self.imageView.image = image
            self.imageView.isHidden = false
        }
    }

    private func showFallback(_ style: (systemImage: String?, glyph: String?)) {
        background.isHidden = false
        imageView.frame = CGRect(x: 8, y: 8, width: 22, height: 22)
        imageView.tintColor = .white
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
        default: return ("mappin.circle.fill", nil)
        }
    }
}

private actor CaltopoMarkerIconDataLoader {
    private let memoryCache = NSCache<NSURL, UIImage>()
    private let session: URLSession
    private let cacheDirectory: URL
    private var inFlight: [URL: Task<Data?, Never>] = [:]

    init() {
        let root = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        cacheDirectory = root.appendingPathComponent(
            "RID2Caltopo/CalTopoMarkerIcons",
            isDirectory: true
        )
        try? FileManager.default.createDirectory(
            at: cacheDirectory,
            withIntermediateDirectories: true
        )
        let configuration = URLSessionConfiguration.default
        configuration.requestCachePolicy = .returnCacheDataElseLoad
        configuration.urlCache = URLCache(
            memoryCapacity: 8 * 1_024 * 1_024,
            diskCapacity: 32 * 1_024 * 1_024
        )
        session = URLSession(configuration: configuration)
    }

    func image(for url: URL) async -> UIImage? {
        if let cached = memoryCache.object(forKey: url as NSURL) {
            return cached
        }
        let diskURL = cacheURL(for: url)
        if let data = try? Data(contentsOf: diskURL, options: .mappedIfSafe),
           let image = UIImage(data: data) {
            memoryCache.setObject(image, forKey: url as NSURL)
            return image
        }

        let task: Task<Data?, Never>
        if let existing = inFlight[url] {
            task = existing
        } else {
            let session = self.session
            task = Task {
                do {
                    let (data, response) = try await session.data(from: url)
                    guard let http = response as? HTTPURLResponse,
                          (200 ..< 300).contains(http.statusCode),
                          !data.isEmpty
                    else { return nil }
                    return data
                } catch {
                    return nil
                }
            }
            inFlight[url] = task
        }
        let data = await task.value
        inFlight[url] = nil
        guard let data, let image = UIImage(data: data) else { return nil }
        try? data.write(to: diskURL, options: .atomic)
        memoryCache.setObject(image, forKey: url as NSURL)
        return image
    }

    private func cacheURL(for url: URL) -> URL {
        let digest = SHA256.hash(data: Data(url.absoluteString.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
        return cacheDirectory.appendingPathComponent("\(digest).png")
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
            // CalTopo and Android use #AARRGGBB, not CSS #RRGGBBAA.
            self.init(
                red: CGFloat((raw >> 16) & 0xff) / 255,
                green: CGFloat((raw >> 8) & 0xff) / 255,
                blue: CGFloat(raw & 0xff) / 255,
                alpha: CGFloat((raw >> 24) & 0xff) / 255
            )
        default:
            return nil
        }
    }
}
