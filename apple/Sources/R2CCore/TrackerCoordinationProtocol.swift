import Foundation

public enum TrackerCoordinationWireError: Error, Sendable, Equatable {
    case invalidEndpoint
    case invalidMessage
    case missingMessageType
}

public struct TrackerCoordinationClient: Sendable, Equatable {
    public let mapID: String
    public let zoneID: String
    public let name: String
    public let appVersion: String
    public let appVersionCode: Int

    public init(
        mapID: String,
        zoneID: String,
        name: String,
        appVersion: String,
        appVersionCode: Int
    ) {
        self.mapID = mapID
        self.zoneID = zoneID
        self.name = name
        self.appVersion = appVersion
        self.appVersionCode = appVersionCode
    }
}

public struct TrackerCoordinationPosition: Sendable, Equatable {
    public let latitude: Double
    public let longitude: Double
    public let caltopoRTTMilliseconds: Int64

    public init(latitude: Double, longitude: Double, caltopoRTTMilliseconds: Int64 = 2_000) {
        self.latitude = latitude
        self.longitude = longitude
        self.caltopoRTTMilliseconds = caltopoRTTMilliseconds
    }
}

public struct TrackerCoordinationIdentity: Sendable, Equatable {
    public let remoteID: String
    public let mappedID: String
    public let organization: String
    public let model: String
    public let ownerName: String

    public init(remoteID: String, mappedID: String, organization: String, model: String, ownerName: String) {
        self.remoteID = remoteID
        self.mappedID = mappedID
        self.organization = organization
        self.model = model
        self.ownerName = ownerName
    }
}

public struct TrackerCoordinationSighting: Sendable, Equatable {
    public let identity: TrackerCoordinationIdentity
    public let droneTimestampMilliseconds: Int64
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    public let distanceFromZoneMeters: Double?
    public let headingDegrees: Double?
    public let groundSpeedKnots: Double?
    public let verticalRateFeetPerMinute: Double?

    public init(
        identity: TrackerCoordinationIdentity,
        droneTimestampMilliseconds: Int64,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double? = nil,
        distanceFromZoneMeters: Double? = nil,
        headingDegrees: Double? = nil,
        groundSpeedKnots: Double? = nil,
        verticalRateFeetPerMinute: Double? = nil
    ) {
        self.identity = identity
        self.droneTimestampMilliseconds = droneTimestampMilliseconds
        self.latitude = latitude
        self.longitude = longitude
        self.altitudeMeters = altitudeMeters
        self.distanceFromZoneMeters = distanceFromZoneMeters
        self.headingDegrees = headingDegrees
        self.groundSpeedKnots = groundSpeedKnots
        self.verticalRateFeetPerMinute = verticalRateFeetPerMinute
    }
}

public struct TrackerPeerZone: Sendable, Equatable, Identifiable {
    public var id: String { zoneID }
    public let zoneID: String
    public let name: String
    public let latitude: Double
    public let longitude: Double
    public let caltopoRTTMilliseconds: Int64
    public let lastSeenMilliseconds: Int64
    public let online: Bool
}

public struct TrackerRelaySighting: Sendable, Equatable {
    public let sourceZoneID: String
    public let remoteID: String
    public let latitude: Double
    public let longitude: Double
    public let altitudeMeters: Double?
    public let droneTimestampMilliseconds: Int64
    public let headingDegrees: Double?
    public let groundSpeedKnots: Double?
    public let verticalRateFeetPerMinute: Double?
}

public enum TrackerCoordinationEvent: Sendable, Equatable {
    case helloAcknowledged(recommendedAppVersionCode: Int?, updateURL: String?)
    case heartbeatAcknowledged(sequence: Int64, ownerLeaseExpiresAtMilliseconds: Int64?)
    case peersUpdated([TrackerPeerZone])
    case ownershipChanged(remoteID: String, ownerZoneID: String, localOwner: Bool, alertEligible: Bool)
    case ownerExpired(remoteID: String)
    case relaySighting(TrackerRelaySighting)
    case droneConfirmed(identity: TrackerCoordinationIdentity, confirmedByZoneID: String, confirmedLocally: Bool)
    case reconnectRequired(reason: String)
    case ignored(messageType: String)
}

