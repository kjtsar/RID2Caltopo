import Foundation
import R2CCore

enum AppleCaltopoPublisherEvent: Sendable, Equatable {
    case disabled
    case ready
    case trackStarted(String)
    case pointPublished(String, rttMilliseconds: Int64)
    case trackStopped(String)
    case failed(String)
}

actor AppleCaltopoPublisher {
    nonisolated let events: AsyncStream<AppleCaltopoPublisherEvent>
    private nonisolated let continuation: AsyncStream<AppleCaltopoPublisherEvent>.Continuation
    private var client: CaltopoLiveClient?
    private var liveTrackIDs: [String: String] = [:]
    private var startTasks: [String: Task<String, Error>] = [:]
    private var finishingRemoteIDs: Set<String> = []

    init() {
        let pair = AsyncStream<AppleCaltopoPublisherEvent>.makeStream(bufferingPolicy: .bufferingNewest(128))
        events = pair.stream
        continuation = pair.continuation
    }

    deinit {
        continuation.finish()
    }

    func configure(_ configuration: AppleCaltopoConfiguration) {
        liveTrackIDs.removeAll()
        finishingRemoteIDs.removeAll()
        startTasks.values.forEach { $0.cancel() }
        startTasks.removeAll()
        guard let liveConfiguration = configuration.liveConfiguration else {
            client = nil
            continuation.yield(.disabled)
            return
        }
        do {
            client = try CaltopoLiveClient(configuration: liveConfiguration)
            continuation.yield(.ready)
        } catch {
            client = nil
            continuation.yield(.failed("Configuration: \(error.localizedDescription)"))
        }
    }

    func publish(track: RidAircraftTrack) async {
        await publish(
            remoteID: track.aircraftID,
            label: track.aircraftID,
            observation: track.lastObservation
        )
    }

    func publish(remoteID: String, label: String, observation: RidObservation) async {
        guard let client else { return }
        do {
            if liveTrackIDs[remoteID] == nil {
                let task: Task<String, Error>
                if let existing = startTasks[remoteID] {
                    task = existing
                } else {
                    task = Task {
                        try await client.startLiveTrack(remoteID: remoteID, label: label)
                    }
                    startTasks[remoteID] = task
                }
                let liveTrackID = try await task.value
                startTasks.removeValue(forKey: remoteID)
                guard !finishingRemoteIDs.contains(remoteID) else { return }
                liveTrackIDs[remoteID] = liveTrackID
                continuation.yield(.trackStarted(remoteID))
            }
            let requestStarted = Date()
            try await client.publishPoint(remoteID: remoteID, observation: observation)
            let rttMilliseconds = max(0, Int64(Date().timeIntervalSince(requestStarted) * 1_000))
            continuation.yield(.pointPublished(remoteID, rttMilliseconds: rttMilliseconds))
        } catch {
            startTasks.removeValue(forKey: remoteID)
            continuation.yield(.failed("\(remoteID): \(error.localizedDescription)"))
        }
    }

    func finish(remoteID: String) async {
        finishingRemoteIDs.insert(remoteID)
        defer { finishingRemoteIDs.remove(remoteID) }
        guard let client else {
            startTasks.removeValue(forKey: remoteID)?.cancel()
            liveTrackIDs.removeValue(forKey: remoteID)
            return
        }
        do {
            let liveTrackID: String?
            if let existing = liveTrackIDs.removeValue(forKey: remoteID) {
                liveTrackID = existing
            } else if let task = startTasks.removeValue(forKey: remoteID) {
                liveTrackID = try await task.value
            } else {
                liveTrackID = nil
            }
            guard let liveTrackID else { return }
            try await client.stopLiveTrack(liveTrackID: liveTrackID)
            continuation.yield(.trackStopped(remoteID))
        } catch is CancellationError {
            return
        } catch {
            continuation.yield(.failed("Stop \(remoteID): \(error.localizedDescription)"))
        }
    }
}
