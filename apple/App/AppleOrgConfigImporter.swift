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
        incident = defaults.string(forKey: "org.incident") ?? ""
        operationalPeriod = defaults.string(forKey: "org.operationalPeriod") ?? ""
        trackFolder = defaults.string(forKey: "org.trackFolder") ?? ""
        teamID = defaults.string(forKey: "org.teamID") ?? ""
        trackerURLPrefix = defaults.string(forKey: "org.trackerURLPrefix") ?? ""
        usePeers = defaults.object(forKey: "org.usePeers") as? Bool ?? true
        predictiveHeadEnabled = defaults.object(forKey: "org.predictiveHead") as? Bool ?? true
        proximityAlertSpacingFeet = defaults.object(forKey: "org.proximityFeet") as? Int ?? 40
        sourceDescription = defaults.string(forKey: "org.sourceDescription") ?? "Not loaded"
    }

    var trackerAPIKey: String { Self.loadTrackerAPIKey() ?? "" }

    func apply(bundle: OrgConfigBundle, normalizedToken: String) throws {
        let credentials = bundle.credentials
        organizationName = credentials?.organizationName.isEmpty == false
            ? credentials?.organizationName ?? bundle.organizationName
            : bundle.organizationName
        incident = credentials?.incident ?? ""
        operationalPeriod = credentials?.operationalPeriod ?? ""
        trackFolder = credentials?.trackFolder ?? ""
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
        trackFolder = profile.trackFolder
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
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: trackerAccount,
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
                state = .applied("Mutual-aid access installed for \(profile.displayName).")
                AppleLog.info("OrgConfig", "Applied Android MA QR profile='\(profile.profileID)' map='\(profile.targetMapID)'")
            }
        } catch {
            state = .failed("Import failed: \(error.localizedDescription)")
            AppleLog.error("OrgConfig", "Import failed: \(error.localizedDescription)")
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