public enum TrackerCoordinationEndpoint {
    public static func webSocketURL(from trackerURLPrefix: String) throws -> URL {
        var value = trackerURLPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        while value.hasSuffix("/") { value.removeLast() }
        if value.hasPrefix("https://") {
            value = "wss://" + value.dropFirst("https://".count)
        } else if value.hasPrefix("http://") {
            value = "ws://" + value.dropFirst("http://".count)
        }
        guard var components = URLComponents(string: value),
              components.scheme == "ws" || components.scheme == "wss",
              components.host != nil
        else { throw TrackerCoordinationWireError.invalidEndpoint }
        let basePath = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        components.path = "/" + ([basePath, "ws/r2c"].filter { !$0.isEmpty }.joined(separator: "/"))
        guard let url = components.url else { throw TrackerCoordinationWireError.invalidEndpoint }
        return url
    }
}

public enum TrackerCoordinationWire {
    public static func hello(
        client: TrackerCoordinationClient,
        position: TrackerCoordinationPosition
    ) throws -> Data {
        try encode([
            "type": "hello",
            "mapId": client.mapID,
            "incidentId": client.mapID,
            "zoneId": client.zoneID,
            "guid": client.zoneID,
            "name": client.name,
            "lat": finite(position.latitude),
            "lng": finite(position.longitude),
            "appVersion": client.appVersion,
            "appVersionCode": client.appVersionCode,
            "caltopoRttMs": position.caltopoRTTMilliseconds,
        ])
    }

    public static func heartbeat(
        client: TrackerCoordinationClient,
        position: TrackerCoordinationPosition,
        sequence: Int64
    ) throws -> Data {
        try encode([
            "type": "heartbeat",
            "seq": sequence,
            "mapId": client.mapID,
            "zoneId": client.zoneID,
            "guid": client.zoneID,
            "name": client.name,
            "lat": finite(position.latitude),
            "lng": finite(position.longitude),
            "caltopoRttMs": position.caltopoRTTMilliseconds,
        ])
    }

    public static func firstSighting(
        client: TrackerCoordinationClient,
        sighting: TrackerCoordinationSighting
    ) throws -> Data {
        var object = baseSighting(client: client, sighting: sighting)
        object["type"] = "first_sighting"
        object["incidentId"] = client.mapID
        object["name"] = client.name
        object["org"] = sighting.identity.organization
        object["model"] = sighting.identity.model
        object["ownerName"] = sighting.identity.ownerName
        return try encode(object)
    }

    public static func sighting(
        client: TrackerCoordinationClient,
        sighting: TrackerCoordinationSighting
    ) throws -> Data {
        var object = baseSighting(client: client, sighting: sighting)
        object["type"] = "sighting"
        return try encode(object)
    }

    public static func droneLost(client: TrackerCoordinationClient, remoteID: String) throws -> Data {
        try encode([
            "type": "drone_lost",
            "mapId": client.mapID,
            "zoneId": client.zoneID,
            "remoteId": remoteID,
        ])
    }

    public static func droneConfirmed(
        client: TrackerCoordinationClient,
        identity: TrackerCoordinationIdentity
    ) throws -> Data {
        try encode([
            "type": "drone_confirmed",
            "mapId": client.mapID,
            "zoneId": client.zoneID,
            "guid": client.zoneID,
            "remoteId": identity.remoteID,
            "mappedId": identity.mappedID,
            "trackLabel": identity.mappedID,
            "org": identity.organization,
            "model": identity.model,
            "ownerName": identity.ownerName,
        ])
    }

    private static func baseSighting(
        client: TrackerCoordinationClient,
        sighting: TrackerCoordinationSighting
    ) -> [String: Any] {
        var object: [String: Any] = [
            "mapId": client.mapID,
            "zoneId": client.zoneID,
            "guid": client.zoneID,
            "remoteId": sighting.identity.remoteID,
            "mappedId": sighting.identity.mappedID,
            "trackLabel": sighting.identity.mappedID,
            "droneTs": sighting.droneTimestampMilliseconds,
            "lat": finite(sighting.latitude),
            "lng": finite(sighting.longitude),
        ]
        putFinite(sighting.altitudeMeters, key: "altM", into: &object)
        putFinite(sighting.distanceFromZoneMeters, key: "distanceFromZoneM", into: &object)
        var telemetry: [String: Any] = [:]
        putFinite(sighting.headingDegrees, key: "headingDeg", into: &telemetry)
        putFinite(sighting.groundSpeedKnots, key: "groundSpeedKnots", into: &telemetry)
        putFinite(sighting.verticalRateFeetPerMinute, key: "verticalRateFpm", into: &telemetry)
        if !telemetry.isEmpty { object["telemetry"] = telemetry }
        return object
    }

