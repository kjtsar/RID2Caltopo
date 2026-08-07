import Foundation
@preconcurrency import WebRTC

struct AppleVideoICEServer: Decodable, Sendable {
    let urls: [String]
    let username: String?
    let credential: String?

    private enum CodingKeys: String, CodingKey {
        case urls
        case username
        case credential
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        if let values = try? container.decode([String].self, forKey: .urls) {
            urls = values
        } else {
            urls = [try container.decode(String.self, forKey: .urls)]
        }
        username = try container.decodeIfPresent(String.self, forKey: .username)
        credential = try container.decodeIfPresent(String.self, forKey: .credential)
    }
}

private struct AppleVideoPreflightAcknowledgement: Decodable {
    let type: String
    let receivedBytes: Int64
}

/// One-shot data-channel peer used only for the consent-safe link estimate.
///
/// No audio or video sender, receiver, or track is created by this type.
final class AppleManagedVideoPreflightPeer: NSObject, @unchecked Sendable {
    typealias AnswerSink = @Sendable (String, String) -> Void
    typealias ResultSink = @Sendable (String, String, Int64) -> Void
    typealias FailureSink = @Sendable (String, String) -> Void

    private static let chunkBytes = 16 * 1024
    private static let maximumBufferedBytes: UInt64 = 2 * 1024 * 1024
    private static let probeDuration: TimeInterval = 4.0
    private static let probeWarmupDuration: TimeInterval = 0.5
    private static let connectionTimeout: TimeInterval = 12.0
    private static let safetyMargin = 0.75
    private static let sslInitialized = RTCInitializeSSL()

    private let answerSink: AnswerSink
    private let resultSink: ResultSink
    private let failureSink: FailureSink
    private let queue = DispatchQueue(label: "org.ncssar.r2c.video-preflight")
    private let factory: RTCPeerConnectionFactory

    private var peer: RTCPeerConnection?
    private var channel: RTCDataChannel?
    private var probeTimer: DispatchSourceTimer?
    private var requestID = ""
    private var answerSent = false
    private var probeStartedAt: TimeInterval = 0
    private var nextSequence: UInt32 = 0
    private var acknowledgedBytes: Int64 = 0
    private var acknowledgedBytesAfterWarmup: Int64 = 0
    private var probeStarted = false
    private var resultSent = false

    init(
        answerSink: @escaping AnswerSink,
        resultSink: @escaping ResultSink,
        failureSink: @escaping FailureSink
    ) {
        _ = Self.sslInitialized
        factory = RTCPeerConnectionFactory()
        self.answerSink = answerSink
        self.resultSink = resultSink
        self.failureSink = failureSink
    }

    func start(
        requestID: String,
        offerSDP: String,
        iceServers: [AppleVideoICEServer]
    ) {
        queue.async { [weak self] in
            self?.startOnQueue(
                requestID: requestID,
                offerSDP: offerSDP,
                iceServers: iceServers
            )
        }
    }

    func cancel() {
        queue.async { [weak self] in
            self?.closeOnQueue()
        }
    }

