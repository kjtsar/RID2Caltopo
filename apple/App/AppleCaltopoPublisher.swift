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
    private var labels: [String: String] = [:]
    private var observations: [String: [RidObservation]] = [:]
    private var startTasks: [String: Task<String, Error>] = [:]
    private var finishingRemoteIDs: Set<String> = []
    private var trackFolderName = "Drone Tracks"
    private var trackFolderID: String?
    private var archiveFolderID: String?
    private var folderResolver = CaltopoTrackFolderResolver()
    private var configurationGeneration = 0
    private var configuredConfiguration: AppleCaltopoConfiguration?
    private var lastDeviceMarkerPublishedAt: Date?
    private var publishedDeviceMarkerID: String?
    private var lastInterruptedJournalWriteAt: [String: Date] = [:]
    private var pendingInterruptedRecoveries: [CaltopoInterruptedPublication] = []
    private let interruptedJournal: CaltopoInterruptedPublicationJournal

    init(interruptedJournal: CaltopoInterruptedPublicationJournal? = nil) {
        let pair = AsyncStream<AppleCaltopoPublisherEvent>.makeStream(bufferingPolicy: .bufferingNewest(128))
        events = pair.stream
        continuation = pair.continuation
        if let interruptedJournal {
            self.interruptedJournal = interruptedJournal
        } else {
            let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            self.interruptedJournal = CaltopoInterruptedPublicationJournal(
                fileURL: root.appendingPathComponent("caltopo-interrupted-publications.json")
            )
        }
    }

    deinit {
        continuation.finish()
    }

    func configure(_ configuration: AppleCaltopoConfiguration, trackFolderName: String) async {
        let normalizedFolderName = Self.normalizedTrackFolderName(trackFolderName)
        guard configuredConfiguration != configuration
                || self.trackFolderName != normalizedFolderName
        else {
            // ContentView can observe the same map selection through both its
            // selection callback and its configuration fingerprint. Android
            // keeps one open-map session in this case; preserve the same
            // resolved folder IDs instead of starting a second create race.
            AppleLog.info(
                "CalTopo",
                "Ignoring duplicate publisher configuration for map id=\(configuration.mapID)"
            )
            return
        }

        await removeDeviceMarker()
        configurationGeneration += 1
        await folderResolver.reset()
        folderResolver = CaltopoTrackFolderResolver()
        liveTrackIDs.removeAll()
        labels.removeAll()
        observations.removeAll()
        finishingRemoteIDs.removeAll()
        startTasks.values.forEach { $0.cancel() }
        startTasks.removeAll()
        pendingInterruptedRecoveries.removeAll()
        configuredConfiguration = configuration
        self.trackFolderName = normalizedFolderName
        trackFolderID = nil
        archiveFolderID = nil
        lastDeviceMarkerPublishedAt = nil
        guard let liveConfiguration = configuration.liveConfiguration else {
            client = nil
            continuation.yield(.disabled)
            return
        }
        do {
            let configuredClient = try CaltopoLiveClient(configuration: liveConfiguration)
            client = configuredClient
            pendingInterruptedRecoveries = await interruptedJournal.entries(mapID: configuration.mapID)
            do {
                try await ensureFolders(client: configuredClient)
                await recoverInterruptedPublications(client: configuredClient)
            } catch {
                AppleLog.warning(
                    "CalTopo",
                    "Interrupted LiveTrack recovery will retry after map reconnect: \(error.localizedDescription)"
                )
            }
            continuation.yield(.ready)
        } catch {
            client = nil
            continuation.yield(.failed("Configuration: \(error.localizedDescription)"))
        }
    }

    func publishDeviceMarker(_ marker: CaltopoDeviceMarker, force: Bool = false) async {
        guard let client else { return }
        let generation = configurationGeneration
        let now = Date()
        if !force, let lastDeviceMarkerPublishedAt,
           now.timeIntervalSince(lastDeviceMarkerPublishedAt) < 30 {
            return
        }
        do {
            try await ensureFolders(client: client)
            await recoverInterruptedPublications(client: client)
            try await client.publishDeviceMarker(marker, folderID: trackFolderID, now: now)
            guard generation == configurationGeneration else {
                try? await client.deleteMarker(markerID: marker.id)
                return
            }
            lastDeviceMarkerPublishedAt = now
            publishedDeviceMarkerID = marker.id
            AppleLog.info(
                "CalTopo",
                "Published local device marker id=\(marker.id) folder='\(trackFolderName)'"
            )
        } catch {
            continuation.yield(.failed("Device marker: \(error.localizedDescription)"))
        }
    }

    func removeDeviceMarker() async {
        guard let client, let markerID = publishedDeviceMarkerID else {
            publishedDeviceMarkerID = nil
            lastDeviceMarkerPublishedAt = nil
            return
        }
        do {
            try await client.deleteMarker(markerID: markerID)
            AppleLog.info("CalTopo", "Removed local device marker id=\(markerID)")
        } catch {
            AppleLog.warning(
                "CalTopo",
                "Could not remove local device marker id=\(markerID): \(error.localizedDescription)"
            )
        }
        publishedDeviceMarkerID = nil
        lastDeviceMarkerPublishedAt = nil
    }

    func publish(track: RidAircraftTrack) async {
        await publish(
            remoteID: track.aircraftID,
            label: track.aircraftID,
            observation: track.lastObservation
        )
    }

    func publish(
        remoteID: String,
        label: String,
        observation: RidObservation,
        cameraMetadata: CaltopoCameraMetadata? = nil
    ) async {
        guard let client else { return }
        do {
            try await ensureFolders(client: client)
            await recoverInterruptedPublications(client: client)
            var saved = observations[remoteID, default: []]
            if saved.last != observation {
                saved.append(observation)
                if saved.count > 5_000 { saved.removeFirst(saved.count - 5_000) }
                observations[remoteID] = saved
            }
            let trackLabel = labels[remoteID] ?? CaltopoTrackLabel.androidCompatible(
                baseLabel: label,
                firstWaypointAt: saved.first?.receivedAt ?? observation.receivedAt
            )
            labels[remoteID] = trackLabel
            if liveTrackIDs[remoteID] == nil {
                let task: Task<String, Error>
                if let existing = startTasks[remoteID] {
                    task = existing
                } else {
                    let folderID = trackFolderID
                    task = Task {
                        try await client.startLiveTrack(
                            remoteID: remoteID,
                            label: trackLabel,
                            folderID: folderID
                        )
                    }
                    startTasks[remoteID] = task
                }
                let liveTrackID = try await task.value
                startTasks.removeValue(forKey: remoteID)
                guard !finishingRemoteIDs.contains(remoteID) else { return }
                liveTrackIDs[remoteID] = liveTrackID
                await persistInterruptedPublication(remoteID: remoteID, force: true)
                continuation.yield(.trackStarted(remoteID))
            }
            await persistInterruptedPublication(remoteID: remoteID)
            let requestStarted = Date()
            try await client.publishPoint(
                remoteID: remoteID,
                observation: observation,
                cameraMetadata: cameraMetadata
            )
            let rttMilliseconds = max(0, Int64(Date().timeIntervalSince(requestStarted) * 1_000))
            continuation.yield(.pointPublished(remoteID, rttMilliseconds: rttMilliseconds))
        } catch {
            startTasks.removeValue(forKey: remoteID)
            continuation.yield(.failed("\(remoteID): \(error.localizedDescription)"))
        }
    }

    func finish(remoteID: String, description: String = "") async {
        finishingRemoteIDs.insert(remoteID)
        defer { finishingRemoteIDs.remove(remoteID) }
        guard let client else {
            startTasks.removeValue(forKey: remoteID)?.cancel()
            liveTrackIDs.removeValue(forKey: remoteID)
            labels.removeValue(forKey: remoteID)
            observations.removeValue(forKey: remoteID)
            return
        }
        do {
            let liveTrackID: String?
            if let existing = liveTrackIDs[remoteID] {
                liveTrackID = existing
            } else if let task = startTasks.removeValue(forKey: remoteID) {
                liveTrackID = try await task.value
                liveTrackIDs[remoteID] = liveTrackID
            } else {
                liveTrackID = nil
            }
            guard let liveTrackID else { return }
            await persistInterruptedPublication(remoteID: remoteID, description: description, force: true)
            try await ensureFolders(client: client)
            guard let archiveFolderID else {
                throw CaltopoLiveClientError.missingResult
            }
            try await client.archiveLiveTrack(
                liveTrackID: liveTrackID,
                label: labels[remoteID] ?? remoteID,
                observations: observations[remoteID] ?? [],
                folderID: archiveFolderID,
                description: description
            )
            liveTrackIDs.removeValue(forKey: remoteID)
            labels.removeValue(forKey: remoteID)
            observations.removeValue(forKey: remoteID)
            try await interruptedJournal.remove(liveTrackID: liveTrackID)
            continuation.yield(.trackStopped(remoteID))
        } catch is CancellationError {
            return
        } catch {
            continuation.yield(.failed("Stop \(remoteID): \(error.localizedDescription)"))
        }
    }

    /// Stops an ignored aircraft's live track without converting it to an archived Shape.
    func discard(remoteID: String) async {
        finishingRemoteIDs.insert(remoteID)
        defer {
            finishingRemoteIDs.remove(remoteID)
            startTasks.removeValue(forKey: remoteID)
            liveTrackIDs.removeValue(forKey: remoteID)
            labels.removeValue(forKey: remoteID)
            observations.removeValue(forKey: remoteID)
        }

        let liveTrackID: String?
        if let existing = liveTrackIDs[remoteID] {
            liveTrackID = existing
        } else if let task = startTasks[remoteID] {
            liveTrackID = try? await task.value
        } else {
            liveTrackID = nil
        }
        guard let client, let liveTrackID else { return }
        do {
            try await client.stopLiveTrack(liveTrackID: liveTrackID)
            try await interruptedJournal.remove(liveTrackID: liveTrackID)
            continuation.yield(.trackStopped(remoteID))
            AppleLog.info("CalTopo", "Discarded ignored aircraft remoteId=\(remoteID) liveTrackId=\(liveTrackID)")
        } catch {
            continuation.yield(.failed("Discard \(remoteID): \(error.localizedDescription)"))
        }
    }

    private func ensureFolders(client: CaltopoLiveClient) async throws {
        guard trackFolderID == nil || archiveFolderID == nil else { return }
        let generation = configurationGeneration
        let resolver = folderResolver
        let folderName = trackFolderName
        let resolved = try await resolver.resolve(
            trackFolderName: folderName,
            settleDelay: .milliseconds(500),
            fetchSnapshot: {
                try await client.fetchMapArtifacts()
            },
            createFolder: { title, visible, labelVisible in
                try await client.createFolder(
                    title: title,
                    visible: visible,
                    labelVisible: labelVisible
                )
            },
            deleteFolder: { folderID in
                try await client.deleteFolder(folderID: folderID)
                AppleLog.info(
                    "CalTopo",
                    "Removed empty duplicate track folder id=\(folderID)"
                )
            }
        )
        guard generation == configurationGeneration else { throw CancellationError() }
        trackFolderID = resolved.active
        archiveFolderID = resolved.archive
    }

    private func persistInterruptedPublication(
        remoteID: String,
        description: String = "",
        force: Bool = false
    ) async {
        guard let configuration = configuredConfiguration,
              let liveTrackID = liveTrackIDs[remoteID]
        else { return }
        let now = Date()
        if !force,
           let lastWrite = lastInterruptedJournalWriteAt[remoteID],
           now.timeIntervalSince(lastWrite) < 5 {
            return
        }
        let entry = CaltopoInterruptedPublication(
            mapID: configuration.mapID,
            remoteID: remoteID,
            liveTrackID: liveTrackID,
            label: labels[remoteID] ?? remoteID,
            description: description,
            observations: observations[remoteID] ?? []
        )
        do {
            try await interruptedJournal.upsert(entry)
            lastInterruptedJournalWriteAt[remoteID] = now
        } catch {
            AppleLog.warning("CalTopo", "Could not persist interrupted LiveTrack \(liveTrackID): \(error.localizedDescription)")
        }
    }

    private func recoverInterruptedPublications(client: CaltopoLiveClient) async {
        guard let archiveFolderID, !pendingInterruptedRecoveries.isEmpty else { return }
        let generation = configurationGeneration
        var deferred: [CaltopoInterruptedPublication] = []
        for entry in pendingInterruptedRecoveries {
            do {
                try await client.archiveLiveTrack(
                    liveTrackID: entry.liveTrackID,
                    label: entry.label,
                    observations: entry.observations,
                    folderID: archiveFolderID,
                    description: entry.description
                )
                try await interruptedJournal.remove(liveTrackID: entry.liveTrackID)
                AppleLog.info(
                    "CalTopo",
                    "Recovered interrupted LiveTrack remoteId=\(entry.remoteID) liveTrackId=\(entry.liveTrackID) points=\(entry.points.count)"
                )
            } catch {
                deferred.append(entry)
                AppleLog.warning(
                    "CalTopo",
                    "Interrupted LiveTrack recovery deferred liveTrackId=\(entry.liveTrackID): \(error.localizedDescription)"
                )
            }
        }
        if generation == configurationGeneration {
            pendingInterruptedRecoveries = deferred
        }
    }

    private static func normalizedTrackFolderName(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Drone Tracks" : trimmed
    }

}
