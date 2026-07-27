import Foundation
import R2CCore
import Security
import SwiftUI
import Vision
import VisionKit

@MainActor
final class AppleOrgConfigSettings: ObservableObject {
    @Published private(set) var organizationName: String
    @Published private(set) var incident: String
    @Published private(set) var operationalPeriod: String
    @Published private(set) var trackFolder: String
    @Published private(set) var teamID: String
    @Published private(set) var trackerURLPrefix: String
    @Published private(set) var usePeers: Bool
    @Published private(set) var predictiveHeadEnabled: Bool
    @Published private(set) var proximityAlertSpacingFeet: Int
    @Published private(set) var sourceDescription: String

    private let defaults: UserDefaults
    private static let keychainService = "org.ncssar.RID2CaltopoApple.org-config"
    private static let trackerAccount = "tracker-api-key"
    private static let faaClientIDAccount = "faa-client-id"
    private static let faaClientSecretAccount = "faa-client-secret"
    private static let mutualAidCredentialIDAccount = "mutual-aid-credential-id"
    private static let mutualAidCredentialSecretAccount = "mutual-aid-credential-secret"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        organizationName = defaults.string(forKey: "org.name") ?? ""
        // Incident and operational period are session choices on Android.
        // Begin every process with Android's defaults instead of restoring the
        // previous operational session.
        incident = "Training"
        operationalPeriod = "1"
        trackFolder = Self.normalizedTrackFolder(defaults.string(forKey: "org.trackFolder"))
        teamID = defaults.string(forKey: "org.teamID") ?? ""
        trackerURLPrefix = defaults.string(forKey: "org.trackerURLPrefix") ?? ""
        usePeers = defaults.object(forKey: "org.usePeers") as? Bool ?? true
        predictiveHeadEnabled = defaults.object(forKey: "org.predictiveHead") as? Bool ?? true
        proximityAlertSpacingFeet = defaults.object(forKey: "org.proximityFeet") as? Int ?? 40
        sourceDescription = defaults.string(forKey: "org.sourceDescription") ?? "Not loaded"
        defaults.set("Training", forKey: "org.incident")
        defaults.set("1", forKey: "org.operationalPeriod")
    }

    var trackerAPIKey: String { Self.loadTrackerAPIKey() ?? "" }

    var faaConfiguration: FaaSharedConfig? {
        let clientID = Self.loadSecret(account: Self.faaClientIDAccount) ?? ""
        let clientSecret = Self.loadSecret(account: Self.faaClientSecretAccount) ?? ""
        guard !clientID.isEmpty, !clientSecret.isEmpty else { return nil }
        return FaaSharedConfig(
            sourceLabel: defaults.string(forKey: "faa.sourceLabel") ?? "FAA NOTAM credentials",
            apiBaseURL: defaults.string(forKey: "faa.apiBaseURL") ?? "",
            tokenURL: defaults.string(forKey: "faa.tokenURL") ?? "",
            clientID: clientID,
            clientSecret: clientSecret,
            scope: defaults.string(forKey: "faa.scope") ?? ""
        )
    }

    var mutualAidTemplate: MutualAidTemplateCredentials? {
        let credentialID = Self.loadSecret(account: Self.mutualAidCredentialIDAccount) ?? ""
        let credentialSecret = Self.loadSecret(account: Self.mutualAidCredentialSecretAccount) ?? ""
        guard !credentialID.isEmpty, !credentialSecret.isEmpty else { return nil }
        return MutualAidTemplateCredentials(
            teamID: defaults.string(forKey: "mutualAid.template.teamID") ?? "",
            credentialID: credentialID,
            credentialSecret: credentialSecret,
            domainAndPort: defaults.string(forKey: "mutualAid.template.domainAndPort") ?? "caltopo.com",
            sourceLabel: defaults.string(forKey: "mutualAid.template.sourceLabel") ?? organizationName,
            targetFolderHint: defaults.string(forKey: "mutualAid.template.targetFolderHint") ?? "MAI"
        )
    }

    func apply(bundle: OrgConfigBundle, normalizedToken: String) throws {
        let credentials = bundle.credentials
        organizationName = credentials?.organizationName.isEmpty == false
            ? credentials?.organizationName ?? bundle.organizationName
            : bundle.organizationName
        incident = credentials?.incident ?? ""
        operationalPeriod = credentials?.operationalPeriod ?? ""
        trackFolder = Self.normalizedTrackFolder(credentials?.trackFolder)
        teamID = credentials?.teamID ?? ""
        trackerURLPrefix = credentials?.trackerURLPrefix ?? ""
        usePeers = credentials?.usePeers ?? true
        predictiveHeadEnabled = credentials?.predictiveHeadEnabled ?? true
        proximityAlertSpacingFeet = credentials?.proximityAlertSpacingFeet ?? 40
        sourceDescription = "Android QR • \(organizationName.isEmpty ? "Unnamed org" : organizationName)"

        defaults.set(organizationName, forKey: "org.name")
        defaults.set(incident, forKey: "org.incident")
        defaults.set(operationalPeriod, forKey: "org.operationalPeriod")
        defaults.set(trackFolder, forKey: "org.trackFolder")
        defaults.set(teamID, forKey: "org.teamID")
        defaults.set(trackerURLPrefix, forKey: "org.trackerURLPrefix")
        defaults.set(usePeers, forKey: "org.usePeers")
        defaults.set(predictiveHeadEnabled, forKey: "org.predictiveHead")
        defaults.set(proximityAlertSpacingFeet, forKey: "org.proximityFeet")
        defaults.set(sourceDescription, forKey: "org.sourceDescription")
        defaults.set(normalizedToken, forKey: "org.joinToken")
        try Self.storeTrackerAPIKey(credentials?.trackerAPIKey ?? "")
    }

    func apply(mutualAid profile: MutualAidSharedProfile, normalizedToken: String) throws {
        organizationName = profile.sourceLabel
        incident = profile.incident
        operationalPeriod = profile.operationalPeriod
        trackFolder = Self.normalizedTrackFolder(profile.trackFolder)
        teamID = profile.teamID
        trackerURLPrefix = profile.trackerURLPrefix
        usePeers = true
        predictiveHeadEnabled = true
        sourceDescription = "Android MA QR • \(profile.displayName)"
        defaults.set(organizationName, forKey: "org.name")
        defaults.set(incident, forKey: "org.incident")
        defaults.set(operationalPeriod, forKey: "org.operationalPeriod")
        defaults.set(trackFolder, forKey: "org.trackFolder")
        defaults.set(teamID, forKey: "org.teamID")
        defaults.set(trackerURLPrefix, forKey: "org.trackerURLPrefix")
        defaults.set(usePeers, forKey: "org.usePeers")
        defaults.set(predictiveHeadEnabled, forKey: "org.predictiveHead")
        defaults.set(sourceDescription, forKey: "org.sourceDescription")
        defaults.set(normalizedToken, forKey: "org.mutualAidToken")
        defaults.set(profile.expiresAtEpochMilliseconds, forKey: "org.mutualAidExpiresAt")
        try Self.storeSecret(profile.trackerAPIKey, account: Self.trackerAccount)
    }

    func setPredictiveHeadEnabled(_ enabled: Bool) {
        predictiveHeadEnabled = enabled
        defaults.set(enabled, forKey: "org.predictiveHead")
    }

    func setUsePeers(_ enabled: Bool) {
        usePeers = enabled
        defaults.set(enabled, forKey: "org.usePeers")
        AppleLog.info("TrackerPeer", "Peer coordination setting enabled=\(enabled)")
    }

    func resetPersistedState() {
        organizationName = ""
        incident = "Training"
        operationalPeriod = "1"
        trackFolder = "Drone Tracks"
        teamID = ""
        trackerURLPrefix = ""
        usePeers = true
        predictiveHeadEnabled = true
        proximityAlertSpacingFeet = 40
        sourceDescription = "Not loaded"
        for account in [
            Self.trackerAccount,
            Self.faaClientIDAccount,
            Self.faaClientSecretAccount,
            Self.mutualAidCredentialIDAccount,
            Self.mutualAidCredentialSecretAccount,
        ] {
            try? Self.storeSecret("", account: account)
        }
    }

    private static func normalizedTrackFolder(_ value: String?) -> String {
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? "Drone Tracks" : trimmed
    }

    func setIncidentMapTitle(_ title: String) {
        let value = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return }
        incident = value
        defaults.set(value, forKey: "org.incident")
    }

    func setIncident(_ value: String) {
        incident = value.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(incident, forKey: "org.incident")
    }

    func setOperationalPeriod(_ value: String) {
        operationalPeriod = value.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(operationalPeriod, forKey: "org.operationalPeriod")
    }

    func transferSnapshot() -> [String: Any] {
        var value: [String: Any] = [
            "organization_name": organizationName,
            "incident": incident,
            "operational_period": operationalPeriod,
            "track_folder": trackFolder,
            "team_id": teamID,
            "tracker_url_prefix": trackerURLPrefix,
            "tracker_api_key": trackerAPIKey,
            "use_peers": usePeers,
            "predictive_head": predictiveHeadEnabled,
            "proximity_feet": proximityAlertSpacingFeet,
            "source_description": sourceDescription,
        ]
        if let faaConfiguration {
            value["faa"] = [
                "source_label": faaConfiguration.sourceLabel,
                "api_base_url": faaConfiguration.apiBaseURL,
                "token_url": faaConfiguration.tokenURL,
                "client_id": faaConfiguration.clientID,
                "client_secret": faaConfiguration.clientSecret,
                "scope": faaConfiguration.scope,
            ]
        }
        if let mutualAidTemplate {
            value["mutual_aid_template"] = [
                "team_id": mutualAidTemplate.teamID,
                "credential_id": mutualAidTemplate.credentialID,
                "credential_secret": mutualAidTemplate.credentialSecret,
                "domain_and_port": mutualAidTemplate.domainAndPort,
                "source_label": mutualAidTemplate.sourceLabel,
                "target_folder_hint": mutualAidTemplate.targetFolderHint,
            ]
        }
        return value
    }

    func applyTransferSnapshot(_ object: [String: Any]) throws {
        organizationName = object["organization_name"] as? String ?? ""
        incident = object["incident"] as? String ?? ""
        operationalPeriod = object["operational_period"] as? String ?? ""
        trackFolder = Self.normalizedTrackFolder(object["track_folder"] as? String)
        teamID = object["team_id"] as? String ?? ""
        trackerURLPrefix = object["tracker_url_prefix"] as? String ?? ""
        usePeers = (object["use_peers"] as? NSNumber)?.boolValue ?? true
        predictiveHeadEnabled = (object["predictive_head"] as? NSNumber)?.boolValue ?? true
        proximityAlertSpacingFeet = (object["proximity_feet"] as? NSNumber)?.intValue ?? 40
        sourceDescription = object["source_description"] as? String ?? "Local backup"
        defaults.set(organizationName, forKey: "org.name")
        defaults.set(incident, forKey: "org.incident")
        defaults.set(operationalPeriod, forKey: "org.operationalPeriod")
        defaults.set(trackFolder, forKey: "org.trackFolder")
        defaults.set(teamID, forKey: "org.teamID")
        defaults.set(trackerURLPrefix, forKey: "org.trackerURLPrefix")
        defaults.set(usePeers, forKey: "org.usePeers")
        defaults.set(predictiveHeadEnabled, forKey: "org.predictiveHead")
        defaults.set(proximityAlertSpacingFeet, forKey: "org.proximityFeet")
        defaults.set(sourceDescription, forKey: "org.sourceDescription")
        try Self.storeSecret(object["tracker_api_key"] as? String ?? "", account: Self.trackerAccount)
        if let faa = object["faa"] as? [String: Any] {
            try applyEmbedded(faa: FaaSharedConfig(
                sourceLabel: faa["source_label"] as? String ?? "",
                apiBaseURL: faa["api_base_url"] as? String ?? "",
                tokenURL: faa["token_url"] as? String ?? "",
                clientID: faa["client_id"] as? String ?? "",
                clientSecret: faa["client_secret"] as? String ?? "",
                scope: faa["scope"] as? String ?? ""
            ))
        }
        if let template = object["mutual_aid_template"] as? [String: Any] {
            try apply(mutualAidTemplate: MutualAidTemplateCredentials(
                teamID: template["team_id"] as? String ?? "",
                credentialID: template["credential_id"] as? String ?? "",
                credentialSecret: template["credential_secret"] as? String ?? "",
                domainAndPort: template["domain_and_port"] as? String ?? "caltopo.com",
                sourceLabel: template["source_label"] as? String ?? "",
                targetFolderHint: template["target_folder_hint"] as? String ?? "MAI"
            ))
        }
    }

    func apply(faa config: FaaSharedConfig, normalizedToken: String) throws {
        try apply(faa: config, sourceToken: normalizedToken)
    }

    func applyEmbedded(faa config: FaaSharedConfig) throws {
        try apply(faa: config, sourceToken: nil)
    }

    func apply(mutualAidTemplate template: MutualAidTemplateCredentials) throws {
        defaults.set(template.teamID, forKey: "mutualAid.template.teamID")
        defaults.set(template.domainAndPort, forKey: "mutualAid.template.domainAndPort")
        defaults.set(template.sourceLabel, forKey: "mutualAid.template.sourceLabel")
        defaults.set(template.targetFolderHint, forKey: "mutualAid.template.targetFolderHint")
        try Self.storeSecret(template.credentialID, account: Self.mutualAidCredentialIDAccount)
        try Self.storeSecret(template.credentialSecret, account: Self.mutualAidCredentialSecretAccount)
    }

    private func apply(faa config: FaaSharedConfig, sourceToken: String?) throws {
        defaults.set(config.sourceLabel, forKey: "faa.sourceLabel")
        defaults.set(config.apiBaseURL, forKey: "faa.apiBaseURL")
        defaults.set(config.tokenURL, forKey: "faa.tokenURL")
        defaults.set(config.scope, forKey: "faa.scope")
        if let sourceToken { defaults.set(sourceToken, forKey: "faa.joinToken") }
        try Self.storeSecret(config.clientID, account: Self.faaClientIDAccount)
        try Self.storeSecret(config.clientSecret, account: Self.faaClientSecretAccount)
    }

    private static func loadTrackerAPIKey() -> String? {
        loadSecret(account: trackerAccount)
    }

    private static func loadSecret(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func storeTrackerAPIKey(_ value: String) throws {
        try storeSecret(value, account: trackerAccount)
    }

    private static func storeSecret(_ value: String, account: String) throws {
        let key: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: account,
        ]
        if value.isEmpty {
            let status = SecItemDelete(key as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
            }
            return
        }
        let data = Data(value.utf8)
        let update = SecItemUpdate(key as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return }
        guard update == errSecItemNotFound else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(update))
        }
        var insert = key
        insert[kSecValueData as String] = data
        let status = SecItemAdd(insert as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
    }
}