    private func startOnQueue(
        requestID: String,
        offerSDP: String,
        iceServers: [AppleVideoICEServer]
    ) {
        closeOnQueue()
        self.requestID = requestID
        answerSent = false
        probeStarted = false
        resultSent = false
        acknowledgedBytes = 0
        acknowledgedBytesAfterWarmup = 0
        nextSequence = 0

        let configuration = RTCConfiguration()
        configuration.sdpSemantics = .unifiedPlan
        // The browser offer is relay-only, so any selected pair remains
        // routed. Let the tablet contribute its fastest reachable candidate
        // instead of requiring a second TURN allocation.
        configuration.iceTransportPolicy = .all
        configuration.iceServers = iceServers.map {
            RTCIceServer(
                urlStrings: $0.urls,
                username: $0.username,
                credential: $0.credential
            )
        }
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: nil
        )
        guard let peer = factory.peerConnection(
            with: configuration,
            constraints: constraints,
            delegate: self
        ) else {
            failOnQueue("Unable to create WebRTC peer.")
            return
        }
        self.peer = peer
        AppleLog.info(
            "VideoPreflight",
            "Peer created request=\(requestID) iceServers=\(iceServers.count)"
        )
        queue.asyncAfter(
            deadline: .now() + Self.connectionTimeout
        ) { [weak self] in
            guard
                let self,
                self.requestID == requestID,
                self.peer != nil,
                !self.probeStarted,
                !self.resultSent
            else {
                return
            }
            let iceState = self.peer.map {
                String(describing: $0.iceConnectionState)
            } ?? "closed"
            let channelState = self.channel.map {
                String(describing: $0.readyState)
            } ?? "not-created"
            self.failOnQueue(
                "WebRTC test channel did not open within 12 seconds "
                    + "(ICE \(iceState), channel \(channelState))."
            )
        }
        let offer = RTCSessionDescription(type: .offer, sdp: offerSDP)
        peer.setRemoteDescription(offer) { [weak self] error in
            guard let owner = self else { return }
            owner.queue.async { [owner] in
                if let error {
                    owner.failOnQueue(
                        "Unable to apply the WebRTC offer: "
                            + Self.describe(error)
                    )
                    return
                }
                peer.answer(for: constraints) { answer, error in
                    owner.queue.async { [owner] in
                        guard error == nil, let answer else {
                            owner.failOnQueue(
                                "Unable to create the WebRTC answer"
                                    + Self.errorSuffix(error)
                            )
                            return
                        }
                        peer.setLocalDescription(answer) { error in
                            owner.queue.async { [owner] in
                                if let error {
                                    owner.failOnQueue(
                                        "Unable to apply the WebRTC answer: "
                                            + Self.describe(error)
                                    )
                                } else {
                                    owner.maybeSendAnswerOnQueue()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static func errorSuffix(_ error: Error?) -> String {
        guard let error else { return "." }
        return ": \(describe(error))"
    }

    private static func describe(_ error: Error) -> String {
        let value = error as NSError
        return "\(value.localizedDescription) "
            + "[\(value.domain) \(value.code)]"
    }

    private func maybeSendAnswerOnQueue(allowPartialGathering: Bool = false) {
        guard
            !answerSent,
            let peer,
            allowPartialGathering || peer.iceGatheringState == .complete,
            let localDescription = peer.localDescription,
            !localDescription.sdp.isEmpty
        else {
            return
        }
        answerSent = true
        AppleLog.info(
            "VideoPreflight",
            "Sending gathered answer request=\(requestID)"
        )
        answerSink(requestID, localDescription.sdp)
    }

    private func attachOnQueue(_ dataChannel: RTCDataChannel) {
        channel = dataChannel
        dataChannel.delegate = self
        AppleLog.info(
            "VideoPreflight",
            "Remote data channel received request=\(requestID) "
                + "state=\(String(describing: dataChannel.readyState))"
        )
        if dataChannel.readyState == .open {
            startProbeOnQueue()
        }
    }

    private func startProbeOnQueue() {
        guard !probeStarted, let channel, channel.readyState == .open else {
            return
        }
        probeStarted = true
        AppleLog.info(
            "VideoPreflight",
            "Synthetic probe started request=\(requestID)"
        )
        probeStartedAt = Date.timeIntervalSinceReferenceDate
        queue.asyncAfter(
            deadline: .now() + Self.probeWarmupDuration
        ) { [weak self] in
            guard let self, self.probeStarted, !self.resultSent else { return }
            self.acknowledgedBytesAfterWarmup = self.acknowledgedBytes
        }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: .milliseconds(2))
        timer.setEventHandler { [weak self] in
            self?.sendProbeBurstOnQueue()
        }
        probeTimer = timer
        timer.resume()
        queue.asyncAfter(
            deadline: .now() + Self.probeDuration + 0.25
        ) { [weak self] in
            self?.finishProbeOnQueue()
        }
    }

    private func sendProbeBurstOnQueue() {
        guard let channel, channel.readyState == .open else { return }
        let elapsed = Date.timeIntervalSinceReferenceDate - probeStartedAt
        guard elapsed < Self.probeDuration else {
            probeTimer?.cancel()
            probeTimer = nil
            return
        }
        for _ in 0..<16 where channel.bufferedAmount < Self.maximumBufferedBytes {
            var sequence = nextSequence.bigEndian
            var payload = Data(bytes: &sequence, count: MemoryLayout<UInt32>.size)
            payload.append(Data(count: Self.chunkBytes - payload.count))
            guard channel.sendData(RTCDataBuffer(data: payload, isBinary: true)) else {
                return
            }
            nextSequence &+= 1
        }
    }

    private func finishProbeOnQueue() {
        probeTimer?.cancel()
        probeTimer = nil
        guard !resultSent, let peer else { return }
        resultSent = true
        peer.statistics { [weak self] report in
            guard let owner = self else { return }
            owner.queue.async { [owner] in
                owner.finishOnQueue(report)
            }
        }
    }

    private func finishOnQueue(_ report: RTCStatisticsReport) {
        let route = selectedRoute(in: report)
        let elapsed = max(
            0.001,
            min(
                Self.probeDuration - Self.probeWarmupDuration,
                Date.timeIntervalSinceReferenceDate - probeStartedAt
                    - Self.probeWarmupDuration
            )
        )
        let measuredBytes = max(
            0,
            acknowledgedBytes - acknowledgedBytesAfterWarmup
        )
        let rawBitsPerSecond = Double(measuredBytes) * 8.0 / elapsed
        let usableBitsPerSecond = Int64(rawBitsPerSecond * Self.safetyMargin)
        guard let route, usableBitsPerSecond > 0 else {
            failOnQueue("WebRTC link measurement did not produce a usable result.")
            return
        }
        resultSink(requestID, route, usableBitsPerSecond)
        AppleLog.info(
            "VideoPreflight",
            "Synthetic probe completed request=\(requestID) "
                + "route=\(route) acknowledgedBytes=\(measuredBytes) "
                + "elapsedMs=\(Int(elapsed * 1_000)) "
                + "rawBps=\(Int64(rawBitsPerSecond)) "
                + "usableBps=\(usableBitsPerSecond) "
                + "bufferedBytes=\(channel?.bufferedAmount ?? 0)"
        )
        closeOnQueue()
    }

    private func selectedRoute(in report: RTCStatisticsReport) -> String? {
        let statistics = report.statistics
        let selectedPairID = statistics.values
            .first(where: { $0.type == "transport" })?
            .values["selectedCandidatePairId"] as? String
        guard
            let selectedPairID,
            let pair = statistics[selectedPairID],
            let localID = pair.values["localCandidateId"] as? String,
            let remoteID = pair.values["remoteCandidateId"] as? String,
            let local = statistics[localID],
            let remote = statistics[remoteID],
            let localType = local.values["candidateType"] as? String,
            let remoteType = remote.values["candidateType"] as? String
        else {
            return nil
        }
        return localType == "relay" || remoteType == "relay"
            ? "routed"
            : "direct"
    }

    private func failOnQueue(_ reason: String) {
        if !requestID.isEmpty {
            AppleLog.warning(
                "VideoPreflight",
                "Preflight failed request=\(requestID): \(reason)"
            )
            failureSink(requestID, reason)
        }
        closeOnQueue()
    }

    private func closeOnQueue() {
        probeTimer?.cancel()
        probeTimer = nil
        channel?.delegate = nil
        channel?.close()
        channel = nil
        peer?.close()
        peer = nil
        requestID = ""
    }
}

extension AppleManagedVideoPreflightPeer: RTCPeerConnectionDelegate {
    func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didChange stateChanged: RTCSignalingState
    ) {}

    func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didAdd stream: RTCMediaStream
    ) {}

    func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didRemove stream: RTCMediaStream
    ) {}

    func peerConnectionShouldNegotiate(
        _ peerConnection: RTCPeerConnection
    ) {}

    func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didChange newState: RTCIceConnectionState
    ) {
        AppleLog.info(
            "VideoPreflight",
            "ICE state request=\(requestID) state=\(String(describing: newState))"
        )
    }

    func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didChange newState: RTCIceGatheringState
    ) {
        AppleLog.debug(
            "VideoPreflight",
            "ICE gathering request=\(requestID) "
                + "state=\(String(describing: newState))"
        )
        guard newState == .complete else { return }
        queue.async { [weak self] in
            self?.maybeSendAnswerOnQueue()
        }
    }

    func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didGenerate candidate: RTCIceCandidate
    ) {
        guard candidate.sdp.contains(" typ srflx ")
            || candidate.sdp.contains(" typ relay ")
        else { return }
        queue.asyncAfter(deadline: .now() + .milliseconds(50)) { [weak self] in
            self?.maybeSendAnswerOnQueue(allowPartialGathering: true)
        }
    }

