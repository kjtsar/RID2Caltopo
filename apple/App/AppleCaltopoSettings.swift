import Combine
import Foundation
import R2CCore
import Security

struct AppleCaltopoConfiguration: Sendable, Equatable {
    let enabled: Bool
    let domainAndPort: String
    let mapID: String
    let credentialID: String
    let credentialSecret: String

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
    @Published var credentialID: String
    @Published var credentialSecret: String
    @Published private(set) var teamID: String
    @Published private(set) var status = "Not configured"

    private let defaults: UserDefaults
    private static let keychainService = "org.ncssar.RID2CaltopoApple.caltopo"
    private static let secretAccount = "credential-secret"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        enabled = defaults.bool(forKey: "caltopo.enabled")
        domainAndPort = defaults.string(forKey: "caltopo.domain") ?? "caltopo.com"
        mapID = defaults.string(forKey: "caltopo.mapID") ?? ""
        credentialID = defaults.string(forKey: "caltopo.credentialID") ?? ""
        credentialSecret = Self.loadSecret() ?? ""
        teamID = defaults.string(forKey: "caltopo.teamID") ?? ""
        status = enabled ? "Configuration loaded" : "Publishing disabled"
    }

    var configuration: AppleCaltopoConfiguration {
        AppleCaltopoConfiguration(
            enabled: enabled,
            domainAndPort: domainAndPort.trimmingCharacters(in: .whitespacesAndNewlines),
            mapID: mapID.trimmingCharacters(in: .whitespacesAndNewlines),
            credentialID: credentialID.trimmingCharacters(in: .whitespacesAndNewlines),
            credentialSecret: credentialSecret.trimmingCharacters(in: .whitespacesAndNewlines)
        )
    }

    @discardableResult
    func save() -> AppleCaltopoConfiguration {
        let value = configuration
        defaults.set(value.enabled, forKey: "caltopo.enabled")
        defaults.set(value.domainAndPort, forKey: "caltopo.domain")
        defaults.set(value.mapID, forKey: "caltopo.mapID")
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
        if !profile.credentialID.isEmpty { credentialID = profile.credentialID }
        if !profile.credentialSecret.isEmpty { credentialSecret = profile.credentialSecret }
        teamID = profile.teamID
        defaults.set(teamID, forKey: "caltopo.teamID")
        _ = save()
        status = "Android mutual-aid QR loaded for \(profile.displayName)"
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
