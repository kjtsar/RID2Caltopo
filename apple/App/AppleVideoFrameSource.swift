import AVFoundation
import Combine
import CoreVideo
import CoreImage
import Dispatch
import QuartzCore
import R2CCore

private struct PixelBufferTransfer: @unchecked Sendable {
    let value: CVPixelBuffer
}

struct AppleVideoSnapshot: Sendable, Equatable {
    let jpegData: Data
    let capturedAt: Date
    let width: Int
    let height: Int
}

enum AppleVideoSnapshotError: LocalizedError {
    case noDecodedFrame
    case conversionFailed

    var errorDescription: String? {
        switch self {
        case .noDecodedFrame: "No decoded video frame is available yet."
        case .conversionFailed: "The decoded frame could not be converted to a snapshot."
        }
    }
}

struct AppleAnomalyBox: Sendable, Equatable, Identifiable {
    let id: Int
    let left: Double
    let top: Double
    let right: Double
    let bottom: Double
    let weight: Double
    let algorithm: Int
}

struct AppleAnomalyResult: Sendable {
    let annotationCount: Int
    let boxes: [AppleAnomalyBox]
}

enum AppleAnomalyMode: String, CaseIterable, Identifiable, Sendable {
    case off
    case colorUniqueness
    case targetColors
    case infrared

    var id: String { rawValue }

    var label: String {
        switch self {
        case .off: "Off"
        case .colorUniqueness: "Color Uniqueness"
        case .targetColors: "Target Colors"
        case .infrared: "Infrared"
        }
    }

    var compactLabel: String {
        switch self {
        case .off: "Off"
        case .colorUniqueness: "Color"
        case .targetColors: "Target"
        case .infrared: "Infrared"
        }
    }

    var algorithmMask: Int32 {
        switch self {
        case .off: 0
        case .colorUniqueness, .targetColors: R2C_ANOMALY_ALGORITHM_COLOR
        case .infrared: R2C_ANOMALY_ALGORITHM_THERMAL
        }
    }
}

/// Owns the non-Sendable C runtime and processes at most one frame at a time.
/// When analysis cannot keep up, the live path drops analysis work instead of
/// building latency while video rendering continues.
final class AppleAnomalyProcessor: @unchecked Sendable {
    private let queue = DispatchQueue(label: "org.ncssar.rid2caltopo.anomaly", qos: .userInitiated)
    private let stateLock = NSLock()
    private var busy = false
    private var mode: AppleAnomalyMode
    private var configuration: AppleAnomalyConfiguration
    private var runtime: OpaquePointer?

    init(mode: AppleAnomalyMode, configuration: AppleAnomalyConfiguration) {
        self.mode = mode
        self.configuration = configuration
        runtime = R2CAnomalyCreate(configuration.algorithmMask(for: mode), 30)
        applyConfiguration()
    }

    deinit {
        R2CAnomalyDestroy(runtime)
    }

    func submit(
        pixelBuffer: CVPixelBuffer,
        timestampMicroseconds: Int64,
        completion: @escaping @MainActor @Sendable (AppleAnomalyResult) -> Void
    ) -> Bool {
        stateLock.lock()
        guard !busy else {
            stateLock.unlock()
            return false
        }
        busy = true
        stateLock.unlock()

        let transfer = PixelBufferTransfer(value: pixelBuffer)
        queue.async { [self, transfer] in
            let result = process(pixelBuffer: transfer.value, timestampMicroseconds: timestampMicroseconds)
            stateLock.lock()
            busy = false
            stateLock.unlock()
            guard let result else { return }
            Task { @MainActor in
                completion(result)
            }
        }
        return true
    }

    func configure(mode: AppleAnomalyMode, configuration: AppleAnomalyConfiguration) {
        queue.async { [self] in
            self.mode = mode
            self.configuration = configuration
            R2CAnomalyDestroy(runtime)
            runtime = R2CAnomalyCreate(configuration.algorithmMask(for: mode), 30)
            applyConfiguration()
        }
    }