    func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didRemove candidates: [RTCIceCandidate]
    ) {}

    func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didOpen dataChannel: RTCDataChannel
    ) {
        AppleLog.info(
            "VideoPreflight",
            "Data-channel callback request=\(requestID)"
        )
        queue.async { [weak self] in
            self?.attachOnQueue(dataChannel)
        }
    }
}

extension AppleManagedVideoPreflightPeer: RTCDataChannelDelegate {
    func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        AppleLog.info(
            "VideoPreflight",
            "Data-channel state request=\(requestID) "
                + "state=\(String(describing: dataChannel.readyState))"
        )
        guard dataChannel.readyState == .open else { return }
        queue.async { [weak self] in
            self?.startProbeOnQueue()
        }
    }

    func dataChannel(
        _ dataChannel: RTCDataChannel,
        didReceiveMessageWith buffer: RTCDataBuffer
    ) {
        guard !buffer.isBinary else { return }
        queue.async { [weak self] in
            guard
                let self,
                let acknowledgement = try? JSONDecoder().decode(
                    AppleVideoPreflightAcknowledgement.self,
                    from: buffer.data
                ),
                acknowledgement.type == "ack"
            else {
                return
            }
            self.acknowledgedBytes = max(
                self.acknowledgedBytes,
                acknowledgement.receivedBytes
            )
        }
    }
}