@MainActor
final class AppleOrgConfigImporter: ObservableObject {
    enum State: Equatable {
        case idle
        case downloading
        case applied(String)
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    var caltopoConfigurationHandler: ((AppleCaltopoConfiguration) -> Void)?

    var statusText: String {
        switch state {
        case .idle: "No import in progress"
        case .downloading: "Downloading shared configuration…"
        case let .applied(message), let .failed(message): message
        }
    }

    func importToken(
        _ rawToken: String,
        caltopoSettings: AppleCaltopoSettings,
        orgSettings: AppleOrgConfigSettings,
        identityStore: AppleDroneConfirmationStore
    ) async {
        let normalized = AndroidConfigTokenCodec.normalize(rawToken)
        guard let token = AndroidConfigTokenCodec.decode(normalized), token.version == 1 else {
            state = .failed("Token not recognised or unsupported.")
            return
        }
        state = .downloading
        AppleLog.info("OrgConfig", "Downloading Android \(token.kind.rawValue) QR config label='\(token.displayName)' fileIdLength=\(token.driveFileID.count)")
        do {
            var components = URLComponents(string: "https://drive.google.com/uc")
            components?.queryItems = [
                URLQueryItem(name: "export", value: "download"),
                URLQueryItem(name: "id", value: token.driveFileID),
            ]
            guard let url = components?.url else { throw OrgConfigInteropError.invalidToken }
            var request = URLRequest(url: url)
            request.cachePolicy = .reloadIgnoringLocalCacheData
            request.timeoutInterval = 90
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200 ..< 300).contains(http.statusCode) else {
                throw URLError(.badServerResponse)
            }
            switch token.kind {
            case .organization:
                let bundle = try OrgConfigTokenCodec.parseBundle(data)
                try orgSettings.apply(bundle: bundle, normalizedToken: normalized)
                try caltopoSettings.applyImported(credentials: bundle.credentials)
                caltopoConfigurationHandler?(caltopoSettings.configuration)
                identityStore.applyImportedMappings(bundle.mappings)
                if let faaConfig = bundle.faaConfig {
                    try orgSettings.applyEmbedded(faa: faaConfig)
                }
                if let mutualAidTemplate = bundle.mutualAidTemplate {
                    try orgSettings.apply(mutualAidTemplate: mutualAidTemplate)
                }
                let name = bundle.organizationName.isEmpty ? token.displayName : bundle.organizationName
                let extras = [
                    bundle.faaConfig == nil ? nil : "FAA",
                    bundle.mutualAidTemplate == nil ? nil : "mutual-aid template",
                ].compactMap { $0 }
                let extraMessage = extras.isEmpty ? "" : " Included \(extras.joined(separator: " and "))."
                state = .applied("Joined '\(name)'. Applied \(bundle.mappings.count) RID mappings.\(extraMessage)")
                AppleLog.info(
                    "OrgConfig",
                    "Applied Android org QR config org='\(name)' mappings=\(bundle.mappings.count) ignored=\(bundle.ignoredConfigTypes.joined(separator: ","))"
                )
            case .faa:
                let config = try AndroidConfigTokenCodec.parseFaaBundle(data)
                try orgSettings.apply(faa: config, normalizedToken: normalized)
                state = .applied("FAA config imported: \(config.sourceLabel.isEmpty ? token.displayName : config.sourceLabel).")
                AppleLog.info("OrgConfig", "Applied Android FAA QR config label='\(config.sourceLabel)'")
            case .mutualAid:
                let profile = try AndroidConfigTokenCodec.parseMutualAidBundle(data)
                try orgSettings.apply(mutualAid: profile, normalizedToken: normalized)
                try caltopoSettings.applyImported(mutualAid: profile)
                caltopoConfigurationHandler?(caltopoSettings.configuration)
                state = .applied("Mutual-aid access installed for \(profile.displayName).")
                AppleLog.info("OrgConfig", "Applied Android MA QR profile='\(profile.profileID)' map='\(profile.targetMapID)'")
            }
        } catch {
            state = .failed("Import failed: \(error.localizedDescription)")
            AppleLog.error("OrgConfig", "Import failed: \(error.localizedDescription)")
        }
    }

