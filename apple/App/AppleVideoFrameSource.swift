import AVFoundation
import Combine
import CoreVideo
import Dispatch
import QuartzCore

private struct PixelBufferTransfer: @unchecked Sendable {
    let value: CVPixelBuffer
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
    case infrared

    var id: String { rawValue }

    var label: String {
        switch self {
        case .off: "Off"
        case .colorUniqueness: "Color Uniqueness"
        case .infrared: "Infrared"
        }
    }

    var compactLabel: String {
        switch self {
        case .off: "Off"
        case .colorUniqueness: "Color"
        case .infrared: "Infrared"
        }
    }

    fileprivate var algorithmMask: Int32 {
        switch self {
        case .off: 0
        case .colorUniqueness: R2C_ANOMALY_ALGORITHM_COLOR
        case .infrared: R2C_ANOMALY_ALGORITHM_THERMAL
        }
    }
}

/// Owns the non-Sendable C runtime and processes at most one frame at a time.
/// When analysis cannot keep up, the live path drops analysis work instead of
/// building latency while video rendering continues.
private final class AppleAnomalyProcessor: @unchecked Sendable {
    private let queue = DispatchQueue(label: "org.ncssar.rid2caltopo.anomaly", qos: .userInitiated)
    private let stateLock = NSLock()
    private var busy = false
    private var algorithmMask: Int32
    private var runtime: OpaquePointer?

    init(algorithmMask: Int32) {
        self.algorithmMask = algorithmMask
        runtime = R2CAnomalyCreate(algorithmMask, 30)
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

    func configure(algorithmMask: Int32) {
        queue.async { [self] in
            R2CAnomalyDestroy(runtime)
            self.algorithmMask = algorithmMask
            runtime = R2CAnomalyCreate(algorithmMask, 30)
        }
    }

    func reset() {
        queue.async { [self] in
            R2CAnomalyDestroy(runtime)
            runtime = R2CAnomalyCreate(algorithmMask, 30)
        }
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
    @Published private(set) var player: AVPlayer?

    private var videoOutput: AVPlayerItemVideoOutput?
    private var displayLink: CADisplayLink?
    private var itemStatusObservation: NSKeyValueObservation?
    private var retryTask: Task<Void, Never>?
    private let defaults: UserDefaults
    private let anomalyProcessor: AppleAnomalyProcessor

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let stored = defaults.string(forKey: "video.anomalyMode")
        let mode = stored.flatMap(AppleAnomalyMode.init(rawValue:)) ?? .infrared
        anomalyMode = mode
        anomalyProcessor = AppleAnomalyProcessor(algorithmMask: mode.algorithmMask)
    }

    func setAnomalyMode(_ mode: AppleAnomalyMode) {
        guard mode != anomalyMode else { return }
        anomalyMode = mode
        defaults.set(mode.rawValue, forKey: "video.anomalyMode")
        analyzedFrameCount = 0
        droppedAnalysisFrameCount = 0
        anomalyCount = 0
        anomalyBoxes = []
        anomalyProcessor.configure(algorithmMask: mode.algorithmMask)
        AppleLog.info("Anomaly", "Detector mode changed to \(mode.label)")
    }

    func start(url: URL) {
        stop()
        state = .connecting

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
                guard let self else { return }
                switch item.status {
                case .readyToPlay:
                    player.playImmediately(atRate: 1)
                case .failed:
                    self.scheduleReconnect(
                        url: url,
                        reason: item.error?.localizedDescription ?? "AVPlayer failed"
                    )
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
    }

    func stop() {
        retryTask?.cancel()
        retryTask = nil
        displayLink?.invalidate()
        displayLink = nil
        itemStatusObservation = nil
        player?.pause()
        player = nil
        videoOutput = nil
        frameCount = 0
        dimensions = "--"
        videoAspectRatio = 16.0 / 9.0
        analyzedFrameCount = 0
        droppedAnalysisFrameCount = 0
        anomalyCount = 0
        anomalyBoxes = []
        anomalyProcessor.reset()
        state = .idle
    }

    private func scheduleReconnect(url: URL, reason: String) {
        state = .failed(reason)
        retryTask?.cancel()
        retryTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled, let self else { return }
            self.start(url: url)
        }
    }

    @objc private func pullFrame(_ displayLink: CADisplayLink) {
        guard let output = videoOutput else { return }
        let hostTime = displayLink.timestamp + displayLink.duration
        let itemTime = output.itemTime(forHostTime: hostTime)
        guard output.hasNewPixelBuffer(forItemTime: itemTime),
              let pixelBuffer = output.copyPixelBuffer(forItemTime: itemTime, itemTimeForDisplay: nil)
        else { return }

        frameCount += 1
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        dimensions = "\(width) x \(height)"
        if height > 0 {
            videoAspectRatio = Double(width) / Double(height)
        }
        submitForAnomalyAnalysis(pixelBuffer: pixelBuffer, itemTime: itemTime)
        state = .streaming
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