    func reset() {
        queue.async { [self] in
            R2CAnomalyDestroy(runtime)
            runtime = R2CAnomalyCreate(configuration.algorithmMask(for: mode), 30)
            applyConfiguration()
        }
    }

    private func applyConfiguration() {
        guard let runtime else { return }
        var native = configuration.nativeConfiguration(for: mode)
        R2CAnomalyApplyConfiguration(runtime, &native)
    }

    private func process(
        pixelBuffer: CVPixelBuffer,
        timestampMicroseconds: Int64
    ) -> AppleAnomalyResult? {
        guard let runtime,
              CVPixelBufferGetPixelFormatType(pixelBuffer) == kCVPixelFormatType_32BGRA
        else { return nil }

        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        guard let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else { return nil }

        var result = R2CAnomalyFrameResult()
        let status = R2CAnomalyProcessBGRA(
            runtime,
            baseAddress.assumingMemoryBound(to: UInt8.self),
            Int32(CVPixelBufferGetBytesPerRow(pixelBuffer)),
            Int32(CVPixelBufferGetWidth(pixelBuffer)),
            Int32(CVPixelBufferGetHeight(pixelBuffer)),
            timestampMicroseconds,
            &result
        )
        guard status == 0 else { return nil }
        var boxes: [AppleAnomalyBox] = []
        for index in 0 ..< Int(result.annotation_count) {
            var box = R2CAnomalyBox()
            guard R2CAnomalyFrameResultCopyBox(&result, Int32(index), &box) == 0 else { continue }
            boxes.append(AppleAnomalyBox(
                id: index,
                left: Double(box.left),
                top: Double(box.top),
                right: Double(box.right),
                bottom: Double(box.bottom),
                weight: Double(box.weight),
                algorithm: Int(box.algorithm)
            ))
        }
        return AppleAnomalyResult(annotationCount: Int(result.annotation_count), boxes: boxes)
    }
}

/// Pulls decoded BGRA frames from an AVFoundation HLS player at display cadence.
/// Consumers can render the player while the same decoded CVPixelBuffers feed
/// the portable anomaly detector boundary.
@MainActor
final class AppleVideoFrameSource: ObservableObject {
    enum State: Equatable {
        case idle
        case connecting
        case streaming
        case waitingForPublisher(String)
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var frameCount = 0
    @Published private(set) var dimensions = "--"
    @Published private(set) var videoAspectRatio = 16.0 / 9.0
    @Published private(set) var analyzedFrameCount = 0
    @Published private(set) var droppedAnalysisFrameCount = 0
    @Published private(set) var anomalyCount = 0
    @Published private(set) var anomalyBoxes: [AppleAnomalyBox] = []
    @Published private(set) var anomalyMode: AppleAnomalyMode
    @Published private(set) var anomalyConfiguration: AppleAnomalyConfiguration
    @Published private(set) var player: AVPlayer?
    @Published private(set) var decodedFrameAgeSeconds: Double?
    @Published private(set) var recoveryCount = 0
    @Published private(set) var lastRecoveryReason = "None"
    @Published private(set) var nextRetryDelaySeconds: Double?
    @Published private(set) var mediaPublisherStatus = "Unknown"

    private var videoOutput: AVPlayerItemVideoOutput?
    private var displayLink: CADisplayLink?
    private var itemStatusObservation: NSKeyValueObservation?
    private var notificationObservers: [NSObjectProtocol] = []
    private var retryTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    private var currentURL: URL?
    private var currentPath: String?
    private var playerGeneration = 0
    private var recoveryPolicy = LiveVideoRecoveryPolicy()
    private var latestPixelBuffer: CVPixelBuffer?
    private var latestFrameCapturedAt: Date?
    private let defaults: UserDefaults
    private let anomalyProcessor: AppleAnomalyProcessor

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let stored = defaults.string(forKey: "video.anomalyMode")
        let mode = stored.flatMap(AppleAnomalyMode.init(rawValue:)) ?? .infrared
        let configuration = AppleAnomalyConfiguration.load(from: defaults)
        anomalyMode = mode
        anomalyConfiguration = configuration
        anomalyProcessor = AppleAnomalyProcessor(mode: mode, configuration: configuration)
    }