    func importFile(
        _ url: URL,
        caltopoSettings: AppleCaltopoSettings,
        orgSettings: AppleOrgConfigSettings,
        identityStore: AppleDroneConfirmationStore
    ) async {
        state = .downloading
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        do {
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                throw OrgConfigInteropError.invalidBundle
            }
            let bundleData: Data
            if root["format"] as? String == "rid2caltopo_org_config" {
                bundleData = data
            } else {
                bundleData = try JSONSerialization.data(withJSONObject: [
                    "format": "rid2caltopo_org_config",
                    "version": 1,
                    "org_name": root["org_name"] as? String ?? "",
                    "configs": [root],
                ])
            }
            let bundle = try OrgConfigTokenCodec.parseBundle(bundleData)
            try orgSettings.apply(bundle: bundle, normalizedToken: "local-file:\(url.lastPathComponent)")
            try caltopoSettings.applyImported(credentials: bundle.credentials)
            caltopoConfigurationHandler?(caltopoSettings.configuration)
            if !bundle.mappings.isEmpty {
                identityStore.applyImportedMappings(bundle.mappings)
            }
            if let faaConfig = bundle.faaConfig {
                try orgSettings.applyEmbedded(faa: faaConfig)
            }
            if let mutualAidTemplate = bundle.mutualAidTemplate {
                try orgSettings.apply(mutualAidTemplate: mutualAidTemplate)
            }
            state = .applied(
                "Loaded \(url.lastPathComponent): \(bundle.mappings.count) RID mapping(s)."
            )
            AppleLog.info(
                "OrgConfig",
                "Loaded local config file name='\(url.lastPathComponent)' mappings=\(bundle.mappings.count)"
            )
        } catch {
            state = .failed("Config file import failed: \(error.localizedDescription)")
            AppleLog.error("OrgConfig", "Local config import failed: \(error.localizedDescription)")
        }
    }
}

