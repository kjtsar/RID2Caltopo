import Foundation
import R2CCore
import Security
import SwiftUI
import Vision
import VisionKit

struct AppleStoredOperationalProfile: Codable {
    let profileID: String
    let displayName: String
    let profileType: String
    let enabled: Bool
    let organizationName: String
    let incident: String
    let operationalPeriod: String
    let trackFolder: String
    let teamID: String
    let trackerURLPrefix: String
    let trackerAPIKey: String
    let domainAndPort: String
    let mapID: String
    let mapTitle: String
    let credentialID: String
    let credentialSecret: String
    let autoConnect: Bool
    let expiresAtEpochMilliseconds: Int64
    let quietRemoveOnExpiry: Bool
}

@MainActor
final class AppleCaltopoProfileLifecycle: ObservableObject {
    static let shared = AppleCaltopoProfileLifecycle()
    @Published private(set) var activeProfileID: String
    @Published private(set) var mutualAidDisplayName: String?
    @Published private(set) var mutualAidExpiresAt: Date?

    private let defaults: UserDefaults
    private static let service = "org.ncssar.RID2CaltopoApple.caltopo-profiles"
    private static let homeAccount = "profile.home"
    private static let mutualAidAccount = "profile.mutual-aid"
    private static let activeKey = "caltopoProfiles.activeID"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        activeProfileID = defaults.string(forKey: Self.activeKey) ?? ""
        if let profile = try? Self.load(account: Self.mutualAidAccount) {
            mutualAidDisplayName = profile.displayName
            mutualAidExpiresAt = profile.expiresAtEpochMilliseconds > 0
                ? Date(timeIntervalSince1970: Double(profile.expiresAtEpochMilliseconds) / 1_000)
                : nil
        }
    }

    func captureHome(
        org: AppleOrgConfigSettings,
        caltopo: AppleCaltopoSettings
    ) throws {
        let configuration = caltopo.configuration
        let profile = AppleStoredOperationalProfile(
            profileID: "home",
            displayName: org.organizationName.isEmpty ? "Home" : org.organizationName,
            profileType: "HOME",
            enabled: configuration.enabled,
            organizationName: org.organizationName,
            incident: org.incident,
            operationalPeriod: org.operationalPeriod,
            trackFolder: org.trackFolder,
            teamID: configuration.teamID.isEmpty ? org.teamID : configuration.teamID,
            trackerURLPrefix: org.trackerURLPrefix,
            trackerAPIKey: org.trackerAPIKey,
            domainAndPort: configuration.domainAndPort,
            mapID: "",
            mapTitle: "",
            credentialID: configuration.credentialID,
            credentialSecret: configuration.credentialSecret,
            autoConnect: false,
            expiresAtEpochMilliseconds: 0,
            quietRemoveOnExpiry: false
        )
        try Self.store(profile, account: Self.homeAccount)
        setActive("home")
    }

    func install(
        _ profile: MutualAidSharedProfile,
        org: AppleOrgConfigSettings,
        caltopo: AppleCaltopoSettings
    ) throws -> Bool {
        if (try? Self.load(account: Self.homeAccount)) == nil {
            try captureHome(org: org, caltopo: caltopo)
        }
        let stored = AppleStoredOperationalProfile(
            profileID: profile.profileID,
            displayName: profile.displayName,
            profileType: "MUTUAL_AID",
            enabled: true,
            organizationName: profile.sourceLabel,
            incident: profile.incident,
            operationalPeriod: profile.operationalPeriod,
            trackFolder: profile.trackFolder,
            teamID: profile.teamID,
            trackerURLPrefix: profile.trackerURLPrefix,
            trackerAPIKey: profile.trackerAPIKey,
            domainAndPort: profile.domainAndPort,
            mapID: profile.targetMapID,
            mapTitle: profile.targetMapTitle.isEmpty ? profile.displayName : profile.targetMapTitle,
            credentialID: profile.credentialID,
            credentialSecret: profile.credentialSecret,
            autoConnect: profile.autoConnect,
            expiresAtEpochMilliseconds: profile.expiresAtEpochMilliseconds,
            quietRemoveOnExpiry: profile.quietRemoveOnExpiry
        )
        try Self.store(stored, account: Self.mutualAidAccount)
        mutualAidDisplayName = stored.displayName
        mutualAidExpiresAt = stored.expiresAtEpochMilliseconds > 0
            ? Date(timeIntervalSince1970: Double(stored.expiresAtEpochMilliseconds) / 1_000)
            : nil
        let unexpired = stored.expiresAtEpochMilliseconds <= 0
            || Int64(Date().timeIntervalSince1970 * 1_000) < stored.expiresAtEpochMilliseconds
        if unexpired {
            setActive(stored.profileID)
        }
        return unexpired
    }

    @discardableResult
    func restoreActive(
        org: AppleOrgConfigSettings,
        caltopo: AppleCaltopoSettings,
        now: Date = Date()
    ) throws -> Bool {
        guard !activeProfileID.isEmpty, activeProfileID != "home",
              let mutualAid = try? Self.load(account: Self.mutualAidAccount),
              mutualAid.profileID == activeProfileID
        else { return false }
        if mutualAid.expiresAtEpochMilliseconds > 0,
           Int64(now.timeIntervalSince1970 * 1_000) >= mutualAid.expiresAtEpochMilliseconds {
            return try removeExpired(org: org, caltopo: caltopo, now: now)
        }
        try org.apply(storedProfile: mutualAid)
        try caltopo.apply(storedProfile: mutualAid, connectMap: true)
        return true
    }

    @discardableResult
    func removeExpired(
        org: AppleOrgConfigSettings,
        caltopo: AppleCaltopoSettings,
        now: Date = Date()
    ) throws -> Bool {
        guard let mutualAid = try? Self.load(account: Self.mutualAidAccount),
              mutualAid.expiresAtEpochMilliseconds > 0,
              Int64(now.timeIntervalSince1970 * 1_000) >= mutualAid.expiresAtEpochMilliseconds
        else { return false }
        try Self.delete(account: Self.mutualAidAccount)
        mutualAidDisplayName = nil
        mutualAidExpiresAt = nil
        if activeProfileID == mutualAid.profileID,
           let home = try? Self.load(account: Self.homeAccount) {
            try org.apply(storedProfile: home)
            try caltopo.apply(storedProfile: home, connectMap: false)
            setActive(home.profileID)
        }
        AppleLog.info(
            "OrgConfig",
            "Removed expired mutual-aid profile id='\(mutualAid.profileID)' quiet=\(mutualAid.quietRemoveOnExpiry)"
        )
        return true
    }

    private func setActive(_ profileID: String) {
        activeProfileID = profileID
        defaults.set(profileID, forKey: Self.activeKey)
    }

    private static func load(account: String) throws -> AppleStoredOperationalProfile {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
        return try JSONDecoder().decode(AppleStoredOperationalProfile.self, from: data)
    }

    private static func store(_ profile: AppleStoredOperationalProfile, account: String) throws {
        let key: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let data = try JSONEncoder().encode(profile)
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

    private static func delete(account: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
    }
}

