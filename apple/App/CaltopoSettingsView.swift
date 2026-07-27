import Foundation
import R2CCore
import Security
import SwiftUI
import UniformTypeIdentifiers

struct CaltopoSettingsView: View {
    @ObservedObject var settings: AppleCaltopoSettings
    @ObservedObject var orgSettings: AppleOrgConfigSettings
    @ObservedObject var locationProvider: AppleLocationProvider
    @ObservedObject var importer: AppleOrgConfigImporter
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    @ObservedObject var trackModel: RIDTrackViewModel
    let iCloudBackup: AppleICloudBackupCenter
    let onSave: (AppleCaltopoConfiguration) -> Void
    @ObservedObject private var notams = AppleNotamCenter.shared
    @ObservedObject private var airspace = AppleAirspaceCenter.shared
    @ObservedObject private var landRestrictions = AppleLandRestrictionCenter.shared
    @ObservedObject private var externalDisplay = AppleExternalDisplaySettings.shared
    @ObservedObject private var spokenWarnings = AppleSpokenWarningCenter.shared
    @ObservedObject private var profileLifecycle = AppleCaltopoProfileLifecycle.shared
    @AppStorage("video.captureStreams") private var captureStreams = false
    @AppStorage(AppleDeviceIdentity.storedNameKey) private var deviceName = AppleDeviceIdentity.displayName
    @State private var showingTeamMaps = false

