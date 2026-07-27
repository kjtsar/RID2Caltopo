import Combine
import CoreLocation
import Foundation
import R2CCore
import UIKit

enum AppleTrackerCoordinationStatus: String, Sendable {
    case unconfigured = "Not configured"
    case standalone = "Standalone"
    case connecting = "Connecting"
    case healthy = "Healthy"
    case degraded = "Degraded"
}

@MainActor
final class AppleTrackerCoordinator: ObservableObject {
    nonisolated let events: AsyncStream<TrackerCoordinationEvent>
    private nonisolated let eventContinuation: AsyncStream<TrackerCoordinationEvent>.Continuation

    @Published private(set) var status: AppleTrackerCoordinationStatus = .unconfigured
    @Published private(set) var peers: [TrackerPeerZone] = []
    @Published private(set) var statusDetail = "Tracker coordination not configured"
    @Published private(set) var helloAcknowledgedAtMilliseconds: Int64 = 0
    @Published private(set) var heartbeatAcknowledgedAtMilliseconds: Int64 = 0
    @Published private(set) var heartbeatSequenceAcknowledged: Int64 = 0

    private var usePeers = false
    private var trackerURLPrefix = ""
    private var trackerAPIKey = ""
    private var mapID = ""
    private let zoneID: String
    private let zoneName: String
    private var client: TrackerCoordinationClient
    private var position = TrackerCoordinationPosition(latitude: 0, longitude: 0)
    private var protocolState: TrackerCoordinationProtocolState
    private var session: URLSession?
    private var socket: URLSessionWebSocketTask?
    private var socketDelegate: AppleTrackerWebSocketDelegate?
    private var generation = UUID()
    private var connected = false
    private var helloAcknowledged = false
    private var reconnectDelay: Duration = .seconds(2)
    private var receiveTask: Task<Void, Never>?
    private var receiveFailureTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var observedSightings: [String: TrackerCoordinationSighting] = [:]
    private var firstSightingsSent: Set<String> = []
    private var pendingConfirmations: [String: TrackerCoordinationIdentity] = [:]
    private var lastSightingSentAt: [String: Date] = [:]
    private var fallbackTasks: [String: Task<Void, Never>] = [:]

    init(defaults: UserDefaults = .standard) {
        let pair = AsyncStream<TrackerCoordinationEvent>.makeStream(bufferingPolicy: .bufferingNewest(256))
        events = pair.stream
        eventContinuation = pair.continuation
        if let existing = defaults.string(forKey: "tracker.zoneID"), !existing.isEmpty {
            zoneID = existing
        } else {
            let value = UUID().uuidString.lowercased()
            defaults.set(value, forKey: "tracker.zoneID")
            zoneID = value
        }
        zoneName = AppleDeviceIdentity.displayName
        client = Self.makeClient(mapID: "", zoneID: zoneID, zoneName: zoneName)
        protocolState = TrackerCoordinationProtocolState(localZoneID: zoneID)
    }

    deinit {
        eventContinuation.finish()
    }

    var coordinationRequired: Bool {
        usePeers && !trackerURLPrefix.isEmpty && !trackerAPIKey.isEmpty && !mapID.isEmpty
    }

    var localZoneID: String { zoneID }

    var localDeviceStatusLines: [String] {
        var lines = [statusDetail]
        if coordinationRequired {
            lines.append(
                helloAcknowledgedAtMilliseconds > 0
                    ? "Hello ack \(Self.elapsedText(since: helloAcknowledgedAtMilliseconds)) ago"
                    : "Hello ack waiting"
            )
            lines.append(
                heartbeatAcknowledgedAtMilliseconds > 0
                    ? "Heartbeat ack \(Self.elapsedText(since: heartbeatAcknowledgedAtMilliseconds)) ago (seq \(heartbeatSequenceAcknowledged))"
                    : "Heartbeat ack waiting"
            )
        }
        lines.append(peers.isEmpty ? "Peers: none" : "Peers: \(peers.count)")
        return lines
    }