    private static func putFinite(_ value: Double?, key: String, into object: inout [String: Any]) {
        if let value, value.isFinite { object[key] = value }
    }

    private static func finite(_ value: Double) -> Double { value.isFinite ? value : 0 }

    private static func encode(_ object: [String: Any]) throws -> Data {
        guard JSONSerialization.isValidJSONObject(object) else { throw TrackerCoordinationWireError.invalidMessage }
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }
}

/// Stateful validation for tracker messages. Transport and timers remain platform adapters.
/// Publication and local alerts are allowed only when the tracker assigns this zone and the
/// operator has confirmed the drone locally, matching Android's safety contract.
public struct TrackerCoordinationProtocolState: Sendable {
    public let localZoneID: String
    private var ownersByRemoteID: [String: String] = [:]
    private var leaseSequencesByRemoteID: [String: Int64] = [:]
    private var locallyConfirmedRemoteIDs: Set<String> = []
    private var heartbeatSequence: Int64 = 0
    private var lastHeartbeatSequenceSent: Int64 = 0
    private var lastHeartbeatSequenceAcknowledged: Int64 = 0
    private var lastHeartbeatSentAtMilliseconds: Int64 = 0
    private var helloSentAtMilliseconds: Int64 = 0
    private var helloAcknowledgedAtMilliseconds: Int64 = 0

    public init(localZoneID: String) {
        self.localZoneID = localZoneID
    }

    public mutating func transportOpened(helloSentAtMilliseconds: Int64) {
        // A new transport must never inherit the prior socket's heartbeat watchdog state.
        heartbeatSequence = 0
        lastHeartbeatSequenceSent = 0
        lastHeartbeatSequenceAcknowledged = 0
        lastHeartbeatSentAtMilliseconds = 0
        helloAcknowledgedAtMilliseconds = 0
        self.helloSentAtMilliseconds = helloSentAtMilliseconds
    }

    public mutating func nextHeartbeatSequence(sentAtMilliseconds: Int64) -> Int64 {
        heartbeatSequence += 1
        lastHeartbeatSequenceSent = heartbeatSequence
        lastHeartbeatSentAtMilliseconds = sentAtMilliseconds
        return heartbeatSequence
    }

    public mutating func confirmLocally(remoteID: String) -> TrackerCoordinationEvent? {
        guard !remoteID.isEmpty else { return nil }
        locallyConfirmedRemoteIDs.insert(remoteID)
        guard let owner = ownersByRemoteID[remoteID] else { return nil }
        return ownershipEvent(remoteID: remoteID, ownerZoneID: owner)
    }

    public mutating func assignFallbackOwnership(remoteID: String) -> TrackerCoordinationEvent? {
        guard !remoteID.isEmpty else { return nil }
        ownersByRemoteID[remoteID] = localZoneID
        return ownershipEvent(remoteID: remoteID, ownerZoneID: localZoneID)
    }

    public func isLocalOwner(remoteID: String) -> Bool {
        ownersByRemoteID[remoteID] == localZoneID
    }

    public func isLocalAlertEligible(remoteID: String) -> Bool {
        isLocalOwner(remoteID: remoteID) && locallyConfirmedRemoteIDs.contains(remoteID)
    }

    public func requiresReconnectForMissingAcknowledgement(
        nowMilliseconds: Int64,
        timeoutMilliseconds: Int64 = 10_000
    ) -> String? {
        if helloSentAtMilliseconds > 0,
           helloAcknowledgedAtMilliseconds < helloSentAtMilliseconds,
           nowMilliseconds - helloSentAtMilliseconds > timeoutMilliseconds {
            return "missed hello_ack"
        }
        if lastHeartbeatSequenceSent > lastHeartbeatSequenceAcknowledged,
           lastHeartbeatSentAtMilliseconds > 0,
           nowMilliseconds - lastHeartbeatSentAtMilliseconds > timeoutMilliseconds {
            return "missed heartbeat_ack seq=\(lastHeartbeatSequenceSent)"
        }
        return nil
    }

