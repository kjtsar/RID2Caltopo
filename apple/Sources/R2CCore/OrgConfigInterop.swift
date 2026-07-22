import Foundation

public struct OrgConfigJoinToken: Sendable, Equatable {
    public let organizationName: String
    public let driveFileID: String
    public let isPublic: Bool
    public let version: Int
}

public struct OrgConfigRIDMapping: Sendable, Equatable {
    public let remoteID: String
    public let mappedID: String
    public let organization: String
    public let model: String
    public let owner: String

    public init(remoteID: String, mappedID: String, organization: String, model: String, owner: String) {
        self.remoteID = remoteID
        self.mappedID = mappedID
        self.organization = organization
        self.model = model
        self.owner = owner
    }
}

public struct OrgConfigCredentials: Sendable, Equatable {
    public let organizationName: String
    public let teamID: String
    public let credentialID: String
    public let credentialSecret: String
    public let domainAndPort: String
    public let trackFolder: String
    public let incident: String
    public let operationalPeriod: String
    public let trackerAPIKey: String
    public let trackerURLPrefix: String
    public let usePeers: Bool?
    public let predictiveHeadEnabled: Bool
    public let proximityAlertSpacingFeet: Int
}

public struct OrgConfigBundle: Sendable, Equatable {
    public let organizationName: String
    public let version: Int
    public let mappings: [OrgConfigRIDMapping]
    public let credentials: OrgConfigCredentials?
    public let faaConfig: FaaSharedConfig?
    public let mutualAidTemplate: MutualAidTemplateCredentials?
    public let ignoredConfigTypes: [String]
}

public enum OrgConfigInteropError: Error, Sendable, Equatable {
    case invalidToken
    case invalidEncryptedPayload
    case invalidBundle
    case unsupportedBundleVersion(Int)
}

public enum AndroidConfigTokenKind: String, Sendable, Equatable {
    case organization
    case faa
    case mutualAid
}

public struct AndroidConfigJoinToken: Sendable, Equatable {
    public let kind: AndroidConfigTokenKind
    public let displayName: String
    public let driveFileID: String
    public let isPublic: Bool
    public let version: Int
}

public struct FaaSharedConfig: Sendable, Equatable {
    public let sourceLabel: String
    public let apiBaseURL: String
    public let tokenURL: String
    public let clientID: String
    public let clientSecret: String
    public let scope: String

    public init(sourceLabel: String, apiBaseURL: String, tokenURL: String, clientID: String, clientSecret: String, scope: String) {
        self.sourceLabel = sourceLabel
        self.apiBaseURL = apiBaseURL
        self.tokenURL = tokenURL
        self.clientID = clientID
        self.clientSecret = clientSecret
        self.scope = scope
    }
}

public struct MutualAidTemplateCredentials: Sendable, Equatable {
    public let teamID: String
    public let credentialID: String
    public let credentialSecret: String
    public let domainAndPort: String
    public let sourceLabel: String
    public let targetFolderHint: String

    public init(teamID: String, credentialID: String, credentialSecret: String, domainAndPort: String, sourceLabel: String, targetFolderHint: String) {
        self.teamID = teamID
        self.credentialID = credentialID
        self.credentialSecret = credentialSecret
        self.domainAndPort = domainAndPort
        self.sourceLabel = sourceLabel
        self.targetFolderHint = targetFolderHint
    }
}

public struct MutualAidSharedProfile: Sendable, Equatable {
    public let profileID: String
    public let displayName: String
    public let teamID: String
    public let credentialID: String
    public let credentialSecret: String
    public let domainAndPort: String
    public let trackFolder: String
    public let incident: String
    public let operationalPeriod: String
    public let trackerAPIKey: String
    public let trackerURLPrefix: String
    public let autoConnect: Bool
    public let expiresAtEpochMilliseconds: Int64
    public let sourceLabel: String
    public let targetMapID: String
    public let targetMapTitle: String
    public let targetFolderHint: String
}

public enum AndroidConfigTokenCodec {
    private static let definitions: [(kind: AndroidConfigTokenKind, prefix: String, scheme: String)] = [
        (.organization, "R2C1:", "r2c1"),
        (.faa, "R2CFAA1:", "r2cfaa1"),
        (.mutualAid, "R2CMA1:", "r2cma1"),
    ]
    private static let xorKey = Array("RID2CaltopoQR".utf8)
    private static let standardAlphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
    private static let customAlphabet = Array("r2cNOPQRSTUVWXYZABCDEFGHIJKLMnopqstuvwxyzabdefghijklm013456789+/=")