    func setAnomalyMode(_ mode: AppleAnomalyMode) {
        guard mode != anomalyMode else { return }
        anomalyMode = mode
        defaults.set(mode.rawValue, forKey: "video.anomalyMode")
        analyzedFrameCount = 0
        droppedAnalysisFrameCount = 0
        anomalyCount = 0
        anomalyBoxes = []
        anomalyProcessor.configure(mode: mode, configuration: anomalyConfiguration)
        AppleLog.info("Anomaly", "Detector mode changed to \(mode.label)")
    }

    func applyAnomalyConfiguration(_ configuration: AppleAnomalyConfiguration) {
        let normalized = configuration.normalized
        anomalyConfiguration = normalized
        normalized.save(to: defaults)
        analyzedFrameCount = 0
        droppedAnalysisFrameCount = 0
        anomalyCount = 0
        anomalyBoxes = []
        anomalyProcessor.configure(mode: anomalyMode, configuration: normalized)
        AppleLog.info("Anomaly", "Advanced detector configuration applied")
    }

    func start(url: URL) {
        stop()
        currentURL = url
        currentPath = Self.streamPath(from: url)
        recoveryPolicy.reset(at: Self.now)
        startWatchdog()
        installPlayer(url: url)
        AppleLog.info("Video", "Decoder session requested url=\(url.absoluteString) path=\(currentPath ?? "unknown")")
    }

    private func installPlayer(url: URL) {
        tearDownPlayer()
        playerGeneration &+= 1
        let generation = playerGeneration
        recoveryPolicy.beginRecoveryAttempt(at: Self.now)
        state = .connecting
        nextRetryDelaySeconds = nil

        let attributes: [String: any Sendable] = [
            kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
        ]
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: attributes)
        let item = AVPlayerItem(url: url)
        item.preferredForwardBufferDuration = 0
        item.add(output)

        let player = AVPlayer(playerItem: item)
        player.automaticallyWaitsToMinimizeStalling = false
        self.videoOutput = output
        self.player = player

        itemStatusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            Task { @MainActor [weak self] in
                guard let self, generation == self.playerGeneration else { return }
                switch item.status {
                case .readyToPlay:
                    AppleLog.info("Video", "AVPlayer ready path=\(self.currentPath ?? "unknown")")
                    player.playImmediately(atRate: 1)
                case .failed:
                    self.handleRecoveryDecision(self.recoveryPolicy.playerFailed(
                        detail: item.error?.localizedDescription ?? "unknown failure"
                    ))
                case .unknown:
                    break
                @unknown default:
                    break
                }
            }
        }

        let displayLink = CADisplayLink(target: self, selector: #selector(pullFrame))
        displayLink.preferredFrameRateRange = CAFrameRateRange(minimum: 10, maximum: 30, preferred: 30)
        displayLink.add(to: .main, forMode: .common)
        self.displayLink = displayLink

        let stalled = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.playbackStalledNotification,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, generation == self.playerGeneration else { return }
                AppleLog.warning("Video", "AVPlayer reported playback stalled path=\(self.currentPath ?? "unknown"); awaiting decoded-frame watchdog")
            }
        }
        let failedToEnd = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.failedToPlayToEndTimeNotification,
            object: item,
            queue: .main
        ) { [weak self] notification in
            let failureDetail = (notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error)?
                .localizedDescription ?? "failed before live edge"
            Task { @MainActor [weak self] in
                guard let self, generation == self.playerGeneration else { return }
                self.handleRecoveryDecision(self.recoveryPolicy.playerFailed(
                    detail: failureDetail
                ))
            }
        }
        notificationObservers = [stalled, failedToEnd]
    }

    func stop() {
        retryTask?.cancel()
        retryTask = nil
        watchdogTask?.cancel()
        watchdogTask = nil
        playerGeneration &+= 1
        tearDownPlayer()
        currentURL = nil
        currentPath = nil
        frameCount = 0
        dimensions = "--"
        videoAspectRatio = 16.0 / 9.0
        analyzedFrameCount = 0
        droppedAnalysisFrameCount = 0
        anomalyCount = 0
        anomalyBoxes = []
        decodedFrameAgeSeconds = nil
        recoveryCount = 0
        lastRecoveryReason = "None"
        nextRetryDelaySeconds = nil
        mediaPublisherStatus = "Unknown"
        latestPixelBuffer = nil
        latestFrameCapturedAt = nil
        anomalyProcessor.reset()
        state = .idle
    }

    func handleMediaServerEvent(_ event: MediaServerEvent) {
        guard currentURL != nil else { return }
        let eventPath: String?
        let publisherAvailable: Bool?
        switch event {
        case let .streamStarted(path, _), let .hlsStreamStarted(path):
            eventPath = path
            publisherAvailable = true
        case let .streamStopped(path, _):
            eventPath = path
            publisherAvailable = false
        case let .streamError(path, _, _):
            eventPath = path
            publisherAvailable = false
        default:
            return
        }
        guard eventPath == currentPath, let publisherAvailable else { return }
        mediaPublisherStatus = publisherAvailable ? "Publishing" : "Unavailable"
        AppleLog.info("Video", "MediaMTX publisher path=\(eventPath ?? "unknown") status=\(mediaPublisherStatus)")
        if !publisherAvailable {
            retryTask?.cancel()
            retryTask = nil
            nextRetryDelaySeconds = nil
        }
        handleRecoveryDecision(recoveryPolicy.setPublisherAvailable(publisherAvailable))
    }

    private func startWatchdog() {
        watchdogTask?.cancel()
        watchdogTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled, let self else { return }
                let now = Self.now
                decodedFrameAgeSeconds = recoveryPolicy.decodedFrameAge(at: now)
                handleRecoveryDecision(recoveryPolicy.evaluate(at: now))
            }
        }
    }

    private func handleRecoveryDecision(_ decision: LiveVideoRecoveryPolicy.Decision?) {
        guard let decision else { return }
        switch decision {
        case let .waitForPublisher(trigger):
            retryTask?.cancel()
            retryTask = nil
            nextRetryDelaySeconds = nil
            lastRecoveryReason = trigger.diagnosticDescription
            state = .waitingForPublisher(trigger.diagnosticDescription)
            logPlayerFailureContext(prefix: "Waiting for MediaMTX publisher", trigger: trigger)
        case let .reconnect(delay, trigger):
            recoveryCount += 1
            lastRecoveryReason = trigger.diagnosticDescription
            nextRetryDelaySeconds = delay
            state = .failed("\(trigger.diagnosticDescription); retry in \(Self.secondsLabel(delay))")
            logPlayerFailureContext(prefix: "Scheduling decoder recovery in \(Self.secondsLabel(delay))", trigger: trigger)
            scheduleReconnect(after: delay)
        }
    }

    private func scheduleReconnect(after delay: TimeInterval) {
        retryTask?.cancel()
        let generation = playerGeneration
        retryTask = Task { [weak self] in
            if delay > 0 {
                try? await Task.sleep(for: .seconds(delay))
            }
            guard !Task.isCancelled,
                  let self,
                  generation == self.playerGeneration,
                  let url = self.currentURL
            else { return }
            self.installPlayer(url: url)
        }
    }

    private func tearDownPlayer() {
        displayLink?.invalidate()
        displayLink = nil
        itemStatusObservation = nil
        for observer in notificationObservers {
            NotificationCenter.default.removeObserver(observer)
        }
        notificationObservers.removeAll()
        player?.pause()
        player = nil
        videoOutput = nil
    }

    @objc private func pullFrame(_ displayLink: CADisplayLink) {
        guard let output = videoOutput else { return }
        let hostTime = displayLink.timestamp + displayLink.duration
        let itemTime = output.itemTime(forHostTime: hostTime)
        guard output.hasNewPixelBuffer(forItemTime: itemTime),
              let pixelBuffer = output.copyPixelBuffer(forItemTime: itemTime, itemTimeForDisplay: nil)
        else { return }

        frameCount += 1
        recoveryPolicy.recordDecodedFrame(at: Self.now)
        decodedFrameAgeSeconds = 0
        nextRetryDelaySeconds = nil
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        dimensions = "\(width) x \(height)"
        if height > 0 {
            videoAspectRatio = Double(width) / Double(height)
        }
        latestPixelBuffer = pixelBuffer
        latestFrameCapturedAt = Date()
        submitForAnomalyAnalysis(pixelBuffer: pixelBuffer, itemTime: itemTime)
        state = .streaming
    }

    func captureSnapshot() async throws -> AppleVideoSnapshot {
        guard let latestPixelBuffer, let capturedAt = latestFrameCapturedAt else {
            throw AppleVideoSnapshotError.noDecodedFrame
        }
        let transfer = PixelBufferTransfer(value: latestPixelBuffer)
        let width = CVPixelBufferGetWidth(latestPixelBuffer)
        let height = CVPixelBufferGetHeight(latestPixelBuffer)
        let data = try await Task.detached(priority: .userInitiated) {
            let context = CIContext(options: [.cacheIntermediates: false])
            let image = CIImage(cvPixelBuffer: transfer.value)
            guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
                  let jpeg = context.jpegRepresentation(
                    of: image,
                    colorSpace: colorSpace,
                    options: [kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.85]
                  )
            else { throw AppleVideoSnapshotError.conversionFailed }
            return jpeg
        }.value
        AppleLog.info("Video", "Snapshot captured size=\(width)x\(height) bytes=\(data.count)")
        return AppleVideoSnapshot(jpegData: data, capturedAt: capturedAt, width: width, height: height)
    }

    private func logPlayerFailureContext(prefix: String, trigger: LiveVideoRecoveryPolicy.Trigger) {
        let item = player?.currentItem
        let errorEvent = item?.errorLog()?.events.last
        let accessEvent = item?.accessLog()?.events.last
        let errorDetail = errorEvent.map {
            "errorDomain=\($0.errorDomain) errorCode=\($0.errorStatusCode) comment=\($0.errorComment ?? "none") uri=\($0.uri ?? "none")"
        } ?? "errorLog=none"
        let accessDetail = accessEvent.map {
            "observedBitrate=\(Int($0.observedBitrate)) indicatedBitrate=\(Int($0.indicatedBitrate)) stalls=\($0.numberOfStalls) server=\($0.serverAddress ?? "unknown")"
        } ?? "accessLog=none"
        AppleLog.warning(
            "Video",
            "\(prefix) reason=\(trigger.diagnosticDescription) frames=\(frameCount) frameAge=\(decodedFrameAgeSeconds.map { String(format: "%.1fs", $0) } ?? "none") media=\(mediaPublisherStatus) \(errorDetail) \(accessDetail)"
        )
    }

    private static var now: TimeInterval { ProcessInfo.processInfo.systemUptime }

    private static func secondsLabel(_ seconds: TimeInterval) -> String {
        String(format: "%.0fs", seconds)
    }

    private static func streamPath(from url: URL) -> String? {
        let components = url.pathComponents.filter { $0 != "/" }
        guard let first = components.first else { return nil }
        return first
    }

    private func submitForAnomalyAnalysis(pixelBuffer: CVPixelBuffer, itemTime: CMTime) {
        guard anomalyMode != .off else {
            anomalyCount = 0
            anomalyBoxes = []
            return
        }
        let timestampSeconds = CMTimeGetSeconds(itemTime)
        let accepted = anomalyProcessor.submit(
            pixelBuffer: pixelBuffer,
            timestampMicroseconds: Int64(timestampSeconds * 1_000_000)
        ) { [weak self] result in
            self?.analyzedFrameCount += 1
            self?.anomalyCount = result.annotationCount
            self?.anomalyBoxes = result.boxes
        }
        if !accepted {
            droppedAnalysisFrameCount += 1
        }
    }
}