@MainActor
final class AppleOrgConfigSettings: ObservableObject {
    @Published private(set) var organizationName: String
    @Published private(set) var incident: String
    @Published private(set) var operationalPeriod: String
    @Published private(set) var trackFolder: String
    @Published private(set) var teamID: String
    @Published private(set) var trackerURLPrefix: String
    @Published private(set) var faaProxyURL: String
    @Published private(set) var usePeers: Bool
    @Published private(set) var standaloneR2CCoordinationEnabled: Bool
    @Published private(set) var predictiveHeadEnabled: Bool
    @Published private(set) var proximityAlertSpacingFeet: Int
    @Published private(set) var minimumTrackDistanceFeet: Int
    @Published private(set) var newTrackDelaySeconds: Int
    @Published private(set) var bridgeCheckDistanceFeet: Int
    @Published private(set) var maximumIdleMinutes: Int
    @Published private(set) var sourceDescription: String

    private let defaults: UserDefaults
    private static let keychainService = "org.ncssar.RID2CaltopoApple.org-config"
    private static let trackerAccount = "tracker-api-key"
    private static let managedStandaloneMigrationKey =
        "org.managedTrackerStandaloneMigration.v1"
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
        faaProxyURL = defaults.string(forKey: "org.faaProxyURL") ?? ""
        usePeers = defaults.object(forKey: "org.usePeers") as? Bool ?? true
        standaloneR2CCoordinationEnabled =
            defaults.object(forKey: "org.standaloneR2CCoordination") as? Bool ?? false
        predictiveHeadEnabled = defaults.object(forKey: "org.predictiveHead") as? Bool ?? true
        proximityAlertSpacingFeet = defaults.object(forKey: "org.proximityFeet") as? Int ?? 40
        minimumTrackDistanceFeet = max(2, defaults.object(forKey: "track.minimumDistanceFeet") as? Int ?? 2)
        newTrackDelaySeconds = max(1, defaults.object(forKey: "track.newTrackDelaySeconds") as? Int ?? 30)
        bridgeCheckDistanceFeet = max(1, defaults.object(forKey: "track.bridgeCheckDistanceFeet") as? Int ?? 20)
        maximumIdleMinutes = max(0, defaults.object(forKey: "app.maximumIdleMinutes") as? Int ?? 120)
        sourceDescription = defaults.string(forKey: "org.sourceDescription") ?? "Not loaded"
        if sourceDescription == "Managed r2c-tracker enrollment" {
            let scopedPrefix = TrackerCoordinationEndpoint.organizationScopedPrefix(
                from: trackerURLPrefix,
                organization: organizationName
            )
            if scopedPrefix != trackerURLPrefix {
                trackerURLPrefix = scopedPrefix
                defaults.set(scopedPrefix, forKey: "org.trackerURLPrefix")
                AppleLog.info("TrackerPeer", "Migrated managed tracker prefix to organization scope")
            }
        }
        if sourceDescription == "Managed r2c-tracker enrollment",
           !defaults.bool(forKey: Self.managedStandaloneMigrationKey) {
            usePeers = true
            standaloneR2CCoordinationEnabled = true
            defaults.set(true, forKey: "org.usePeers")
            defaults.set(true, forKey: "org.standaloneR2CCoordination")
            defaults.set(true, forKey: Self.managedStandaloneMigrationKey)
        }
        defaults.set("Training", forKey: "org.incident")
        defaults.set("1", forKey: "org.operationalPeriod")
    }

    var trackerAPIKey: String { Self.loadTrackerAPIKey() ?? "" }

    var hasManagedTrackerEnrollment: Bool {
        sourceDescription == "Managed r2c-tracker enrollment" &&
            !trackerURLPrefix.isEmpty &&
            !trackerAPIKey.isEmpty &&
            !faaProxyURL.isEmpty
    }

    var hasNotamAdminConfiguration: Bool {
        hasManagedTrackerEnrollment
    }

    func setOrganizationNameForRidMappings(_ organization: String) {
        organizationName = organization.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(organizationName, forKey: "org.name")
    }

    func setTrackFolder(_ value: String) {
        trackFolder = Self.normalizedTrackFolder(value)
        defaults.set(trackFolder, forKey: "org.trackFolder")
    }

    func applyManualTrackerConfiguration(
        trackerURLPrefix: String,
        trackerAPIKey: String
    ) throws {
        self.trackerURLPrefix = trackerURLPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        faaProxyURL = ""
        sourceDescription = "Manual Settings"
        defaults.set(self.trackerURLPrefix, forKey: "org.trackerURLPrefix")
        defaults.removeObject(forKey: "org.faaProxyURL")
        defaults.set(sourceDescription, forKey: "org.sourceDescription")
        try Self.storeTrackerAPIKey(trackerAPIKey.trimmingCharacters(in: .whitespacesAndNewlines))
        objectWillChange.send()
    }

    func applyTrackerEnrollment(
        organization: String,
        trackerURLPrefix: String,
        trackerAPIKey: String,
        faaProxyURL: String
    ) throws {
        organizationName = organization.trimmingCharacters(in: .whitespacesAndNewlines)
        self.trackerURLPrefix = TrackerCoordinationEndpoint.organizationScopedPrefix(
            from: trackerURLPrefix,
            organization: organizationName
        )
        self.faaProxyURL = faaProxyURL.trimmingCharacters(in: .whitespacesAndNewlines)
        usePeers = true
        standaloneR2CCoordinationEnabled = true
        sourceDescription = "Managed r2c-tracker enrollment"
        defaults.set(organizationName, forKey: "org.name")
        defaults.set(self.trackerURLPrefix, forKey: "org.trackerURLPrefix")
        defaults.set(self.faaProxyURL, forKey: "org.faaProxyURL")
        defaults.set(usePeers, forKey: "org.usePeers")
        defaults.set(
            standaloneR2CCoordinationEnabled,
            forKey: "org.standaloneR2CCoordination"
        )
        defaults.set(true, forKey: Self.managedStandaloneMigrationKey)
        defaults.set(sourceDescription, forKey: "org.sourceDescription")
        try Self.storeTrackerAPIKey(trackerAPIKey)
    }

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
        defaults.set(standaloneR2CCoordinationEnabled, forKey: "org.standaloneR2CCoordination")
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

    func apply(storedProfile profile: AppleStoredOperationalProfile) throws {
        organizationName = profile.organizationName
        incident = profile.incident
        operationalPeriod = profile.operationalPeriod
        trackFolder = Self.normalizedTrackFolder(profile.trackFolder)
        teamID = profile.teamID
        trackerURLPrefix = profile.trackerURLPrefix
        sourceDescription = profile.profileType == "HOME"
            ? "Home organization • \(profile.displayName)"
            : "Mutual aid • \(profile.displayName)"
        defaults.set(organizationName, forKey: "org.name")
        defaults.set(incident, forKey: "org.incident")
        defaults.set(operationalPeriod, forKey: "org.operationalPeriod")
        defaults.set(trackFolder, forKey: "org.trackFolder")
        defaults.set(teamID, forKey: "org.teamID")
        defaults.set(trackerURLPrefix, forKey: "org.trackerURLPrefix")
        defaults.set(sourceDescription, forKey: "org.sourceDescription")
        try Self.storeTrackerAPIKey(profile.trackerAPIKey)
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

    func setStandaloneR2CCoordinationEnabled(_ enabled: Bool) {
        standaloneR2CCoordinationEnabled = enabled
        defaults.set(enabled, forKey: "org.standaloneR2CCoordination")
        AppleLog.info("TrackerPeer", "Standalone R2C coordination enabled=\(enabled)")
    }

    func setProximityAlertSpacingFeet(_ value: Int) {
        proximityAlertSpacingFeet = max(1, value)
        defaults.set(proximityAlertSpacingFeet, forKey: "org.proximityFeet")
    }

    func setMinimumTrackDistanceFeet(_ value: Int) {
        minimumTrackDistanceFeet = max(2, value)
        defaults.set(minimumTrackDistanceFeet, forKey: "track.minimumDistanceFeet")
    }

    func setNewTrackDelaySeconds(_ value: Int) {
        newTrackDelaySeconds = max(1, value)
        defaults.set(newTrackDelaySeconds, forKey: "track.newTrackDelaySeconds")
    }

    func setBridgeCheckDistanceFeet(_ value: Int) {
        bridgeCheckDistanceFeet = max(1, value)
        defaults.set(bridgeCheckDistanceFeet, forKey: "track.bridgeCheckDistanceFeet")
    }

    func setMaximumIdleMinutes(_ value: Int) {
        maximumIdleMinutes = max(0, value)
        defaults.set(maximumIdleMinutes, forKey: "app.maximumIdleMinutes")
    }

    func resetPersistedState() {
        organizationName = ""
        incident = "Training"
        operationalPeriod = "1"
        trackFolder = "Drone Tracks"
        teamID = ""
        trackerURLPrefix = ""
        faaProxyURL = ""
        usePeers = true
        standaloneR2CCoordinationEnabled = false
        predictiveHeadEnabled = true
        proximityAlertSpacingFeet = 40
        minimumTrackDistanceFeet = 2
        newTrackDelaySeconds = 30
        bridgeCheckDistanceFeet = 20
        maximumIdleMinutes = 120
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
            "standalone_r2c_coordination_enabled": standaloneR2CCoordinationEnabled,
            "predictive_head": predictiveHeadEnabled,
            "proximity_feet": proximityAlertSpacingFeet,
            "minimum_track_distance_feet": minimumTrackDistanceFeet,
            "new_track_delay_seconds": newTrackDelaySeconds,
            "bridge_check_distance_feet": bridgeCheckDistanceFeet,
            "maximum_idle_minutes": maximumIdleMinutes,
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
        standaloneR2CCoordinationEnabled =
            (object["standalone_r2c_coordination_enabled"] as? NSNumber)?.boolValue ?? false
        predictiveHeadEnabled = (object["predictive_head"] as? NSNumber)?.boolValue ?? true
        proximityAlertSpacingFeet = (object["proximity_feet"] as? NSNumber)?.intValue ?? 40
        minimumTrackDistanceFeet = max(2, (object["minimum_track_distance_feet"] as? NSNumber)?.intValue ?? 2)
        newTrackDelaySeconds = max(1, (object["new_track_delay_seconds"] as? NSNumber)?.intValue ?? 30)
        bridgeCheckDistanceFeet = max(1, (object["bridge_check_distance_feet"] as? NSNumber)?.intValue ?? 20)
        maximumIdleMinutes = max(0, (object["maximum_idle_minutes"] as? NSNumber)?.intValue ?? 120)
        sourceDescription = object["source_description"] as? String ?? "Local backup"
        defaults.set(organizationName, forKey: "org.name")
        defaults.set(incident, forKey: "org.incident")
        defaults.set(operationalPeriod, forKey: "org.operationalPeriod")
        defaults.set(trackFolder, forKey: "org.trackFolder")
        defaults.set(teamID, forKey: "org.teamID")
        defaults.set(trackerURLPrefix, forKey: "org.trackerURLPrefix")
        defaults.set(usePeers, forKey: "org.usePeers")
        defaults.set(standaloneR2CCoordinationEnabled, forKey: "org.standaloneR2CCoordination")
        defaults.set(predictiveHeadEnabled, forKey: "org.predictiveHead")
        defaults.set(proximityAlertSpacingFeet, forKey: "org.proximityFeet")
        defaults.set(minimumTrackDistanceFeet, forKey: "track.minimumDistanceFeet")
        defaults.set(newTrackDelaySeconds, forKey: "track.newTrackDelaySeconds")
        defaults.set(bridgeCheckDistanceFeet, forKey: "track.bridgeCheckDistanceFeet")
        defaults.set(maximumIdleMinutes, forKey: "app.maximumIdleMinutes")
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
    let profileLifecycle = AppleCaltopoProfileLifecycle.shared
    var caltopoConfigurationHandler: ((AppleCaltopoConfiguration) -> Void)?

    var statusText: String {
        switch state {
        case .idle: "No import in progress"
        case .downloading: "Downloading shared configuration…"
        case let .applied(message), let .failed(message): message
        }
    }

    func importTrackerEnrollment(
        _ rawURL: String,
        orgSettings: AppleOrgConfigSettings
    ) async {
        state = .downloading
        do {
            let result = try await AppleTrackerEnrollmentClient.redeem(rawURL)
            try orgSettings.applyTrackerEnrollment(
                organization: result.organization,
                trackerURLPrefix: result.trackerBaseURL,
                trackerAPIKey: result.deviceToken,
                faaProxyURL: result.faaProxyURL
            )
            AppleNotamCenter.shared.enabled = true
            state = .applied("Joined \(result.organization) on r2c-tracker.")
            AppleLog.info(
                "OrgConfig",
                "Installed managed tracker enrollment org='\(result.organization)'"
            )
        } catch {
            state = .failed(error.localizedDescription)
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
                try profileLifecycle.captureHome(org: orgSettings, caltopo: caltopoSettings)
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
                let active = try profileLifecycle.install(
                    profile,
                    org: orgSettings,
                    caltopo: caltopoSettings
                )
                if active {
                    try orgSettings.apply(mutualAid: profile, normalizedToken: normalized)
                    try caltopoSettings.applyImported(mutualAid: profile)
                    caltopoConfigurationHandler?(caltopoSettings.configuration)
                }
                state = .applied(
                    active
                        ? "Mutual-aid access installed for \(profile.displayName)."
                        : "Expired mutual-aid access was not activated."
                )
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
            try profileLifecycle.captureHome(org: orgSettings, caltopo: caltopoSettings)
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

    @discardableResult
    func removeExpiredProfiles(
        caltopoSettings: AppleCaltopoSettings,
        orgSettings: AppleOrgConfigSettings
    ) -> Bool {
        do {
            let removed = try profileLifecycle.removeExpired(
                org: orgSettings,
                caltopo: caltopoSettings
            )
            if removed {
                caltopoConfigurationHandler?(caltopoSettings.configuration)
                state = .applied("Expired mutual-aid access removed; home organization restored.")
            }
            return removed
        } catch {
            AppleLog.error("OrgConfig", "Mutual-aid expiry cleanup failed: \(error.localizedDescription)")
            return false
        }
    }

    @discardableResult
    func restoreActiveProfile(
        caltopoSettings: AppleCaltopoSettings,
        orgSettings: AppleOrgConfigSettings
    ) -> Bool {
        do {
            return try profileLifecycle.restoreActive(
                org: orgSettings,
                caltopo: caltopoSettings
            )
        } catch {
            AppleLog.error("OrgConfig", "Profile restoration failed: \(error.localizedDescription)")
            return false
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
                Text("Scan an r2c-tracker enrollment QR. Legacy R2C1 tokens remain available for migration.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                TextEditor(text: $tokenText)
                    .font(.caption.monospaced())
                    .frame(minHeight: 100)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                if isTrackerEnrollment {
                    LabeledContent("Type", value: "Managed tracker enrollment")
                } else if let decodedToken {
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
                        if isTrackerEnrollment {
                            await importer.importTrackerEnrollment(
                                tokenText,
                                orgSettings: orgSettings
                            )
                        } else {
                            await importer.importToken(
                                tokenText,
                                caltopoSettings: caltopoSettings,
                                orgSettings: orgSettings,
                                identityStore: identityStore
                            )
                        }
                    }
                }
                .disabled((decodedToken == nil && !isTrackerEnrollment) || importer.state == .downloading)
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
    private var isTrackerEnrollment: Bool {
        AppleTrackerEnrollmentClient.isEnrollmentURL(tokenText)
    }

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