    func configure(usePeers: Bool, trackerURLPrefix: String, trackerAPIKey: String, mapID: String) {
        let normalizedURL = trackerURLPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedKey = trackerAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedMapID = mapID.trimmingCharacters(in: .whitespacesAndNewlines)
        let unchanged = self.usePeers == usePeers
            && self.trackerURLPrefix == normalizedURL
            && self.trackerAPIKey == normalizedKey
            && self.mapID == normalizedMapID
        guard !unchanged else { return }
        stopTransport()
        self.usePeers = usePeers
        self.trackerURLPrefix = normalizedURL
        self.trackerAPIKey = normalizedKey
        self.mapID = normalizedMapID
        client = Self.makeClient(mapID: normalizedMapID, zoneID: zoneID, zoneName: zoneName)
        protocolState = TrackerCoordinationProtocolState(localZoneID: zoneID)
        resetAcknowledgements()
        observedSightings.removeAll()
        pendingConfirmations.removeAll()
        fallbackTasks.values.forEach { $0.cancel() }
        fallbackTasks.removeAll()

        guard usePeers else {
            status = .standalone
            statusDetail = "Peer coordination disabled by configuration"
            return
        }
        guard coordinationRequired else {
            status = .unconfigured
            statusDetail = missingConfigurationDescription
            return
        }
        connect()
    }

    func updatePosition(_ location: CLLocation?) {
        guard let location else { return }
        position = TrackerCoordinationPosition(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            caltopoRTTMilliseconds: position.caltopoRTTMilliseconds
        )
    }

    func updateCaltopoRTT(milliseconds: Int64?) {
        guard let milliseconds, milliseconds >= 0 else { return }
        position = TrackerCoordinationPosition(
            latitude: position.latitude,
            longitude: position.longitude,
            caltopoRTTMilliseconds: milliseconds
        )
    }

    func observe(track: RidAircraftTrack, identity: RidAircraftIdentity?) {
        guard coordinationRequired else { return }
        let resolved = identity.map(Self.trackerIdentity) ?? TrackerCoordinationIdentity(
            remoteID: track.aircraftID,
            mappedID: track.aircraftID,
            organization: "",
            model: "",
            ownerName: ""
        )
        let range = position.latitude == 0 && position.longitude == 0
            ? nil
            : RidGeometry.relativePosition(
                fromLatitude: position.latitude,
                longitude: position.longitude,
                toLatitude: track.lastObservation.latitude,
                longitude: track.lastObservation.longitude
            )?.distanceMeters
        let sighting = TrackerCoordinationSighting(
            identity: resolved,
            droneTimestampMilliseconds: Int64(track.lastObservation.receivedAt.timeIntervalSince1970 * 1_000),
            latitude: track.lastObservation.latitude,
            longitude: track.lastObservation.longitude,
            altitudeMeters: track.lastObservation.altitudeMeters,
            distanceFromZoneMeters: range,
            headingDegrees: track.lastObservation.headingDegrees,
            groundSpeedKnots: track.lastObservation.speedMetersPerSecond.map { $0 * 1.943_844 }
        )
        let isFirst = observedSightings[track.aircraftID] == nil
        observedSightings[track.aircraftID] = sighting
        if isFirst {
            scheduleFallbackOwnership(remoteID: track.aircraftID)
            sendFirstSightingIfNeeded(remoteID: track.aircraftID)
        } else {
            sendSightingIfEligible(sighting)
        }
    }

    func confirm(_ identity: RidAircraftIdentity) {
        guard coordinationRequired else { return }
        let trackerIdentity = Self.trackerIdentity(identity)
        pendingConfirmations[identity.remoteID] = trackerIdentity
        if let event = protocolState.confirmLocally(remoteID: identity.remoteID) {
            publish(event)
        }
        flushPendingConfirmations()
    }

    func droneLost(remoteID: String) {
        observedSightings.removeValue(forKey: remoteID)
        firstSightingsSent.remove(remoteID)
        pendingConfirmations.removeValue(forKey: remoteID)
        lastSightingSentAt.removeValue(forKey: remoteID)
        fallbackTasks.removeValue(forKey: remoteID)?.cancel()
        guard coordinationRequired,
              let data = try? TrackerCoordinationWire.droneLost(client: client, remoteID: remoteID)
        else { return }
        send(data)
    }

    func publicationAllowed(remoteID: String) -> Bool {
        !coordinationRequired || protocolState.isLocalAlertEligible(remoteID: remoteID)
    }

    func isLocalAlertEligible(remoteID: String) -> Bool {
        coordinationRequired && protocolState.isLocalAlertEligible(remoteID: remoteID)
    }