    public mutating func handleIncoming(
        _ data: Data,
        receivedAtMilliseconds: Int64
    ) throws -> [TrackerCoordinationEvent] {
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw TrackerCoordinationWireError.invalidMessage
        }
        let type = string(object["type"])
        guard !type.isEmpty else { throw TrackerCoordinationWireError.missingMessageType }
        switch type {
        case "hello_ack":
            helloAcknowledgedAtMilliseconds = receivedAtMilliseconds
            let version = number(object["recommendedAppVersionCode"])?.intValue
            let updateURL = string(object["updateUrl"])
            return [.helloAcknowledged(
                recommendedAppVersionCode: version,
                updateURL: updateURL.isEmpty ? nil : updateURL
            )]
        case "heartbeat_ack":
            return [heartbeatAcknowledgement(object, receivedAtMilliseconds: receivedAtMilliseconds)]
        case "zone_update":
            return [.peersUpdated(parseZones(object["zones"], receivedAtMilliseconds: receivedAtMilliseconds))]
        case "owner_assigned":
            let remoteID = string(object["remoteId"])
            let owner = firstNonEmpty(string(object["ownerGuid"]), string(object["ownerZoneId"]))
            guard !remoteID.isEmpty, !owner.isEmpty else { throw TrackerCoordinationWireError.invalidMessage }
            let leaseSequence = number(object["leaseSeq"])?.int64Value ?? -1
            if let previous = leaseSequencesByRemoteID[remoteID], leaseSequence >= 0, leaseSequence < previous {
                return [.ignored(messageType: "stale owner_assigned")]
            }
            ownersByRemoteID[remoteID] = owner
            if leaseSequence >= 0 { leaseSequencesByRemoteID[remoteID] = leaseSequence }
            return [ownershipEvent(remoteID: remoteID, ownerZoneID: owner)]
        case "owner_expired":
            let remoteID = string(object["remoteId"])
            guard !remoteID.isEmpty else { throw TrackerCoordinationWireError.invalidMessage }
            let expectedOwner = firstNonEmpty(
                string(object["prevOwnerGuid"]),
                string(object["prevOwnerZoneId"])
            )
            if !expectedOwner.isEmpty,
               let currentOwner = ownersByRemoteID[remoteID],
               expectedOwner != currentOwner {
                return [.ignored(messageType: "stale owner_expired")]
            }
            ownersByRemoteID.removeValue(forKey: remoteID)
            leaseSequencesByRemoteID.removeValue(forKey: remoteID)
            locallyConfirmedRemoteIDs.remove(remoteID)
            return [.ownerExpired(remoteID: remoteID)]
        case "relay_sighting":
            guard let relay = parseRelay(object), isLocalOwner(remoteID: relay.remoteID), relay.sourceZoneID != localZoneID else {
                return [.ignored(messageType: type)]
            }
            return [.relaySighting(relay)]
        case "drone_confirmed":
            return handleDroneConfirmed(object)
        default:
            return [.ignored(messageType: type)]
        }
    }

    private mutating func heartbeatAcknowledgement(
        _ object: [String: Any],
        receivedAtMilliseconds: Int64
    ) -> TrackerCoordinationEvent {
        let sequence = number(object["clientSeq"])?.int64Value ?? -1
        guard sequence > 0 else { return .reconnectRequired(reason: "heartbeat_ack missing clientSeq") }
        if sequence < lastHeartbeatSequenceAcknowledged {
            return .ignored(messageType: "stale heartbeat_ack")
        }
        guard sequence == lastHeartbeatSequenceSent else {
            return .reconnectRequired(
                reason: "heartbeat_ack seq mismatch ack=\(sequence) expected=\(lastHeartbeatSequenceSent)"
            )
        }
        lastHeartbeatSequenceAcknowledged = sequence
        let leaseExpiry = number(object["ownerLeaseExpireTs"])?.int64Value
        _ = receivedAtMilliseconds
        return .heartbeatAcknowledged(
            sequence: sequence,
            ownerLeaseExpiresAtMilliseconds: leaseExpiry == 0 ? nil : leaseExpiry
        )
    }

    private mutating func handleDroneConfirmed(_ object: [String: Any]) -> [TrackerCoordinationEvent] {
        let remoteID = string(object["remoteId"])
        guard !remoteID.isEmpty else { return [.ignored(messageType: "invalid drone_confirmed")] }
        let confirmedBy = firstNonEmpty(
            string(object["confirmedByGuid"]),
            firstNonEmpty(string(object["guid"]), string(object["zoneId"]))
        )
        let confirmedLocally = confirmedBy == localZoneID
        if confirmedLocally {
            locallyConfirmedRemoteIDs.insert(remoteID)
        } else {
            locallyConfirmedRemoteIDs.remove(remoteID)
        }
        let identity = TrackerCoordinationIdentity(
            remoteID: remoteID,
            mappedID: string(object["mappedId"]),
            organization: string(object["org"]),
            model: string(object["model"]),
            ownerName: string(object["ownerName"])
        )
        var events: [TrackerCoordinationEvent] = [
            .droneConfirmed(identity: identity, confirmedByZoneID: confirmedBy, confirmedLocally: confirmedLocally),
        ]
        if !confirmedBy.isEmpty {
            ownersByRemoteID[remoteID] = confirmedBy
            let leaseSequence = number(object["leaseSeq"])?.int64Value ?? -1
            if leaseSequence >= 0 { leaseSequencesByRemoteID[remoteID] = leaseSequence }
            events.append(ownershipEvent(remoteID: remoteID, ownerZoneID: confirmedBy))
        }
        return events
    }

    private func ownershipEvent(remoteID: String, ownerZoneID: String) -> TrackerCoordinationEvent {
        let localOwner = ownerZoneID == localZoneID
        return .ownershipChanged(
            remoteID: remoteID,
            ownerZoneID: ownerZoneID,
            localOwner: localOwner,
            alertEligible: localOwner && locallyConfirmedRemoteIDs.contains(remoteID)
        )
    }

    private func parseZones(_ value: Any?, receivedAtMilliseconds: Int64) -> [TrackerPeerZone] {
        guard let zones = value as? [[String: Any]] else { return [] }
        return zones.compactMap { zone in
            let zoneID = firstNonEmpty(string(zone["guid"]), string(zone["zoneId"]))
            guard !zoneID.isEmpty, zoneID != localZoneID else { return nil }
            return TrackerPeerZone(
                zoneID: zoneID,
                name: firstNonEmpty(string(zone["name"]), zoneID),
                latitude: finiteNumber(zone["lat"]),
                longitude: finiteNumber(zone["lng"]),
                caltopoRTTMilliseconds: number(zone["caltopoRttMs"])?.int64Value ?? 2_000,
                lastSeenMilliseconds: number(zone["lastSeenMs"])?.int64Value ?? receivedAtMilliseconds,
                online: number(zone["online"])?.boolValue ?? true
            )
        }
    }

    private func parseRelay(_ object: [String: Any]) -> TrackerRelaySighting? {
        let remoteID = string(object["remoteId"])
        let source = firstNonEmpty(string(object["fromZoneId"]), string(object["zoneId"]))
        guard !remoteID.isEmpty, !source.isEmpty,
              let latitude = finiteOptionalNumber(object["lat"]),
              let longitude = finiteOptionalNumber(object["lng"])
        else { return nil }
        let telemetry = object["telemetry"] as? [String: Any]
        return TrackerRelaySighting(
            sourceZoneID: source,
            remoteID: remoteID,
            latitude: latitude,
            longitude: longitude,
            altitudeMeters: finiteOptionalNumber(object["altM"]),
            droneTimestampMilliseconds: number(object["droneTs"])?.int64Value ?? 0,
            headingDegrees: finiteOptionalNumber(telemetry?["headingDeg"]),
            groundSpeedKnots: finiteOptionalNumber(telemetry?["groundSpeedKnots"]),
            verticalRateFeetPerMinute: finiteOptionalNumber(telemetry?["verticalRateFpm"])
        )
    }

    private func string(_ value: Any?) -> String {
        (value as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func number(_ value: Any?) -> NSNumber? { value as? NSNumber }
    private func finiteNumber(_ value: Any?) -> Double { finiteOptionalNumber(value) ?? 0 }
    private func finiteOptionalNumber(_ value: Any?) -> Double? {
        guard let value = number(value)?.doubleValue, value.isFinite else { return nil }
        return value
    }
    private func firstNonEmpty(_ first: String, _ second: String) -> String { first.isEmpty ? second : first }
}