struct ConfigImportView: View {
    @ObservedObject var importer: AppleOrgConfigImporter
    @ObservedObject var caltopoSettings: AppleCaltopoSettings
    @ObservedObject var orgSettings: AppleOrgConfigSettings
    @ObservedObject var identityStore: AppleDroneConfirmationStore
    @State private var tokenText: String
    @State private var showScanner = false

    init(
        initialToken: String,
        importer: AppleOrgConfigImporter,
        caltopoSettings: AppleCaltopoSettings,
        orgSettings: AppleOrgConfigSettings,
        identityStore: AppleDroneConfirmationStore
    ) {
        self.importer = importer
        self.caltopoSettings = caltopoSettings
        self.orgSettings = orgSettings
        self.identityStore = identityStore
        _tokenText = State(initialValue: initialToken)
    }

    var body: some View {
        Form {
            Section("Import Config") {
                Text("Scan an Android RID2Caltopo org-config QR code or paste its R2C1 token.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                TextEditor(text: $tokenText)
                    .font(.caption.monospaced())
                    .frame(minHeight: 100)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if let decodedToken {
                    LabeledContent("Type", value: tokenKindName(decodedToken.kind))
                    LabeledContent("Label", value: decodedToken.displayName.isEmpty ? "Unnamed" : decodedToken.displayName)
                    LabeledContent("Token version", value: "\(decodedToken.version)")
                } else if !tokenText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text("Token not recognised")
                        .foregroundStyle(.red)
                }
                Button("Scan QR Code", systemImage: "qrcode.viewfinder") { showScanner = true }
                    .disabled(!DataScannerViewController.isSupported || !DataScannerViewController.isAvailable)
                Button("Import Config") {
                    Task {
                        await importer.importToken(
                            tokenText,
                            caltopoSettings: caltopoSettings,
                            orgSettings: orgSettings,
                            identityStore: identityStore
                        )
                    }
                }
                .disabled(decodedToken == nil || importer.state == .downloading)
            }
            Section("Status") {
                if importer.state == .downloading { ProgressView() }
                Text(importer.statusText)
                    .foregroundStyle(statusColor)
                if !orgSettings.organizationName.isEmpty {
                    LabeledContent("Loaded organization", value: orgSettings.organizationName)
                    LabeledContent("Source", value: orgSettings.sourceDescription)
                }
            }
            Section {
                Text("The downloaded bundle can contain CalTopo and tracker credentials. Secrets are stored in the Apple Keychain; the QR token and non-secret incident settings are stored in app preferences.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Import Config")
        .sheet(isPresented: $showScanner) {
            QRCodeScannerView { value in
                tokenText = value
                showScanner = false
            }
            .ignoresSafeArea()
        }
    }

    private var decodedToken: AndroidConfigJoinToken? { AndroidConfigTokenCodec.decode(tokenText) }

    private func tokenKindName(_ kind: AndroidConfigTokenKind) -> String {
        switch kind {
        case .organization: "Organization"
        case .faa: "FAA"
        case .mutualAid: "Mutual Aid"
        }
    }

    private var statusColor: Color {
        if case .failed = importer.state { return .red }
        return .secondary
    }
}

private struct QRCodeScannerView: UIViewControllerRepresentable {
    let onScanned: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onScanned: onScanned) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let controller = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true
        )
        controller.delegate = context.coordinator
        try? controller.startScanning()
        return controller
    }

    func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {
        if !uiViewController.isScanning { try? uiViewController.startScanning() }
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onScanned: (String) -> Void
        init(onScanned: @escaping (String) -> Void) { self.onScanned = onScanned }

        func dataScanner(
            _ dataScanner: DataScannerViewController,
            didAdd addedItems: [RecognizedItem],
            allItems: [RecognizedItem]
        ) {
            for item in addedItems {
                guard case let .barcode(barcode) = item,
                      let value = barcode.payloadStringValue
                else { continue }
                dataScanner.stopScanning()
                onScanned(value)
                return
            }
        }
    }
}