    private func connect() {
        guard coordinationRequired,
              let url = try? TrackerCoordinationEndpoint.webSocketURL(from: trackerURLPrefix)
        else { return }
        stopSocketOnly()
        status = .connecting
        statusDetail = "Connecting to tracker"
        connected = false
        helloAcknowledged = false
        generation = UUID()
        let currentGeneration = generation
        let delegate = AppleTrackerWebSocketDelegate(
            onOpen: { [weak self] in
                Task { @MainActor [weak self] in self?.socketOpened(generation: currentGeneration) }
            },
            onClose: { [weak self] code, reason in
                Task { @MainActor [weak self] in
                    self?.socketClosed(generation: currentGeneration, detail: "Closed \(code): \(reason)")
                }
            }
        )
        socketDelegate = delegate
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 20
        let session = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
        self.session = session
        var request = URLRequest(url: url)
        request.timeoutInterval = 20
        request.setValue("RID2Caltopo/coordination", forHTTPHeaderField: "User-Agent")
        request.setValue(trackerAPIKey, forHTTPHeaderField: "X-SAR-Token")
        let socket = session.webSocketTask(with: request)
        self.socket = socket
        socket.resume()
    }

    private func startReceiveLoop(
        socket: URLSessionWebSocketTask,
        generation currentGeneration: UUID
    ) {
        receiveTask?.cancel()
        receiveTask = Task { [weak self, weak socket] in
            guard let socket else { return }
            do {
                while !Task.isCancelled {
                    let message = try await socket.receive()
                    guard let self else { return }
                    self.received(message, generation: currentGeneration)
                }
            } catch is CancellationError {
                return
            } catch {
                guard let self else { return }
                self.receiveFailed(error, generation: currentGeneration)
            }
        }
    }

