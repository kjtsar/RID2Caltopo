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
        try configuration.write(to: configurationURL, options: .atomic)

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

    private let controller = MediaMTXMobileController()
    private var eventTask: Task<Void, Never>?

    func start() {
        guard !isRunning, status != "Starting" else { return }
        status = "Starting"

        eventTask = Task { [controller] in
            for await event in controller.events {
                guard !Task.isCancelled else { return }
                switch event {
                case let .serverStarted(version):
                    status = "Running \(version)"
                case let .streamStarted(path, _):
                    status = "Streaming \(path)"
                case let .streamStopped(path, _):
                    status = "Stopped stream \(path)"
                case let .streamError(path, _, detail):
                    status = "Error \(path ?? "stream"): \(detail)"
                default:
                    break
                }
            }
        }

        Task {
            do {
                guard let url = Bundle.main.url(forResource: "mediamtx", withExtension: "yml") else {
                    status = "Configuration missing"
                    return
                }
                try await controller.start(configuration: Data(contentsOf: url))
                isRunning = true
                if status == "Starting" {
                    status = "Running"
                }
            } catch {
                AppleLog.error("MediaMTX", "Start failed: \(error)")
                eventTask?.cancel()
                eventTask = nil
                status = "Start failed: \(error)"
            }
        }
    }

    func stop() {
        guard isRunning else { return }
        Task {
            await controller.stop()
            eventTask?.cancel()
            eventTask = nil
            isRunning = false
            status = "Stopped"
        }
    }
}
