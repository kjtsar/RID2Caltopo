import Foundation
import Combine
import MediaMTXMobile
import R2CCore

enum MediaMTXMobileControllerError: Error {
    case alreadyRunning
    case startFailed(status: Int32)
}

actor MediaMTXMobileController: MediaServerController {
    nonisolated let events: AsyncStream<MediaServerEvent>

    private nonisolated let continuation: AsyncStream<MediaServerEvent>.Continuation
    private var parser = MediaMTXLogEventParser()
    private var retainedSelf: Unmanaged<MediaMTXMobileController>?
    private var configurationURL: URL?
    private var running = false

    init() {
        let pair = AsyncStream<MediaServerEvent>.makeStream(bufferingPolicy: .bufferingNewest(256))
        events = pair.stream
        continuation = pair.continuation
    }

    deinit {
        continuation.finish()
    }

    func start(configuration: Data) async throws {
        guard !running else { throw MediaMTXMobileControllerError.alreadyRunning }

        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("RID2Caltopo-MediaMTX", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let configurationURL = directory.appendingPathComponent("mediamtx.yml")
        #if DEBUG
        let effectiveConfiguration: Data
        if var text = String(data: configuration, encoding: .utf8) {
            text = text.replacingOccurrences(of: "logLevel: info", with: "logLevel: debug")
            effectiveConfiguration = Data(text.utf8)
        } else {
            effectiveConfiguration = configuration
        }
        #else
        let effectiveConfiguration = configuration
        #endif
        try effectiveConfiguration.write(to: configurationURL, options: .atomic)

        let retainedSelf = Unmanaged.passRetained(self)
        let context = UInt(bitPattern: retainedSelf.toOpaque())
        R2CMediaMTXSetLogCallback(mediaMTXSwiftLogCallback, context)

        let status = configurationURL.path.withCString { path in
            R2CMediaMTXStart(UnsafeMutablePointer(mutating: path))
        }
        guard status == 0 else {
            R2CMediaMTXSetLogCallback(nil, 0)
            retainedSelf.release()
            throw MediaMTXMobileControllerError.startFailed(status: status)
        }

        self.configurationURL = configurationURL
        self.retainedSelf = retainedSelf
        running = true
    }

    func stop() async {
        guard running else { return }
        R2CMediaMTXStop()
        R2CMediaMTXSetLogCallback(nil, 0)
        retainedSelf?.release()
        retainedSelf = nil
        configurationURL = nil
        running = false
    }

    #if DEBUG
    func simulateSilentListenerExit() {
        guard running else { return }
        R2CMediaMTXStop()
        AppleLog.info("MediaMTX", "Simulated silent native listener exit")
    }
    #endif

    fileprivate func receive(logLine: String) {
        AppleLog.debug("MediaMTX", logLine)
        if let event = parser.parse(line: logLine) {
            continuation.yield(event)
        }
    }
}

private func mediaMTXSwiftLogCallback(
    context: UInt,
    line: UnsafePointer<CChar>?
) {
    guard context != 0, let line, let pointer = UnsafeRawPointer(bitPattern: context) else { return }
    let controller = Unmanaged<MediaMTXMobileController>
        .fromOpaque(pointer)
        .takeUnretainedValue()
    let logLine = String(cString: line)
    Task {
        await controller.receive(logLine: logLine)
    }
}

@MainActor
final class MediaMTXViewModel: ObservableObject {
    @Published private(set) var isRunning = false
    @Published private(set) var status = "Stopped"
    @Published private(set) var activePublisherPaths: Set<String> = []
    var eventHandler: ((MediaServerEvent) -> Void)?

    private let controller = MediaMTXMobileController()
    private var eventTask: Task<Void, Never>?
    private var healthCheckTask: Task<Void, Never>?

    func start(captureStreams: Bool? = nil) {
        guard !isRunning, status != "Starting" else { return }
        status = "Starting"
        let captureStreams = captureStreams
            ?? UserDefaults.standard.bool(forKey: "video.captureStreams")

        if eventTask == nil {
            eventTask = Task { [controller] in
                for await event in controller.events {
                    guard !Task.isCancelled else { return }
                    let localizedEvent = Self.localizeCompletedRecordingIfNeeded(event)
                    let deliveredEvent = await Self.normalizeCompletedRecordingIfNeeded(localizedEvent)
                    eventHandler?(deliveredEvent)
                    switch deliveredEvent {
                    case let .serverStarted(version):
                        status = "Running \(version)"
                    case let .streamStarted(path, _):
                        activePublisherPaths.insert(path)
                        status = "Streaming \(path)"
                    case let .streamStopped(path, _):
                        activePublisherPaths.remove(path)
                        status = "Stopped stream \(path)"
                    case let .streamError(path, _, detail):
                        if let path { activePublisherPaths.remove(path) }
                        status = "Error \(path ?? "stream"): \(detail)"
                    case let .recordFileCompleted(path, filePath, _):
                        status = "Recorded \(path)"
                        AppleLog.info("MediaMTX", "Recording file complete path=\(filePath)")
                    default:
                        break
                    }
                }
            }
        }

        Task {
            do {
                guard let url = Bundle.main.url(forResource: "mediamtx", withExtension: "yml") else {
                    status = "Configuration missing"
                    return
                }
                let baseConfiguration = try Data(contentsOf: url)
                let recordingRoot = try Self.capturedStreamsDirectory()
                let configuration = try MediaMTXRuntimeConfiguration.build(
                    base: baseConfiguration,
                    captureStreams: captureStreams,
                    recordingRoot: recordingRoot
                )
                try await controller.start(configuration: configuration)
                isRunning = true
                if status == "Starting" {
                    status = captureStreams ? "Running • capturing streams" : "Running"
                }
            } catch {
                AppleLog.error("MediaMTX", "Start failed: \(error)")
                eventTask?.cancel()
                eventTask = nil
                status = "Start failed: \(error)"
            }
        }
    }

    private static func localizeCompletedRecordingIfNeeded(
        _ event: MediaServerEvent
    ) -> MediaServerEvent {
        guard case let .recordFileCompleted(path, filePath, durationMs) = event else {
            return event
        }
        let sourceURL = URL(fileURLWithPath: filePath)
        guard let localizedURL = ManagedVideoRecordingIdentity.localizedMediaMTXRecordingURL(
            for: sourceURL
        ), localizedURL != sourceURL else {
            return event
        }

        do {
            let availableURL = ManagedVideoRecordingIdentity.availableRecordingURL(
                preferred: localizedURL,
                fileExists: FileManager.default.fileExists(atPath:)
            )
            try FileManager.default.moveItem(at: sourceURL, to: availableURL)
            AppleLog.info(
                "MediaMTX",
                "Localized recording filename from=\(sourceURL.lastPathComponent) to=\(availableURL.lastPathComponent)"
            )
            return .recordFileCompleted(
                path: path,
                filePath: availableURL.path,
                durationMilliseconds: durationMs
            )
        } catch {
            AppleLog.error(
                "MediaMTX",
                "Unable to localize recording filename path=\(filePath): \(error)"
            )
            return event
        }
    }

    private static func normalizeCompletedRecordingIfNeeded(
        _ event: MediaServerEvent
    ) async -> MediaServerEvent {
        guard case let .recordFileCompleted(path, filePath, durationMs) = event else {
            return event
        }
        let result = await Task.detached(priority: .utility) { () -> (Bool, String) in
            let sourceURL = URL(fileURLWithPath: filePath)
            let temporaryURL = sourceURL.deletingLastPathComponent().appendingPathComponent(
                ".\(sourceURL.deletingPathExtension().lastPathComponent)-ios-compatible-\(UUID().uuidString).mp4"
            )
            var detail = [CChar](repeating: 0, count: 256)
            let status = sourceURL.path.withCString { sourcePath in
                temporaryURL.path.withCString { destinationPath in
                    R2CFFmpegNormalizeRecording(
                        sourcePath,
                        destinationPath,
                        &detail,
                        Int32(detail.count)
                    )
                }
            }
            let message = String(
                decoding: detail.prefix { $0 != 0 }.map(UInt8.init(bitPattern:)),
                as: UTF8.self
            )
            guard status == 0 else {
                try? FileManager.default.removeItem(at: temporaryURL)
                return (false, message.isEmpty ? "error=\(status)" : message)
            }
            do {
                _ = try FileManager.default.replaceItemAt(
                    sourceURL,
                    withItemAt: temporaryURL,
                    backupItemName: nil,
                    options: []
                )
                return (true, message)
            } catch {
                try? FileManager.default.removeItem(at: temporaryURL)
                return (false, "replace failed: \(error.localizedDescription)")
            }
        }.value
        if result.0 {
            AppleLog.info(
                "MediaMTX",
                "Normalized recording for iOS playback file=\(URL(fileURLWithPath: filePath).lastPathComponent) \(result.1)"
            )
        } else {
            AppleLog.error(
                "MediaMTX",
                "Recording normalization failed file=\(URL(fileURLWithPath: filePath).lastPathComponent) detail=\(result.1)"
            )
        }
        return .recordFileCompleted(
            path: path,
            filePath: filePath,
            durationMilliseconds: durationMs
        )
    }

    func stop() {
        guard isRunning else { return }
        Task {
            await controller.stop()
            for path in activePublisherPaths {
                eventHandler?(.streamStopped(path: path, publisherConnectionID: nil))
            }
            activePublisherPaths.removeAll()
            isRunning = false
            status = "Stopped"
        }
    }

    func shutdown() async {
        healthCheckTask?.cancel()
        healthCheckTask = nil
        eventTask?.cancel()
        eventTask = nil
        await controller.stop()
        for path in activePublisherPaths {
            eventHandler?(.streamStopped(path: path, publisherConnectionID: nil))
        }
        activePublisherPaths.removeAll()
        isRunning = false
        status = "Stopped"
    }

    func restart(captureStreams: Bool) {
        guard isRunning else {
            start(captureStreams: captureStreams)
            return
        }
        status = "Restarting"
        Task {
            await controller.stop()
            for path in activePublisherPaths {
                eventHandler?(.streamStopped(path: path, publisherConnectionID: nil))
            }
            activePublisherPaths.removeAll()
            isRunning = false
            status = "Stopped"
            start(captureStreams: captureStreams)
        }
    }

    func ensureHealthy(captureStreams: Bool) {
        guard healthCheckTask == nil else { return }
        guard isRunning else {
            if status != "Starting", status != "Restarting" {
                AppleLog.error("MediaMTX", "RTMP listener unavailable while server is stopped; starting")
                start(captureStreams: captureStreams)
            }
            return
        }
        healthCheckTask = Task { [weak self] in
            let listenerAvailable = await Self.localListenerAvailable()
            guard let self else { return }
            self.healthCheckTask = nil
            guard !listenerAvailable else { return }
            AppleLog.error(
                "MediaMTX",
                "RTMP listener health check failed while state was '\(self.status)'; restarting"
            )
            self.restart(captureStreams: captureStreams)
        }
    }

    #if DEBUG
    func simulateSilentListenerExit() {
        Task { await controller.simulateSilentListenerExit() }
    }
    #endif

    private static func localListenerAvailable() async -> Bool {
        guard let url = URL(string: "http://127.0.0.1:8888/") else { return false }
        var request = URLRequest(url: url)
        request.timeoutInterval = 2
        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            return response is HTTPURLResponse
        } catch {
            return false
        }
    }

    private static func capturedStreamsDirectory() throws -> URL {
        let documents = try FileManager.default.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = documents.appendingPathComponent(
            "RID2Caltopo/CapturedStreams",
            isDirectory: true
        )
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        return directory
    }
}
