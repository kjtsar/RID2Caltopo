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

struct AppleArchivedCaltopoTrack: Sendable {
    let liveTrackID: String
    let label: String
    let observations: [RidObservation]
    let folderID: String
}

actor AppleCaltopoPublisher {
    private static let staleDeviceMarkerAge: TimeInterval = 180
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
    private var configurationTransitionInFlight = false
    private var configurationIdleWaiters: [CheckedContinuation<Void, Never>] = []
    private var lastDeviceMarkerPublishedAt: Date?
    private var publishedDeviceMarkerID: String?
    private var deviceMarkerGeneration = 0
    private var deviceMarkerPublishingSuspended = false
    private var deviceMarkerPublishInFlight = false
    private var pendingDeviceMarkerPublication: (marker: CaltopoDeviceMarker, force: Bool)?
    private var deviceMarkerIdleWaiters: [CheckedContinuation<Void, Never>] = []
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
        await waitForConfigurationTransitionToFinish()
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
        configurationTransitionInFlight = true
        await performConfiguration(configuration, normalizedFolderName: normalizedFolderName)
        configurationTransitionInFlight = false
        let waiters = configurationIdleWaiters
        configurationIdleWaiters.removeAll()
        waiters.forEach { $0.resume() }
    }

    private func waitForConfigurationTransitionToFinish() async {
        while configurationTransitionInFlight {
            await withCheckedContinuation { continuation in
                configurationIdleWaiters.append(continuation)
            }
        }
    }

    private func performConfiguration(
        _ configuration: AppleCaltopoConfiguration,
        normalizedFolderName: String
    ) async {
        deviceMarkerGeneration += 1
        deviceMarkerPublishingSuspended = true
        pendingDeviceMarkerPublication = nil
        await waitForDeviceMarkerPublicationToFinish()
        await removePublishedDeviceMarker()
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
            deviceMarkerPublishingSuspended = false
            continuation.yield(.disabled)
            return
        }
        do {
            let configuredClient = try CaltopoLiveClient(configuration: liveConfiguration)
            client = configuredClient
            pendingInterruptedRecoveries = await interruptedJournal.entries(mapID: configuration.mapID)
            do {
                try await ensureFolders(client: configuredClient)
                await removeStaleR2CDeviceMarkers(client: configuredClient)
                await recoverInterruptedPublications(client: configuredClient)
            } catch {
                AppleLog.warning(
                    "CalTopo",
                    "Interrupted LiveTrack recovery will retry after map reconnect: \(error.localizedDescription)"
                )
            }
            deviceMarkerPublishingSuspended = false
            continuation.yield(.ready)
        } catch {
            client = nil
            deviceMarkerPublishingSuspended = false
            continuation.yield(.failed("Configuration: \(error.localizedDescription)"))
        }
    }

    func publishDeviceMarker(_ marker: CaltopoDeviceMarker, force: Bool = false) async {
        guard !deviceMarkerPublishingSuspended, client != nil else { return }
        if deviceMarkerPublishInFlight {
            let pendingForce = pendingDeviceMarkerPublication?.force ?? false
            pendingDeviceMarkerPublication = (marker, force || pendingForce)
            return
        }
        deviceMarkerPublishInFlight = true
        var publication: (marker: CaltopoDeviceMarker, force: Bool)? = (marker, force)
        while let current = publication {
            pendingDeviceMarkerPublication = nil
            await performDeviceMarkerPublication(current.marker, force: current.force)
            publication = pendingDeviceMarkerPublication
        }
        deviceMarkerPublishInFlight = false
        let waiters = deviceMarkerIdleWaiters
        deviceMarkerIdleWaiters.removeAll()
        waiters.forEach { $0.resume() }
    }

    private func performDeviceMarkerPublication(_ marker: CaltopoDeviceMarker, force: Bool) async {
        guard !deviceMarkerPublishingSuspended, let client else { return }
        let generation = deviceMarkerGeneration
        let now = Date()
        if !force, let lastDeviceMarkerPublishedAt,
           now.timeIntervalSince(lastDeviceMarkerPublishedAt) < 30 {
            return
        }
        do {
            try await ensureFolders(client: client)
            await recoverInterruptedPublications(client: client)
            try await client.publishDeviceMarker(marker, folderID: trackFolderID, now: now)
            guard generation == deviceMarkerGeneration, !deviceMarkerPublishingSuspended else {
                await deleteAllDeviceMarkerOccurrences(client: client, markerID: marker.id)
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
        deviceMarkerGeneration += 1
        deviceMarkerPublishingSuspended = true
        pendingDeviceMarkerPublication = nil
        await waitForDeviceMarkerPublicationToFinish()
        await removePublishedDeviceMarker()
        deviceMarkerPublishingSuspended = false
    }

    private func waitForDeviceMarkerPublicationToFinish() async {
        guard deviceMarkerPublishInFlight else { return }
        await withCheckedContinuation { continuation in
            deviceMarkerIdleWaiters.append(continuation)
        }
    }

    private func removePublishedDeviceMarker() async {
        guard let client, let markerID = publishedDeviceMarkerID else {
            publishedDeviceMarkerID = nil
            lastDeviceMarkerPublishedAt = nil
            return
        }
        await deleteAllDeviceMarkerOccurrences(client: client, markerID: markerID)
        publishedDeviceMarkerID = nil
        lastDeviceMarkerPublishedAt = nil
    }

    private func deleteAllDeviceMarkerOccurrences(client: CaltopoLiveClient, markerID: String) async {
        let normalizedID = markerID.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !normalizedID.isEmpty else { return }
        let maximumAttempts = 8
        for attempt in 1...maximumAttempts {
            do {
                try await client.deleteMarker(markerID: normalizedID)
            } catch {
                AppleLog.warning(
                    "CalTopo",
                    "Could not remove local device marker id=\(normalizedID): \(error.localizedDescription)"
                )
                return
            }
            do {
                let snapshot = try await client.fetchMapArtifacts()
                let remaining = snapshot.occurrenceCount(ofItemID: normalizedID)
                if remaining == 0 {
                    AppleLog.info(
                        "CalTopo",
                        "Removed local device marker id=\(normalizedID) attempts=\(attempt)"
                    )
                    return
                }
                AppleLog.warning(
                    "CalTopo",
                    "Local device marker id=\(normalizedID) still has \(remaining) occurrence(s) after delete attempt \(attempt)"
                )
            } catch {
                AppleLog.info(
                    "CalTopo",
                    "Removed local device marker id=\(normalizedID); verification unavailable: \(error.localizedDescription)"
                )
                return
            }
        }
        AppleLog.warning(
            "CalTopo",
            "Local device marker id=\(normalizedID) remained after \(maximumAttempts) delete attempts"
        )
    }

    private func removeStaleR2CDeviceMarkers(client: CaltopoLiveClient, now: Date = Date()) async {
        do {
            let snapshot = try await client.fetchMapArtifacts(now: now)
            let staleIDs = snapshot.staleR2CDeviceMarkerIDs(
                now: now,
                staleAfter: Self.staleDeviceMarkerAge
            )
            for markerID in staleIDs {
                do {
                    try await client.deleteMarker(markerID: markerID, now: now)
                    AppleLog.info("CalTopo", "Removed stale R2C device marker id=\(markerID)")
                } catch {
                    AppleLog.warning(
                        "CalTopo",
                        "Could not remove stale R2C device marker id=\(markerID): \(error.localizedDescription)"
                    )
                }
            }
        } catch {
            AppleLog.warning(
                "CalTopo",
                "Could not inspect map for stale R2C device markers: \(error.localizedDescription)"
            )
        }
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

    func finish(remoteID: String, description: String = "") async -> AppleArchivedCaltopoTrack? {
        finishingRemoteIDs.insert(remoteID)
        defer { finishingRemoteIDs.remove(remoteID) }
        guard let client else {
            startTasks.removeValue(forKey: remoteID)?.cancel()
            liveTrackIDs.removeValue(forKey: remoteID)
            labels.removeValue(forKey: remoteID)
            observations.removeValue(forKey: remoteID)
            return nil
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
            guard let liveTrackID else { return nil }
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
            let archivedTrack = AppleArchivedCaltopoTrack(
                liveTrackID: liveTrackID,
                label: labels[remoteID] ?? remoteID,
                observations: observations[remoteID] ?? [],
                folderID: archiveFolderID
            )
            liveTrackIDs.removeValue(forKey: remoteID)
            labels.removeValue(forKey: remoteID)
            observations.removeValue(forKey: remoteID)
            try await interruptedJournal.remove(liveTrackID: liveTrackID)
            continuation.yield(.trackStopped(remoteID))
            return archivedTrack
        } catch is CancellationError {
            return nil
        } catch {
            continuation.yield(.failed("Stop \(remoteID): \(error.localizedDescription)"))
            return nil
        }
    }

    func updateArchivedDescription(
        _ archivedTrack: AppleArchivedCaltopoTrack,
        description: String
    ) async -> Bool {
        guard let client else { return false }
        do {
            try await client.updateArchivedTrack(
                liveTrackID: archivedTrack.liveTrackID,
                label: archivedTrack.label,
                observations: archivedTrack.observations,
                folderID: archivedTrack.folderID,
                description: description
            )
            AppleLog.info(
                "CalTopo",
                "Added deferred recording link to archived track \(archivedTrack.liveTrackID)"
            )
            return true
        } catch {
            AppleLog.warning(
                "CalTopo",
                "Deferred recording link update failed for \(archivedTrack.liveTrackID): \(error.localizedDescription)"
            )
            return false
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
