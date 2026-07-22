import R2CCore
import SwiftUI

struct CaltopoSettingsView: View {
    @ObservedObject var settings: AppleCaltopoSettings
    @ObservedObject var orgSettings: AppleOrgConfigSettings
    let onSave: (AppleCaltopoConfiguration) -> Void
    @ObservedObject private var notams = AppleNotamCenter.shared
    @ObservedObject private var airspace = AppleAirspaceCenter.shared
    @ObservedObject private var landRestrictions = AppleLandRestrictionCenter.shared
    @ObservedObject private var externalDisplay = AppleExternalDisplaySettings.shared
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
            Section("Publishing") {
                Toggle("Enable live CalTopo publishing", isOn: $settings.enabled)
                TextField("Domain", text: $settings.domainAndPort)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Map ID", text: $settings.mapID)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
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
                    "Predictive Head",
                    isOn: Binding(
                        get: { orgSettings.predictiveHeadEnabled },
                        set: { enabled in
                            orgSettings.setPredictiveHeadEnabled(enabled)
                        }
                    )
                )
                LabeledContent(
                    "Proximity spacing",
                    value: "\(orgSettings.proximityAlertSpacingFeet) ft"
                )
                Text("Predictive Head projects the latest aircraft motion forward by up to two seconds, matching Android. The spacing threshold comes from the imported organization configuration.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
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

private struct CaltopoTeamMapBrowser: View {
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