    public static func normalize(_ rawValue: String) -> String {
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              let definition = definitions.first(where: { $0.scheme == scheme })
        else { return trimmed }
        let payload = ((components.host ?? "") + components.path)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return definition.prefix + payload
    }

    public static func decode(_ rawValue: String) -> AndroidConfigJoinToken? {
        let normalized = normalize(rawValue)
        guard let definition = definitions.first(where: { normalized.hasPrefix($0.prefix) }) else { return nil }
        let encoded = String(normalized.dropFirst(definition.prefix.count))
        let remapped = encoded.map { character -> Character in
            guard let index = customAlphabet.firstIndex(of: character) else { return character }
            return standardAlphabet[index]
        }
        guard let encrypted = Data(base64Encoded: String(remapped)),
              let object = try? JSONSerialization.jsonObject(with: Data(xor(Array(encrypted)))) as? [String: Any],
              let fileID = object["f"] as? String,
              !fileID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return nil }
        let displayNameKey = definition.kind == .faa ? "l" : "o"
        return AndroidConfigJoinToken(
            kind: definition.kind,
            displayName: object[displayNameKey] as? String ?? "",
            driveFileID: fileID,
            isPublic: (object["p"] as? NSNumber)?.intValue != 0,
            version: (object["v"] as? NSNumber)?.intValue ?? 1
        )
    }

    public static func parseFaaBundle(_ data: Data) throws -> FaaSharedConfig {
        guard let wrapper = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              wrapper["type"] as? String == "ct_faa_credentials_enc",
              let encoded = wrapper["enc"] as? String,
              let plaintext = try? decrypt(encoded),
              let plaintextData = plaintext.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: plaintextData) as? [String: Any]
        else { throw OrgConfigInteropError.invalidBundle }
        let clientID = string(object["notam_client_id"])
        let clientSecret = string(object["notam_client_secret"])
        guard !clientID.isEmpty, !clientSecret.isEmpty else { throw OrgConfigInteropError.invalidBundle }
        return FaaSharedConfig(
            sourceLabel: string(object["source_label"]).isEmpty ? string(object["label"]) : string(object["source_label"]),
            apiBaseURL: string(object["notam_api_base_url"]),
            tokenURL: string(object["notam_token_url"]),
            clientID: clientID,
            clientSecret: clientSecret,
            scope: string(object["notam_scope"])
        )
    }

    public static func parseMutualAidBundle(_ data: Data) throws -> MutualAidSharedProfile {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              root["format"] as? String == "rid2caltopo_mutual_aid_profile",
              let profileWrapper = root["profile"] as? [String: Any],
              profileWrapper["type"] as? String == "caltopo_profile_enc",
              let encoded = profileWrapper["enc"] as? String,
              let plaintext = try? decrypt(encoded),
              let plaintextData = plaintext.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: plaintextData) as? [String: Any]
        else { throw OrgConfigInteropError.invalidBundle }
        let profileID = string(object["profile_id"])
        guard !profileID.isEmpty else { throw OrgConfigInteropError.invalidBundle }
        return MutualAidSharedProfile(
            profileID: profileID,
            displayName: string(object["display_name"]),
            teamID: string(object["team_id"]),
            credentialID: string(object["credential_id"]),
            credentialSecret: string(object["credential_secret"]),
            domainAndPort: string(object["domain_and_port"]),
            trackFolder: string(object["track_folder"]),
            incident: string(object["incident"]),
            operationalPeriod: string(object["op_period"]),
            trackerAPIKey: string(object["tracker_api_key"]),
            trackerURLPrefix: string(object["tracker_url_prefix"]),
            autoConnect: (object["auto_connect"] as? NSNumber)?.boolValue ?? true,
            expiresAtEpochMilliseconds: (object["expires_at_epoch_ms"] as? NSNumber)?.int64Value ?? 0,
            sourceLabel: string(object["source_label"]),
            targetMapID: string(object["target_map_id"]),
            targetMapTitle: string(object["target_map_title"]),
            targetFolderHint: string(object["target_folder_hint"])
        )
    }

    public static func encryptMutualAidProfile(_ object: [String: Any]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        return Data(xor(Array(data))).base64EncodedString()
    }

    public static func decryptMutualAidProfile(_ encoded: String) throws -> MutualAidSharedProfile {
        let plaintext = try decrypt(encoded)
        guard let data = plaintext.data(using: .utf8),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { throw OrgConfigInteropError.invalidBundle }
        let profileID = string(object["profile_id"])
        guard !profileID.isEmpty else { throw OrgConfigInteropError.invalidBundle }
        return MutualAidSharedProfile(
            profileID: profileID,
            displayName: string(object["display_name"]),
            teamID: string(object["team_id"]),
            credentialID: string(object["credential_id"]),
            credentialSecret: string(object["credential_secret"]),
            domainAndPort: string(object["domain_and_port"]),
            trackFolder: string(object["track_folder"]),
            incident: string(object["incident"]),
            operationalPeriod: string(object["op_period"]),
            trackerAPIKey: string(object["tracker_api_key"]),
            trackerURLPrefix: string(object["tracker_url_prefix"]),
            autoConnect: (object["auto_connect"] as? NSNumber)?.boolValue ?? true,
            expiresAtEpochMilliseconds: (object["expires_at_epoch_ms"] as? NSNumber)?.int64Value ?? 0,
            sourceLabel: string(object["source_label"]),
            targetMapID: string(object["target_map_id"]),
            targetMapTitle: string(object["target_map_title"]),
            targetFolderHint: string(object["target_folder_hint"])
        )
    }

    private static func decrypt(_ encoded: String) throws -> String {
        guard let data = Data(base64Encoded: encoded),
              let plaintext = String(data: Data(xor(Array(data))), encoding: .utf8)
        else { throw OrgConfigInteropError.invalidEncryptedPayload }
        return plaintext
    }

    private static func xor(_ input: [UInt8]) -> [UInt8] {
        input.enumerated().map { index, value in value ^ xorKey[index % xorKey.count] }
    }

    private static func string(_ value: Any?) -> String {
        (value as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

public enum OrgConfigTokenCodec {
    public static let magicPrefix = "R2C1:"
    public static let qrScheme = "r2c1"
    private static let xorKey = Array("RID2CaltopoQR".utf8)
    private static let standardAlphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
    private static let customAlphabet = Array("r2cNOPQRSTUVWXYZABCDEFGHIJKLMnopqstuvwxyzabdefghijklm013456789+/=")

    public static func normalize(_ rawValue: String) -> String {
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed),
              components.scheme?.lowercased() == qrScheme
        else { return trimmed }
        let payload = (components.host ?? "") + components.path
        return magicPrefix + payload.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    public static func decode(_ rawValue: String) -> OrgConfigJoinToken? {
        let token = normalize(rawValue)
        guard token.hasPrefix(magicPrefix) else { return nil }
        let encoded = String(token.dropFirst(magicPrefix.count))
        let remapped = encoded.map { character -> Character in
            guard let index = customAlphabet.firstIndex(of: character) else { return character }
            return standardAlphabet[index]
        }
        guard let xored = Data(base64Encoded: String(remapped)),
              let object = try? JSONSerialization.jsonObject(with: Data(xor(Array(xored)))) as? [String: Any],
              let fileID = object["f"] as? String,
              !fileID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return nil }
        return OrgConfigJoinToken(
            organizationName: object["o"] as? String ?? "",
            driveFileID: fileID,
            isPublic: number(object["p"])?.intValue != 0,
            version: number(object["v"])?.intValue ?? 1
        )
    }

    public static func encode(_ token: OrgConfigJoinToken) throws -> String {
        let object: [String: Any] = [
            "o": token.organizationName,
            "f": token.driveFileID,
            "p": token.isPublic ? 1 : 0,
            "v": token.version,
        ]
        let json = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        let base64 = Data(xor(Array(json))).base64EncodedString()
        let remapped = base64.map { character -> Character in
            guard let index = standardAlphabet.firstIndex(of: character) else { return character }
            return customAlphabet[index]
        }
        return magicPrefix + String(remapped)
    }

    public static func decryptPayload(_ encoded: String) throws -> String {
        guard let data = Data(base64Encoded: encoded),
              let plaintext = String(data: Data(xor(Array(data))), encoding: .utf8)
        else { throw OrgConfigInteropError.invalidEncryptedPayload }
        return plaintext
    }

    public static func parseBundle(_ data: Data) throws -> OrgConfigBundle {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              root["format"] as? String == "rid2caltopo_org_config",
              let configObjects = root["configs"] as? [[String: Any]],
              !configObjects.isEmpty
        else { throw OrgConfigInteropError.invalidBundle }
        let version = number(root["version"])?.intValue ?? 1
        guard version == 1 else { throw OrgConfigInteropError.unsupportedBundleVersion(version) }

        var mappings: [OrgConfigRIDMapping] = []
        var credentials: OrgConfigCredentials?
        var faaConfig: FaaSharedConfig?
        var mutualAidTemplate: MutualAidTemplateCredentials?
        var ignored: [String] = []

        for encryptedOrPlain in configObjects {
            let config: [String: Any]
            if encryptedOrPlain["type"] as? String == "ct_credentials_enc" {
                guard let encoded = encryptedOrPlain["enc"] as? String,
                      let decrypted = try? decryptPayload(encoded),
                      let data = decrypted.data(using: .utf8),
                      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
                else { throw OrgConfigInteropError.invalidEncryptedPayload }
                config = object
            } else {
                config = encryptedOrPlain
            }

            let type = (config["type"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            switch type {
            case "ct_ridmap":
                let entries = config["map"] as? [[String: Any]] ?? []
                mappings.append(contentsOf: entries.compactMap { entry in
                    let remoteID = string(entry["remoteId"])
                    guard !remoteID.isEmpty else { return nil }
                    return OrgConfigRIDMapping(
                        remoteID: remoteID,
                        mappedID: string(entry["mappedId"]),
                        organization: string(entry["org"]),
                        model: string(entry["model"]),
                        owner: string(entry["owner"])
                    )
                })
            case "ct_credentials":
                credentials = OrgConfigCredentials(
                    organizationName: string(config["org_name"]).isEmpty
                        ? string(root["org_name"])
                        : string(config["org_name"]),
                    teamID: string(config["team_id"]),
                    credentialID: string(config["credential_id"]),
                    credentialSecret: string(config["credential_secret"]),
                    domainAndPort: string(config["domain_and_port"]),
                    trackFolder: string(config["track_folder"]),
                    incident: string(config["incident"]),
                    operationalPeriod: string(config["op_period"]),
                    trackerAPIKey: string(config["tracker_api_key"]),
                    trackerURLPrefix: string(config["tracker_url_pfx"]).isEmpty
                        ? string(config["tracker_url_prefix"])
                        : string(config["tracker_url_pfx"]),
                    usePeers: number(config["use_peers"]).map { $0.boolValue },
                    predictiveHeadEnabled: number(config["predictive_head_enabled"])?.boolValue ?? true,
                    proximityAlertSpacingFeet: number(config["proximity_alert_spacing_feet"])?.intValue ?? 40
                )
            case "ct_faa_remote_config":
                if let payload = config["faa_payload_enc"] as? String,
                   let payloadData = payload.data(using: .utf8) {
                    faaConfig = try AndroidConfigTokenCodec.parseFaaBundle(payloadData)
                } else {
                    ignored.append(type)
                }
            case "ct_faa_credentials_enc":
                let encodedConfig = try JSONSerialization.data(withJSONObject: config)
                faaConfig = try AndroidConfigTokenCodec.parseFaaBundle(encodedConfig)
            case "ct_faa_credentials":
                let clientID = string(config["notam_client_id"])
                let clientSecret = string(config["notam_client_secret"])
                guard !clientID.isEmpty, !clientSecret.isEmpty else {
                    throw OrgConfigInteropError.invalidBundle
                }
                faaConfig = FaaSharedConfig(
                    sourceLabel: string(config["source_label"]).isEmpty
                        ? string(config["label"])
                        : string(config["source_label"]),
                    apiBaseURL: string(config["notam_api_base_url"]),
                    tokenURL: string(config["notam_token_url"]),
                    clientID: clientID,
                    clientSecret: clientSecret,
                    scope: string(config["notam_scope"])
                )
            case "ct_mutual_aid_credentials":
                mutualAidTemplate = MutualAidTemplateCredentials(
                    teamID: string(config["team_id"]),
                    credentialID: string(config["credential_id"]),
                    credentialSecret: string(config["credential_secret"]),
                    domainAndPort: string(config["domain_and_port"]),
                    sourceLabel: string(config["source_label"]).isEmpty
                        ? string(config["org_name"])
                        : string(config["source_label"]),
                    targetFolderHint: string(config["target_folder_hint"]).isEmpty
                        ? "MAI"
                        : string(config["target_folder_hint"])
                )
            default:
                if !type.isEmpty { ignored.append(type) }
            }
        }

        return OrgConfigBundle(
            organizationName: string(root["org_name"]),
            version: version,
            mappings: mappings,
            credentials: credentials,
            faaConfig: faaConfig,
            mutualAidTemplate: mutualAidTemplate,
            ignoredConfigTypes: ignored
        )
    }

    private static func xor(_ input: [UInt8]) -> [UInt8] {
        input.enumerated().map { index, value in value ^ xorKey[index % xorKey.count] }
    }

    private static func string(_ value: Any?) -> String {
        (value as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func number(_ value: Any?) -> NSNumber? {
        value as? NSNumber
    }
}
