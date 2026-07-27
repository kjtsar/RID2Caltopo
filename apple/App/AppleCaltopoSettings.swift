import Combine
import Foundation
import R2CCore
import Security

struct AppleCaltopoConfiguration: Sendable, Equatable {
    let enabled: Bool
    let domainAndPort: String
    let mapID: String
    let mapTitle: String
    let credentialID: String
    let credentialSecret: String
    let teamID: String

    var liveConfiguration: CaltopoLiveConfiguration? {
        guard enabled,
              !domainAndPort.isEmpty,
              !mapID.isEmpty,
              !credentialID.isEmpty,
              !credentialSecret.isEmpty
        else { return nil }
        return CaltopoLiveConfiguration(
            domainAndPort: domainAndPort,
            mapID: mapID,
            credentialID: credentialID,
            credentialSecretBase64: credentialSecret
        )
    }
}

@MainActor
final class AppleCaltopoSettings: ObservableObject {
    @Published var enabled: Bool
    @Published var domainAndPort: String
    @Published var mapID: String
    @Published private(set) var mapTitle: String
    @Published var credentialID: String
    @Published var credentialSecret: String
    @Published private(set) var teamID: String
    @Published private(set) var status = "Not configured"
    @Published private(set) var teamMaps: [CaltopoTeamMapNode] = []
    @Published private(set) var isLoadingTeamMaps = false