    var body: some View {
        Form {
            Section("Incident map") {
                if settings.mapID.isEmpty {
                    Label("No CalTopo map selected", systemImage: "map")
                        .foregroundStyle(.secondary)
                } else {
                    LabeledContent("Connected Map", value: settings.mapTitle.isEmpty ? settings.mapID : settings.mapTitle)
                    LabeledContent("Map ID", value: settings.mapID)
                }
                Button {
                    showingTeamMaps = true
                } label: {
                    Label(settings.mapID.isEmpty ? "Connect to CalTopo Map" : "Switch CalTopo Map", systemImage: "map.fill")
                        .font(.headline)
                }
                .disabled(settings.teamID.isEmpty || settings.credentialID.isEmpty || settings.credentialSecret.isEmpty)
                if settings.teamID.isEmpty {
                    Text("Import the organization QR code first. It supplies the CalTopo team credential; this browser then lets you choose the incident map just as Android does.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            Section("CalTopo profiles") {
                LabeledContent(
                    "Active profile",
                    value: profileLifecycle.activeProfileID.isEmpty
                        ? "Not selected"
                        : profileLifecycle.activeProfileID
                )
                if let name = profileLifecycle.mutualAidDisplayName {
                    LabeledContent("Mutual aid", value: name)
                    if let expiresAt = profileLifecycle.mutualAidExpiresAt {
                        LabeledContent("Expires", value: expiresAt.formatted(date: .abbreviated, time: .shortened))
                    }
                } else {
                    Text("No mutual-aid profile installed")
                        .foregroundStyle(.secondary)
                }
                Text("Home credentials are preserved securely in Keychain. Imported mutual-aid access is removed at expiry and the app falls back to the home profile, matching Android.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Publishing") {
                Toggle("Enable live CalTopo publishing", isOn: $settings.enabled)
                TextField("Domain", text: $settings.domainAndPort)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Map ID", text: $settings.mapID)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            Section("This device") {
                TextField("Device Name", text: $deviceName)
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled()
                Text("Used for this iPad's R2C map marker, Map Folders item, tracker identity, and local track metadata. iOS does not expose the local Bluetooth adapter name, so set this once if Apple reports only “iPad.”")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Video") {
                Toggle("Capture Streams", isOn: $captureStreams)
                Text("When enabled, incoming streams are recorded as fMP4 under Files > RID2Caltopo > CapturedStreams. Changing this setting restarts the local media server.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Team credential") {
                TextField("Credential ID", text: $settings.credentialID)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Credential secret", text: $settings.credentialSecret)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Text("The credential secret is stored in the Apple Keychain. Publishing is off by default and no request is made until enabled.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Traffic safety") {
                Toggle(
                    "Standalone R2C coordination",
                    isOn: Binding(
                        get: { orgSettings.standaloneR2CCoordinationEnabled },
                        set: { orgSettings.setStandaloneR2CCoordinationEnabled($0) }
                    )
                )
                Text("Allows tracker ownership and confirmation coordination when no CalTopo map is connected, matching Android.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Toggle(
                    "Predictive Head",
                    isOn: Binding(
                        get: { orgSettings.predictiveHeadEnabled },
                        set: { enabled in
                            orgSettings.setPredictiveHeadEnabled(enabled)
                        }
                    )
                )
                Stepper(
                    "Proximity spacing: \(orgSettings.proximityAlertSpacingFeet) ft",
                    value: Binding(
                        get: { orgSettings.proximityAlertSpacingFeet },
                        set: { orgSettings.setProximityAlertSpacingFeet($0) }
                    ),
                    in: 1 ... 1_000
                )
                Stepper(
                    "Min Dist: \(orgSettings.minimumTrackDistanceFeet) ft",
                    value: Binding(
                        get: { orgSettings.minimumTrackDistanceFeet },
                        set: { orgSettings.setMinimumTrackDistanceFeet($0) }
                    ),
                    in: 2 ... 1_000
                )
                Stepper(
                    "New Track Delay: \(orgSettings.newTrackDelaySeconds) s",
                    value: Binding(
                        get: { orgSettings.newTrackDelaySeconds },
                        set: { orgSettings.setNewTrackDelaySeconds($0) }
                    ),
                    in: 1 ... 600
                )
                Stepper(
                    "Bridge Check Distance: \(orgSettings.bridgeCheckDistanceFeet) ft",
                    value: Binding(
                        get: { orgSettings.bridgeCheckDistanceFeet },
                        set: { orgSettings.setBridgeCheckDistanceFeet($0) }
                    ),
                    in: 1 ... 1_000
                )
                Stepper(
                    "Max App Idle Time: \(orgSettings.maximumIdleMinutes) min",
                    value: Binding(
                        get: { orgSettings.maximumIdleMinutes },
                        set: { orgSettings.setMaximumIdleMinutes($0) }
                    ),
                    in: 0 ... 1_440
                )
                Text("Track filtering, loss timing, bridge distance, and proximity spacing use the same operator-adjustable controls as Android. The idle value is retained for configuration parity; iOS does not permit an app to terminate itself, so the system remains responsible for suspending an idle app.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading) {
                    Text("Audio Alarm Volume: \(spokenWarnings.volumePercent)%")
                    Slider(
                        value: Binding(
                            get: { Double(spokenWarnings.volumePercent) },
                            set: { spokenWarnings.setVolumePercent(Int($0.rounded())) }
                        ),
                        in: 0 ... 100,
                        step: 5
                    )
                    Button("Audio Alarm Test") {
                        spokenWarnings.requestAudioAlarmTest()
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            Section("NOTAM / TFR") {
                Toggle("Enable FAA controlled-airspace lookup", isOn: $airspace.enabled)
                Toggle("Refresh controlled airspace automatically", isOn: $airspace.autoRefresh)
                    .disabled(!airspace.enabled)
                Toggle("Enable nearby NOTAM monitoring", isOn: $notams.enabled)
                Toggle("Show NOTAMs on map", isOn: $notams.showOnMap)
                    .disabled(!notams.enabled)
                Toggle("Refresh automatically", isOn: $notams.autoRefresh)
                    .disabled(!notams.enabled)
                Stepper("Query radius: \(notams.radiusNM) NM", value: $notams.radiusNM, in: 1 ... 100)
                    .disabled(!notams.enabled)
                Picker("Refresh interval", selection: $notams.refreshIntervalSeconds) {
                    Text("1 minute").tag(60)
                    Text("5 minutes").tag(300)
                    Text("15 minutes").tag(900)
                }
                .disabled(!notams.enabled || !notams.autoRefresh)
                LabeledContent("FAA credentials", value: orgSettings.faaConfiguration == nil ? "Not loaded" : "Loaded")
                Text("Controlled-airspace status uses the public FAA UAS Facility Map for Android's one-mile operating area. NOTAM/TFR queries use imported FAA credentials.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Land / agency restrictions") {
                Toggle("Enable protected-land checks", isOn: $landRestrictions.enabled)
                Toggle("Show protected lands on map", isOn: $landRestrictions.showOnMap)
                    .disabled(!landRestrictions.enabled)
                Toggle("Refresh protected lands automatically", isOn: $landRestrictions.autoRefresh)
                    .disabled(!landRestrictions.enabled)
                Stepper(
                    "Boundary query radius: \(landRestrictions.radiusNM) NM",
                    value: $landRestrictions.radiusNM,
                    in: 1 ... 50
                )
                .disabled(!landRestrictions.enabled)
                Text("Checks National Park Service, National Wildlife Refuge, U.S. Forest Service wilderness, and Colorado Parks and Wildlife boundaries. Results distinguish land-use rules from FAA airspace restrictions and include agency follow-up links.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("External display") {
                Picker("Mode", selection: $externalDisplay.mode) {
                    ForEach(AppleExternalDisplayMode.allCases) { mode in Text(mode.label).tag(mode.rawValue) }
                }
                Picker("Content", selection: $externalDisplay.content) {
                    ForEach(AppleExternalDisplayContent.allCases) { content in Text(content.label).tag(content.rawValue) }
                }
                .disabled(externalDisplay.mode != AppleExternalDisplayMode.appManaged.rawValue)
                Picker("Alert routing", selection: $externalDisplay.alertRouting) {
                    ForEach(AppleExternalAlertRouting.allCases) { routing in Text(routing.label).tag(routing.rawValue) }
                }
                Toggle("Open automatically when connected", isOn: $externalDisplay.autoOpen)
                Toggle("Allow interaction", isOn: $externalDisplay.allowInteraction)
                    .disabled(externalDisplay.mode != AppleExternalDisplayMode.appManaged.rawValue)
                Text("OS mirroring uses the system display controls. App-managed mode presents the selected streams/map layout independently on an attached display.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Advanced") {
                NavigationLink {
                    AppleDeveloperToolsView(
                        caltopo: settings,
                        organization: orgSettings,
                        locationProvider: locationProvider,
                        importer: importer,
                        identities: identityStore,
                        trackModel: trackModel,
                        iCloudBackup: iCloudBackup
                    )
                } label: {
                    Label("Developer Tools", systemImage: "hammer")
                }
            }
            Section {
                Button("Save CalTopo Configuration") {
                    onSave(settings.save())
                }
                Text(settings.status)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Settings")
        .sheet(isPresented: $showingTeamMaps) {
            CaltopoTeamMapBrowser(settings: settings) { map in
                orgSettings.setIncidentMapTitle(map.title)
                onSave(settings.selectMap(map))
                showingTeamMaps = false
            }
        }
    }
}

@MainActor
private final class AppleDeveloperToolsManager: ObservableObject {
    @Published var status = "Ready"
    @Published private(set) var exportURL: URL?
    @Published private(set) var isWorking = false

    func prepareOrgConfig(
        caltopo: AppleCaltopoSettings,
        organization: AppleOrgConfigSettings,
        identities: AppleDroneConfirmationStore
    ) {
        isWorking = true
        defer { isWorking = false }
        do {
            let ridMap: [String: Any] = [
                "type": "ct_ridmap",
                "file_version": "1.0",
                "load_type": "replace",
                "map": identities.importedMappings.map {
                    [
                        "remoteId": $0.remoteID,
                        "mappedId": $0.mappedID,
                        "org": $0.organization,
                        "model": $0.droneDescription,
                        "owner": $0.pilotCallsign,
                    ]
                },
            ]
            var credentials: [String: Any] = [
                "type": "ct_credentials",
                "file_version": "1.0",
                "org_name": organization.organizationName,
                "team_id": caltopo.teamID,
                "credential_id": caltopo.credentialID,
                "credential_secret": caltopo.credentialSecret,
                "domain_and_port": caltopo.domainAndPort,
                "track_folder": organization.trackFolder,
                "incident": organization.incident,
                "op_period": organization.operationalPeriod,
                "tracker_api_key": organization.trackerAPIKey,
                "tracker_url_pfx": organization.trackerURLPrefix,
                "tracker_url_prefix": organization.trackerURLPrefix,
                "use_peers": organization.usePeers,
                "predictive_head_enabled": organization.predictiveHeadEnabled,
                "proximity_alert_spacing_feet": organization.proximityAlertSpacingFeet,
            ]
            credentials = credentials.filter { value in
                if let text = value.value as? String { return !text.isEmpty }
                return true
            }
            let credentialData = try JSONSerialization.data(
                withJSONObject: credentials,
                options: [.sortedKeys]
            )
            let credentialText = String(decoding: credentialData, as: UTF8.self)
            var configs: [[String: Any]] = [
                ridMap,
                [
                    "type": "ct_credentials_enc",
                    "enc": OrgConfigTokenCodec.encryptPayload(credentialText),
                ],
            ]
            if let faa = organization.faaConfiguration {
                let faaObject: [String: Any] = [
                    "type": "ct_faa_credentials",
                    "source_label": faa.sourceLabel,
                    "notam_api_base_url": faa.apiBaseURL,
                    "notam_token_url": faa.tokenURL,
                    "notam_client_id": faa.clientID,
                    "notam_client_secret": faa.clientSecret,
                    "notam_scope": faa.scope,
                ]
                let faaData = try JSONSerialization.data(withJSONObject: faaObject, options: [.sortedKeys])
                configs.append([
                    "type": "ct_faa_credentials_enc",
                    "enc": OrgConfigTokenCodec.encryptPayload(
                        String(decoding: faaData, as: UTF8.self)
                    ),
                ])
            }
            let bundle: [String: Any] = [
                "format": "rid2caltopo_org_config",
                "version": 1,
                "org_name": organization.organizationName,
                "generated": ISO8601DateFormatter().string(from: Date()),
                "configs": configs,
            ]
            let data = try JSONSerialization.data(
                withJSONObject: bundle,
                options: [.prettyPrinted, .sortedKeys]
            )
            let root = (FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
                ?? FileManager.default.temporaryDirectory)
                .appendingPathComponent("RID2Caltopo/Exports", isDirectory: true)
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            let safeOrg = organization.organizationName
                .replacingOccurrences(of: "/", with: "-")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let name = safeOrg.isEmpty ? "RID2Caltopo_Org_Config.json" : "\(safeOrg)_Org_Config.json"
            let destination = root.appendingPathComponent(name)
            try data.write(to: destination, options: .atomic)
            exportURL = destination
            status = "Android-compatible organization config ready to share."
        } catch {
            status = "Organization export failed: \(error.localizedDescription)"
        }
    }

    func resetPersistedState(
        caltopo: AppleCaltopoSettings,
        organization: AppleOrgConfigSettings,
        identities: AppleDroneConfirmationStore,
        locationProvider: AppleLocationProvider,
        iCloudBackup: AppleICloudBackupCenter
    ) {
        if let bundleID = Bundle.main.bundleIdentifier {
            UserDefaults.standard.removePersistentDomain(forName: bundleID)
        }
        SecItemDelete([kSecClass as String: kSecClassGenericPassword] as CFDictionary)
        caltopo.resetPersistedState()
        organization.resetPersistedState()
        identities.resetPersistedState()
        locationProvider.clearLocationOverride()
        iCloudBackup.setEnabled(false, passphrase: "")
        status = "Persisted app state reset. Quit and reopen RID2Caltopo to rebuild all runtime settings."
        AppleLog.warning("DeveloperTools", "Persisted app state reset")
    }
}

private struct AppleDeveloperToolsView: View {
    @ObservedObject var caltopo: AppleCaltopoSettings
    @ObservedObject var organization: AppleOrgConfigSettings
    @ObservedObject var locationProvider: AppleLocationProvider
    @ObservedObject var importer: AppleOrgConfigImporter
    @ObservedObject var identities: AppleDroneConfirmationStore
    @ObservedObject var trackModel: RIDTrackViewModel
    let iCloudBackup: AppleICloudBackupCenter
    @StateObject private var manager = AppleDeveloperToolsManager()
    @State private var importingConfig = false
    @State private var locationText = ""
    @State private var locationError: String?
    @State private var recentDays = 2
    @State private var resubmitting = false
    @State private var archiveDirectories: [AppleArchiveDirectoryOption] = []
    @State private var selectedArchiveDirectories: Set<String> = []
    @State private var loadingArchiveDirectories = false
    @State private var showingArchiveDeleteConfirmation = false
    @State private var showingResetConfirmation = false

    var body: some View {
        Form {
            Section("Configuration") {
                Button("Load Config File", systemImage: "doc.badge.plus") {
                    importingConfig = true
                }
                Button("Export Org Config", systemImage: "square.and.arrow.up") {
                    manager.prepareOrgConfig(
                        caltopo: caltopo,
                        organization: organization,
                        identities: identities
                    )
                }
                if let exportURL = manager.exportURL {
                    ShareLink(item: exportURL) {
                        Label("Share Prepared Org Config", systemImage: "square.and.arrow.up")
                    }
                }
                Text(importer.statusText)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Tracker archive") {
                Stepper("Recent days: \(recentDays)", value: $recentDays, in: 1 ... 30)
                Button(resubmitting ? "Resubmitting…" : "Resubmit Recent Tracks To Tracker") {
                    resubmitting = true
                    Task {
                        manager.status = await trackModel.resubmitRecentTracks(days: recentDays)
                        resubmitting = false
                    }
                }
                .disabled(resubmitting)
                Text("Clears the reported marker for the selected recent day folders and submits eligible team-drone tracks again.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Local track archives") {
                Button(loadingArchiveDirectories ? "Loading…" : "Load Archive Folders") {
                    loadArchiveDirectories()
                }
                .disabled(loadingArchiveDirectories)
                ForEach(archiveDirectories) { directory in
                    Toggle(
                        isOn: Binding(
                            get: { selectedArchiveDirectories.contains(directory.name) },
                            set: { selected in
                                if selected {
                                    selectedArchiveDirectories.insert(directory.name)
                                } else {
                                    selectedArchiveDirectories.remove(directory.name)
                                }
                            }
                        )
                    ) {
                        VStack(alignment: .leading) {
                            Text(directory.name + (directory.isToday ? " • Today" : ""))
                            Text(
                                "\(directory.fileCount) files • \(ByteCountFormatter.string(fromByteCount: directory.byteCount, countStyle: .file))"
                            )
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        }
                    }
                    .disabled(directory.isToday)
                }
                Button("Delete Selected Archive Folders", role: .destructive) {
                    showingArchiveDeleteConfirmation = true
                }
                .disabled(selectedArchiveDirectories.isEmpty)
                Text("Deletes only selected dated folders under Files > RID2Caltopo > Tracks. Today’s active folder cannot be selected.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Simulate MyLocation") {
                TextField("Latitude, longitude", text: $locationText)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Apply Temporary Location") { applyLocationOverride() }
                Button("Clear Location Override", role: .destructive) {
                    locationProvider.clearLocationOverride()
                    locationText = ""
                    locationError = nil
                }
                .disabled(locationProvider.locationOverride == nil)
                if let override = locationProvider.locationOverride {
                    LabeledContent(
                        "Active override",
                        value: String(
                            format: "%.6f, %.6f",
                            override.coordinate.latitude,
                            override.coordinate.longitude
                        )
                    )
                }
                if let locationError {
                    Text(locationError).foregroundStyle(.red)
                }
                Text("The override is temporary and drives iOS airspace, NOTAM/TFR, protected-land, map-marker, and proximity checks until cleared or the app exits.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Peer coordination") {
                Toggle(
                    "Disable Peer Coordination",
                    isOn: Binding(
                        get: { !organization.usePeers },
                        set: { organization.setUsePeers(!$0) }
                    )
                )
                Text("Disable only for isolated testing. Multiple standalone instances can publish duplicate CalTopo updates.")
                    .font(.footnote)
                    .foregroundStyle(.red)
            }

            Section("Persistent state") {
                Button("Reset Persisted App State", role: .destructive) {
                    showingResetConfirmation = true
                }
                Text("Clears saved configuration, mappings, preferences, and app Keychain secrets. Local tracks, clues, logs, captured video, and cached maps are retained.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Status") {
                if manager.isWorking || resubmitting { ProgressView() }
                Text(manager.status).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Developer Tools")
        .fileImporter(
            isPresented: $importingConfig,
            allowedContentTypes: [.json, .plainText, .data]
        ) { result in
            guard case let .success(url) = result else { return }
            Task {
                await importer.importFile(
                    url,
                    caltopoSettings: caltopo,
                    orgSettings: organization,
                    identityStore: identities
                )
            }
        }
        .confirmationDialog(
            "Delete selected archive folders?",
            isPresented: $showingArchiveDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                let selected = selectedArchiveDirectories
                Task {
                    manager.status = await trackModel.deleteLocalArchiveDirectories(selected)
                    selectedArchiveDirectories.removeAll()
                    loadArchiveDirectories()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This permanently removes \(selectedArchiveDirectories.count) local track archive folder(s).")
        }
        .alert("Reset Persisted App State?", isPresented: $showingResetConfirmation) {
            Button("Cancel", role: .cancel) {}
            Button("Reset", role: .destructive) {
                manager.resetPersistedState(
                    caltopo: caltopo,
                    organization: organization,
                    identities: identities,
                    locationProvider: locationProvider,
                    iCloudBackup: iCloudBackup
                )
            }
        } message: {
            Text("This clears saved settings and credentials. Local operational files are retained. You must quit and reopen the app afterward.")
        }
        .onAppear {
            if let override = locationProvider.locationOverride {
                locationText = String(
                    format: "%.6f, %.6f",
                    override.coordinate.latitude,
                    override.coordinate.longitude
                )
            }
        }
    }

    private func applyLocationOverride() {
        let fields = locationText
            .split(separator: ",", maxSplits: 1)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        guard fields.count == 2,
              let latitude = Double(fields[0]),
              let longitude = Double(fields[1]),
              (-90 ... 90).contains(latitude),
              (-180 ... 180).contains(longitude)
        else {
            locationError = "Enter decimal latitude and longitude separated by a comma."
            return
        }
        locationProvider.setLocationOverride(latitude: latitude, longitude: longitude)
        locationError = nil
        manager.status = "Temporary MyLocation override applied."
    }

    private func loadArchiveDirectories() {
        loadingArchiveDirectories = true
        Task {
            archiveDirectories = await trackModel.localArchiveDirectories()
            selectedArchiveDirectories.formIntersection(
                archiveDirectories.filter { !$0.isToday }.map(\.name)
            )
            loadingArchiveDirectories = false
        }
    }
}

struct CaltopoTeamMapBrowser: View {
    @ObservedObject var settings: AppleCaltopoSettings
    let onSelect: (CaltopoTeamMap) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var navigationStack: [[CaltopoTeamMapNode]] = []
    @State private var search = ""

    private var currentItems: [CaltopoTeamMapNode] {
        navigationStack.last ?? settings.teamMaps
    }

    private var filteredItems: [CaltopoTeamMapNode] {
        guard !search.isEmpty else { return currentItems }
        return currentItems.filter { $0.title.localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        NavigationStack {
            Group {
                if settings.isLoadingTeamMaps {
                    ProgressView("Loading team maps…")
                } else if settings.teamMaps.isEmpty {
                    ContentUnavailableView(
                        "Team Maps Unavailable",
                        systemImage: "map",
                        description: Text(settings.status)
                    )
                } else {
                    List {
                        if !navigationStack.isEmpty {
                            Button {
                                _ = navigationStack.popLast()
                                search = ""
                            } label: {
                                Label("Back", systemImage: "chevron.left")
                            }
                        }
                        ForEach(filteredItems) { node in
                            Button {
                                if let children = node.children {
                                    navigationStack.append(children)
                                    search = ""
                                } else if let map = node.map {
                                    onSelect(map)
                                }
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: node.children == nil ? "mappin.and.ellipse" : "folder.fill")
                                        .foregroundStyle(node.children == nil ? Color.accentColor : Color.orange)
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(node.title).foregroundStyle(.primary)
                                        if let map = node.map {
                                            Text(map.updatedMilliseconds > 0
                                                 ? Date(timeIntervalSince1970: Double(map.updatedMilliseconds) / 1_000).formatted()
                                                 : "Date unknown")
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    Spacer()
                                    if node.children != nil { Image(systemName: "chevron.right").foregroundStyle(.secondary) }
                                }
                            }
                        }
                    }
                    .searchable(text: $search, prompt: "Search maps")
                }
            }
            .navigationTitle("Team Maps")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button { Task { await settings.loadTeamMaps() } } label: { Image(systemName: "arrow.clockwise") }
                        .disabled(settings.isLoadingTeamMaps)
                }
            }
            .task {
                if settings.teamMaps.isEmpty { await settings.loadTeamMaps() }
            }
        }
    }
}