    private func receiveFailed(_ error: Error, generation currentGeneration: UUID) {
        guard currentGeneration == generation, coordinationRequired else { return }
        let detail = "Receive failed: \(Self.errorDescription(error))"
        status = .degraded
        statusDetail = detail
        AppleLog.error("TrackerPeer", "\(detail); awaiting WebSocket close reason")
        receiveFailureTask?.cancel()
        receiveFailureTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(750))
            guard !Task.isCancelled, let self, currentGeneration == self.generation else { return }
            self.receiveFailureTask = nil
            self.socketClosed(generation: currentGeneration, detail: detail)
        }
    }

    private func socketOpened(generation: UUID) {
        guard generation == self.generation, let socket else { return }
        connected = true
        // Match Android's WebSocketListener lifecycle: no reads or protocol
        // messages are started until the transport has reported onOpen.
        startReceiveLoop(socket: socket, generation: generation)
        let now = Self.nowMilliseconds
        protocolState.transportOpened(helloSentAtMilliseconds: now)
        firstSightingsSent.removeAll()
        status = .connecting
        statusDetail = "Waiting for tracker hello"
        if let hello = try? TrackerCoordinationWire.hello(client: client, position: position) {
            send(hello)
        }
        replayFirstSightings()
        flushPendingConfirmations()
        startLivenessTasks(generation: generation)
        AppleLog.info("TrackerPeer", "WebSocket opened mapId='\(mapID)' zoneId='\(zoneID)'")
    }

    private func received(_ message: URLSessionWebSocketTask.Message, generation: UUID) {
        guard generation == self.generation else { return }
        let data: Data
        switch message {
        case let .data(value): data = value
        case let .string(value): data = Data(value.utf8)
        @unknown default: return
        }
        do {
            let events = try protocolState.handleIncoming(data, receivedAtMilliseconds: Self.nowMilliseconds)
            for event in events {
                switch event {
                case .helloAcknowledged:
                    helloAcknowledged = true
                    reconnectDelay = .seconds(2)
                    helloAcknowledgedAtMilliseconds = Self.nowMilliseconds
                    status = .healthy
                    statusDetail = "Tracker link healthy"
                    flushPendingConfirmations()
                case let .heartbeatAcknowledged(sequence, _):
                    heartbeatAcknowledgedAtMilliseconds = Self.nowMilliseconds
                    heartbeatSequenceAcknowledged = sequence
                case let .peersUpdated(updatedPeers):
                    peers = updatedPeers
                case let .reconnectRequired(reason):
                    reconnect(reason: reason)
                default:
                    break
                }
                if case let .droneConfirmed(_, confirmedByZoneID, confirmedLocally) = event,
                   confirmedLocally,
                   confirmedByZoneID == zoneID {
                    if case let .droneConfirmed(identity, _, _) = event {
                        pendingConfirmations.removeValue(forKey: identity.remoteID)
                    }
                }
                publish(event)
            }
        } catch {
            AppleLog.error("TrackerPeer", "Invalid tracker message: \(error.localizedDescription)")
        }
    }

    private func startLivenessTasks(generation: UUID) {
        heartbeatTask?.cancel()
        watchdogTask?.cancel()
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(15))
                guard !Task.isCancelled, let self, generation == self.generation, self.connected else { return }
                let sequence = self.protocolState.nextHeartbeatSequence(sentAtMilliseconds: Self.nowMilliseconds)
                if let data = try? TrackerCoordinationWire.heartbeat(
                    client: self.client,
                    position: self.position,
                    sequence: sequence
                ) {
                    self.send(data)
                }
            }
        }
        watchdogTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                guard !Task.isCancelled, let self, generation == self.generation, self.connected else { return }
                if let reason = self.protocolState.requiresReconnectForMissingAcknowledgement(
                    nowMilliseconds: Self.nowMilliseconds
                ) {
                    self.reconnect(reason: reason)
                    return
                }
            }
        }
    }

    private func socketClosed(generation: UUID, detail: String) {
        guard generation == self.generation, coordinationRequired else { return }
        receiveFailureTask?.cancel()
        receiveFailureTask = nil
        connected = false
        helloAcknowledged = false
        heartbeatTask?.cancel()
        watchdogTask?.cancel()
        status = .degraded
        statusDetail = detail
        AppleLog.error("TrackerPeer", detail)
        scheduleReconnect()
    }

    private func reconnect(reason: String) {
        guard coordinationRequired else { return }
        status = .degraded
        statusDetail = reason
        stopSocketOnly()
        scheduleReconnect()
    }

    private func scheduleReconnect() {
        guard coordinationRequired, reconnectTask == nil else { return }
        let delay = reconnectDelay
        reconnectDelay = min(reconnectDelay * 2, .seconds(10))
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self else { return }
            self.reconnectTask = nil
            self.connect()
        }
    }

    private func scheduleFallbackOwnership(remoteID: String) {
        fallbackTasks[remoteID]?.cancel()
        fallbackTasks[remoteID] = Task { [weak self] in
            try? await Task.sleep(for: .seconds(12))
            guard !Task.isCancelled, let self,
                  self.observedSightings[remoteID] != nil,
                  !self.helloAcknowledged
            else { return }
            if let event = self.protocolState.assignFallbackOwnership(remoteID: remoteID) {
                self.publish(event)
                AppleLog.info("TrackerPeer", "Fallback local ownership remoteId=\(remoteID)")
            }
        }
    }

    private func sendFirstSightingIfNeeded(remoteID: String) {
        guard connected, !firstSightingsSent.contains(remoteID),
              let sighting = observedSightings[remoteID],
              let data = try? TrackerCoordinationWire.firstSighting(client: client, sighting: sighting)
        else { return }
        send(data)
        firstSightingsSent.insert(remoteID)
    }

    private func replayFirstSightings() {
        for remoteID in observedSightings.keys.sorted() { sendFirstSightingIfNeeded(remoteID: remoteID) }
    }

    private func sendSightingIfEligible(_ sighting: TrackerCoordinationSighting) {
        guard connected else { return }
        let remoteID = sighting.identity.remoteID
        if !protocolState.isLocalOwner(remoteID: remoteID),
           let lastSent = lastSightingSentAt[remoteID],
           Date().timeIntervalSince(lastSent) < 3 {
            return
        }
        guard let data = try? TrackerCoordinationWire.sighting(client: client, sighting: sighting) else { return }
        send(data)
        lastSightingSentAt[remoteID] = Date()
    }

    private func flushPendingConfirmations() {
        guard connected else { return }
        for (remoteID, identity) in pendingConfirmations {
            guard let data = try? TrackerCoordinationWire.droneConfirmed(client: client, identity: identity) else { continue }
            send(data)
            AppleLog.info("TrackerPeer", "Sent pending drone confirmation remoteId=\(remoteID)")
        }
    }

    private func send(_ data: Data) {
        guard connected, let socket, let text = String(data: data, encoding: .utf8) else { return }
        let currentGeneration = generation
        socket.send(.string(text)) { [weak self] error in
            guard let error else { return }
            Task { @MainActor [weak self] in
                guard let self, currentGeneration == self.generation else { return }
                self.socketClosed(
                    generation: currentGeneration,
                    detail: "Send failed: \(Self.errorDescription(error))"
                )
            }
        }
    }

    private func publish(_ event: TrackerCoordinationEvent) {
        switch event {
        case let .ownershipChanged(remoteID, ownerZoneID, localOwner, alertEligible):
            AppleLog.info(
                "TrackerPeer",
                "Ownership remoteId=\(remoteID) ownerZoneId=\(ownerZoneID) local=\(localOwner) alertEligible=\(alertEligible)"
            )
        case let .ownerExpired(remoteID):
            AppleLog.info("TrackerPeer", "Owner expired remoteId=\(remoteID)")
        case let .peersUpdated(peers):
            AppleLog.info("TrackerPeer", "Peer zones updated count=\(peers.count)")
        case let .relaySighting(relay):
            AppleLog.info(
                "TrackerPeer",
                "Relay sighting remoteId=\(relay.remoteID) fromZoneId=\(relay.sourceZoneID)"
            )
        default:
            break
        }
        eventContinuation.yield(event)
    }

    private func stopTransport() {
        reconnectTask?.cancel()
        reconnectTask = nil
        stopSocketOnly()
        peers = []
        resetAcknowledgements()
    }

    private func stopSocketOnly() {
        generation = UUID()
        connected = false
        helloAcknowledged = false
        receiveTask?.cancel()
        receiveFailureTask?.cancel()
        heartbeatTask?.cancel()
        watchdogTask?.cancel()
        receiveTask = nil
        receiveFailureTask = nil
        heartbeatTask = nil
        watchdogTask = nil
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        session?.invalidateAndCancel()
        session = nil
        socketDelegate = nil
    }

    private var missingConfigurationDescription: String {
        if trackerURLPrefix.isEmpty || trackerAPIKey.isEmpty { return "Tracker URL or API key missing" }
        if mapID.isEmpty { return "Select the incident Map ID for coordination" }
        return "Tracker coordination not configured"
    }

    private func resetAcknowledgements() {
        helloAcknowledgedAtMilliseconds = 0
        heartbeatAcknowledgedAtMilliseconds = 0
        heartbeatSequenceAcknowledged = 0
    }

    private static func elapsedText(since milliseconds: Int64) -> String {
        let seconds = max(0, (nowMilliseconds - milliseconds) / 1_000)
        if seconds < 60 { return "\(seconds) sec" }
        let minutes = seconds / 60
        if minutes < 60 { return "\(minutes) min" }
        return "\(minutes / 60) hr"
    }

    private static func errorDescription(_ error: Error) -> String {
        let value = error as NSError
        return "\(value.localizedDescription) [\(value.domain) \(value.code)]"
    }

    private static var nowMilliseconds: Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }

    private static func trackerIdentity(_ identity: RidAircraftIdentity) -> TrackerCoordinationIdentity {
        TrackerCoordinationIdentity(
            remoteID: identity.remoteID,
            mappedID: identity.mappedID,
            organization: identity.organization,
            model: identity.droneDescription,
            ownerName: identity.pilotCallsign
        )
    }

    private static func makeClient(mapID: String, zoneID: String, zoneName: String) -> TrackerCoordinationClient {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        let build = Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0") ?? 0
        return TrackerCoordinationClient(
            mapID: mapID,
            zoneID: zoneID,
            name: zoneName,
            appVersion: "\(version)(\(build))",
            appVersionCode: build
        )
    }
}

private final class AppleTrackerWebSocketDelegate: NSObject, URLSessionWebSocketDelegate, @unchecked Sendable {
    private let onOpen: @Sendable () -> Void
    private let onClose: @Sendable (Int, String) -> Void

    init(onOpen: @escaping @Sendable () -> Void, onClose: @escaping @Sendable (Int, String) -> Void) {
        self.onOpen = onOpen
        self.onClose = onClose
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        onOpen()
    }

    func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        onClose(Int(closeCode.rawValue), reason.flatMap { String(data: $0, encoding: .utf8) } ?? "")
    }
}