    private let defaults: UserDefaults
    private static let keychainService = "org.ncssar.RID2CaltopoApple.caltopo"
    private static let secretAccount = "credential-secret"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        enabled = defaults.bool(forKey: "caltopo.enabled")
        domainAndPort = defaults.string(forKey: "caltopo.domain") ?? "caltopo.com"
        // Match Android's session lifecycle: credentials and profiles persist,
        // but every process launch begins without an active incident map. A map
        // becomes active only after the operator explicitly selects it.
        mapID = ""
        mapTitle = ""
        defaults.removeObject(forKey: "caltopo.mapID")
        defaults.removeObject(forKey: "caltopo.mapTitle")
        credentialID = defaults.string(forKey: "caltopo.credentialID") ?? ""
        credentialSecret = Self.loadSecret() ?? ""
        teamID = defaults.string(forKey: "caltopo.teamID") ?? ""
        status = enabled ? "Standalone; select the incident map" : "Publishing disabled"
    }

    var configuration: AppleCaltopoConfiguration {
        let normalizedDomain = domainAndPort.trimmingCharacters(in: .whitespacesAndNewlines)
        return AppleCaltopoConfiguration(
            enabled: enabled,
            domainAndPort: normalizedDomain.isEmpty ? "caltopo.com" : normalizedDomain,
            mapID: mapID.trimmingCharacters(in: .whitespacesAndNewlines),
            mapTitle: mapTitle.trimmingCharacters(in: .whitespacesAndNewlines),
            credentialID: credentialID.trimmingCharacters(in: .whitespacesAndNewlines),
            credentialSecret: credentialSecret.trimmingCharacters(in: .whitespacesAndNewlines),
            teamID: teamID.trimmingCharacters(in: .whitespacesAndNewlines)
        )
    }

    @discardableResult
    func save() -> AppleCaltopoConfiguration {
        let value = configuration
        defaults.set(value.enabled, forKey: "caltopo.enabled")
        defaults.set(value.domainAndPort, forKey: "caltopo.domain")
        defaults.set(value.mapID, forKey: "caltopo.mapID")
        defaults.set(value.mapTitle, forKey: "caltopo.mapTitle")
        defaults.set(value.credentialID, forKey: "caltopo.credentialID")
        do {
            try Self.storeSecret(value.credentialSecret)
            status = value.liveConfiguration == nil
                ? "Saved; publishing remains disabled or incomplete"
                : "Saved securely"
        } catch {
            status = "Keychain save failed: \(error.localizedDescription)"
        }
        return value
    }

    func applyImported(credentials: OrgConfigCredentials?) throws {
        guard let credentials else { return }
        if !credentials.domainAndPort.isEmpty { domainAndPort = credentials.domainAndPort }
        if !credentials.credentialID.isEmpty { credentialID = credentials.credentialID }
        if !credentials.credentialSecret.isEmpty { credentialSecret = credentials.credentialSecret }
        teamID = credentials.teamID
        defaults.set(teamID, forKey: "caltopo.teamID")
        _ = save()
        status = "Android QR credentials loaded; select the incident Map ID to publish"
    }

    func applyImported(mutualAid profile: MutualAidSharedProfile) throws {
        if !profile.domainAndPort.isEmpty { domainAndPort = profile.domainAndPort }
        if !profile.targetMapID.isEmpty { mapID = profile.targetMapID }
        mapTitle = profile.displayName
        if !profile.credentialID.isEmpty { credentialID = profile.credentialID }
        if !profile.credentialSecret.isEmpty { credentialSecret = profile.credentialSecret }
        teamID = profile.teamID
        defaults.set(teamID, forKey: "caltopo.teamID")
        _ = save()
        status = "Android mutual-aid QR loaded for \(profile.displayName)"
    }

    func transferSnapshot() -> [String: Any] {
        [
            "enabled": enabled,
            "domain_and_port": domainAndPort,
            "map_id": mapID,
            "map_title": mapTitle,
            "credential_id": credentialID,
            "credential_secret": credentialSecret,
            "team_id": teamID,
        ]
    }

    func applyTransferSnapshot(_ object: [String: Any]) throws {
        enabled = (object["enabled"] as? NSNumber)?.boolValue ?? false
        domainAndPort = object["domain_and_port"] as? String ?? "caltopo.com"
        mapID = object["map_id"] as? String ?? ""
        mapTitle = object["map_title"] as? String ?? ""
        credentialID = object["credential_id"] as? String ?? ""
        credentialSecret = object["credential_secret"] as? String ?? ""
        teamID = object["team_id"] as? String ?? ""
        defaults.set(teamID, forKey: "caltopo.teamID")
        _ = save()
        status = "Configuration restored from local backup"
    }

    func loadTeamMaps() async {
        let value = configuration
        guard !value.teamID.isEmpty, !value.credentialID.isEmpty, !value.credentialSecret.isEmpty else {
            status = "Import an organization QR code before browsing team maps"
            teamMaps = []
            return
        }
        isLoadingTeamMaps = true
        status = "Loading CalTopo team maps…"
        AppleLog.info(
            "CalTopo",
            "Loading team maps domain='\(value.domainAndPort)' teamPresent=\(!value.teamID.isEmpty) credentialPresent=\(!value.credentialID.isEmpty) secretPresent=\(!value.credentialSecret.isEmpty)"
        )
        defer { isLoadingTeamMaps = false }
        do {
            let client = try CaltopoTeamMapClient(configuration: .init(
                domainAndPort: value.domainAndPort,
                teamID: value.teamID,
                credentialID: value.credentialID,
                credentialSecretBase64: value.credentialSecret
            ))
            teamMaps = try await client.fetch()
            if let selected = findMap(id: mapID, in: teamMaps) {
                mapTitle = selected.title
                defaults.set(mapTitle, forKey: "caltopo.mapTitle")
                status = "Connected to \(selected.title)"
            } else {
                status = teamMaps.isEmpty ? "No team maps were returned" : "Select the incident map"
            }
        } catch {
            teamMaps = []
            status = "Unable to load team maps: \(error.localizedDescription)"
            AppleLog.error("CalTopo", status)
        }
    }

    @discardableResult
    func selectMap(_ map: CaltopoTeamMap) -> AppleCaltopoConfiguration {
        mapID = map.id
        mapTitle = map.title
        enabled = true
        let value = save()
        status = "Connected to \(map.title)"
        return value
    }

    @discardableResult
    func disconnectMap() -> AppleCaltopoConfiguration {
        mapID = ""
        mapTitle = ""
        defaults.removeObject(forKey: "caltopo.mapID")
        defaults.removeObject(forKey: "caltopo.mapTitle")
        status = "Standalone; select the incident map"
        return configuration
    }

    func resetPersistedState() {
        enabled = false
        domainAndPort = "caltopo.com"
        mapID = ""
        mapTitle = ""
        credentialID = ""
        credentialSecret = ""
        teamID = ""
        teamMaps = []
        status = "Not configured"
        try? Self.storeSecret("")
    }

    private func findMap(id: String, in nodes: [CaltopoTeamMapNode]) -> CaltopoTeamMap? {
        for node in nodes {
            if let map = node.map, map.id == id { return map }
            if let children = node.children, let map = findMap(id: id, in: children) { return map }
        }
        return nil
    }

    private static func loadSecret() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: secretAccount,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private static func storeSecret(_ secret: String) throws {
        let key: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: secretAccount,
        ]
        if secret.isEmpty {
            let status = SecItemDelete(key as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
            }
            return
        }
        let data = Data(secret.utf8)
        let updateStatus = SecItemUpdate(
            key as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(updateStatus))
        }
        var insert = key
        insert[kSecValueData as String] = data
        let insertStatus = SecItemAdd(insert as CFDictionary, nil)
        guard insertStatus == errSecSuccess else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(insertStatus))
        }
    }
}
