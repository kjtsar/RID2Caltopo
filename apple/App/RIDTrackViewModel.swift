import Combine
import Foundation
import R2CCore

@MainActor
final class RIDTrackViewModel: ObservableObject {
    private static let deferredVideoLinkRetryNanoseconds: UInt64 = 2_000_000_000
    private static let deferredVideoLinkRetryCount = 15
    @Published private(set) var tracks: [RidAircraftTrack] = []
    @Published private(set) var acceptedObservationCount = 0
    @Published private(set) var filteredObservationCount = 0
    @Published private(set) var duplicatePositionFilterCount = 0
    @Published private(set) var minimumDistanceFilterCount = 0
    @Published private(set) var implausibleSpeedFilterCount = 0
    @Published private(set) var horizontalAccuracyFilterCount = 0
    @Published private(set) var invalidObservationCount = 0
    @Published private(set) var archivedTrackCount = 0
    @Published private(set) var archiveStatus = "No archived tracks"
    @Published private(set) var trackerArchiveStatus = "Tracker archive not configured"
    @Published private(set) var latestArchiveURL: URL?
    @Published private(set) var caltopoStatus = "Publishing disabled"
    @Published private(set) var caltopoRTTMilliseconds: Int64?
    @Published private(set) var altitudeDisplayByAircraftID: [String: OperationalAircraftAltitudeDisplay] = [:]
    @Published private(set) var lastValidRIDUpdateAt: Date?
    @Published private(set) var lastRIDMessageAt: Date?

    private let store = RidTrackStore()
    private let archiveStore = AppleTrackArchiveStore()
    private let caltopoPublisher = AppleCaltopoPublisher()
    private let terrainService = AppleTerrainElevationService()
    private var altitudeCoordinatorByAircraftID: [String: OperationalAltitudeCoordinator] = [:]
    private var terrainTasks: [String: Task<Void, Never>] = [:]
    private var terrainRequestKeyByAircraftID: [String: String] = [:]
    private var terrainResolvedKeyByAircraftID: [String: String] = [:]
    private var centerpointElevationCache: [String: (inputKey: String, sample: OperationalCenterpointElevation.Sample)] = [:]
    private var lastHorizontalAccuracyCodeByAircraftID: [String: UInt8] = [:]
    private var observationTasks: [String: Task<Void, Never>] = [:]
    private var aircraftMessageTasks: [String: Task<Void, Never>] = [:]
    private var ridMessageTimeTasks: [String: Task<Void, Never>] = [:]
    private var agingTask: Task<Void, Never>?
    private var demoTask: Task<Void, Never>?
    private var caltopoEventTask: Task<Void, Never>?
    private var coordinationEventTask: Task<Void, Never>?
    private var peerCoordinator: AppleTrackerCoordinator?
    private var identityProvider: ((String) -> RidAircraftIdentity?)?
    private var publicationSuppressionProvider: ((String) -> Bool)?
    private var peerConfirmationConsumer: ((TrackerCoordinationIdentity) -> Void)?
    private var peerConfirmationClearer: ((String) -> Void)?
    private var pairedVideoActivityProvider: (() -> [String: Date])?
    private var pendingPublication: [String: [RidAircraftTrack]] = [:]
    private var publicationChains: [String: Task<Void, Never>] = [:]
    private var ownershipActivationTasks: [String: Task<Void, Never>] = [:]
    private var archiveConfiguration: AppleTrackArchiveConfiguration?
    private var localDeviceMarker: CaltopoDeviceMarker?
    private var localDeviceMarkerPublishingEnabled = true
    private var clueArchiveProvider:
        ((String, Date, Date) -> [AppleTrackArchiveClue])?

    func bind(to observations: AsyncStream<RidObservation>, sourceID: String) {
        guard observationTasks[sourceID] == nil else { return }
        observationTasks[sourceID] = Task { [weak self] in
            for await observation in observations {
                guard !Task.isCancelled, let self else { return }
                await self.ingest(observation)
            }
        }
        agingTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled, let self else { return }
                let inactive = await self.store.removeInactive(
                    pairedVideoLastActivityAt: self.pairedVideoActivityProvider?() ?? [:]
                )
                for track in inactive {
                    await self.archive(track)
                    self.peerCoordinator?.droneLost(remoteID: track.aircraftID)
                    self.pendingPublication.removeValue(forKey: track.aircraftID)
                    await self.finishPublication(track: track)
                    self.terrainTasks.removeValue(forKey: track.aircraftID)?.cancel()
                    self.terrainRequestKeyByAircraftID.removeValue(forKey: track.aircraftID)
                    self.terrainResolvedKeyByAircraftID.removeValue(forKey: track.aircraftID)
                    self.lastHorizontalAccuracyCodeByAircraftID.removeValue(forKey: track.aircraftID)
                    self.altitudeCoordinatorByAircraftID.removeValue(forKey: track.aircraftID)
                    self.altitudeDisplayByAircraftID.removeValue(forKey: track.aircraftID)
                }
                let snapshot = await self.store.snapshot()
                if snapshot != self.tracks {
                    self.tracks = snapshot
                }
            }
        }
        caltopoEventTask = Task { [weak self, caltopoPublisher] in
            for await event in caltopoPublisher.events {
                guard !Task.isCancelled, let self else { return }
                switch event {
                case .disabled:
                    self.caltopoStatus = "Publishing disabled"
                    self.caltopoRTTMilliseconds = nil
                case .ready:
                    self.caltopoStatus = "Ready"
                    self.caltopoRTTMilliseconds = nil
                case let .trackStarted(id): self.caltopoStatus = "Started \(id)"
                case let .pointPublished(id, rttMilliseconds):
                    self.caltopoStatus = "Published \(id)"
                    self.caltopoRTTMilliseconds = rttMilliseconds
                case let .trackStopped(id): self.caltopoStatus = "Stopped \(id)"
                case let .failed(message):
                    self.caltopoStatus = "Error: \(message)"
                    self.caltopoRTTMilliseconds = nil
                }
            }
        }
    }

    func bindAircraftMessages(
        to messages: AsyncStream<RidAircraftMessage>,
        sourceID: String
    ) {
        guard aircraftMessageTasks[sourceID] == nil else { return }
        aircraftMessageTasks[sourceID] = Task { [weak self] in
            for await message in messages {
                guard !Task.isCancelled, let self else { return }
                if await self.store.noteAircraftMessage(message) {
                    self.tracks = await self.store.snapshot()
                }
            }
        }
    }

    func bindRIDMessageTimes(to messageTimes: AsyncStream<Date>, sourceID: String) {
        guard ridMessageTimeTasks[sourceID] == nil else { return }
        ridMessageTimeTasks[sourceID] = Task { [weak self] in
            for await receivedAt in messageTimes {
                guard !Task.isCancelled, let self else { return }
                self.noteRIDMessage(receivedAt: receivedAt)
            }
        }
    }

    func ingest(_ observation: RidObservation) async {
        switch await store.ingest(observation) {
        case let .accepted(track):
            acceptedObservationCount += 1
            lastValidRIDUpdateAt = max(lastValidRIDUpdateAt ?? observation.receivedAt, observation.receivedAt)
            if let code = observation.horizontalAccuracyCode {
                lastHorizontalAccuracyCodeByAircraftID[track.aircraftID] = code
            }
            logAcceptedObservation(track)
            updateAltitude(for: track)
            if publicationSuppressionProvider?(track.aircraftID) == true {
                pendingPublication.removeValue(forKey: track.aircraftID)
            } else {
                peerCoordinator?.observe(track: track, identity: identityProvider?(track.aircraftID))
                if peerCoordinator?.publicationAllowed(remoteID: track.aircraftID) != false {
                    flushPendingPublication(remoteID: track.aircraftID)
                    publish(track)
                } else {
                    var queued = pendingPublication[track.aircraftID, default: []]
                    queued.append(track)
                    if queued.count > 500 { queued.removeFirst(queued.count - 500) }
                    pendingPublication[track.aircraftID] = queued
                }
            }
        case let .signalOnly(track, reason):
            filteredObservationCount += 1
            switch reason {
            case .duplicatePosition:
                duplicatePositionFilterCount += 1
            case .belowMinimumDistance:
                minimumDistanceFilterCount += 1
            case let .implausibleSpeed(metersPerSecond):
                implausibleSpeedFilterCount += 1
                AppleLog.warning(
                    "RemoteID",
                    String(
                        format: "rid_filter remoteId=%@ reason=implausible_speed speedMph=%.1f transport=%@ rssi=%@",
                        track.aircraftID,
                        metersPerSecond * 2.236936,
                        observation.source.rawValue,
                        observation.signalStrengthDbm.map(String.init) ?? "unknown"
                    )
                )
            }
            logFilterSummaryIfNeeded()
        case let .rejectedHorizontalAccuracy(code, track):
            filteredObservationCount += 1
            horizontalAccuracyFilterCount += 1
            let remoteID = track?.aircraftID ?? RidTrackStore.canonicalAircraftID(observation.aircraftId)
            if lastHorizontalAccuracyCodeByAircraftID[remoteID] != code {
                AppleLog.warning(
                    "RemoteID",
                    "rid_filter remoteId=\(remoteID) reason=horizontal_accuracy code=\(code) requiredCode=10 transport=\(observation.source.rawValue)"
                )
            }
            lastHorizontalAccuracyCodeByAircraftID[remoteID] = code
            logFilterSummaryIfNeeded()
        case .rejectedInvalidObservation:
            filteredObservationCount += 1
            invalidObservationCount += 1
            AppleLog.warning(
                "RemoteID",
                "rid_reject remoteId=\(observation.aircraftId) reason=invalid_observation transport=\(observation.source.rawValue)"
            )
            logFilterSummaryIfNeeded()
        }
        tracks = await store.snapshot()
    }

    private func logFilterSummaryIfNeeded() {
        guard filteredObservationCount.isMultiple(of: 25) else { return }
        AppleLog.info(
            "RemoteID",
            "rid_filter_summary total=\(filteredObservationCount) duplicate=\(duplicatePositionFilterCount) " +
                "underMinimumDistance=\(minimumDistanceFilterCount) implausibleSpeed=\(implausibleSpeedFilterCount) " +
                "horizontalAccuracy=\(horizontalAccuracyFilterCount) invalid=\(invalidObservationCount)"
        )
    }

    func configureTrackPolicy(minimumDistanceFeet: Int, activeTimeoutSeconds: Int) {
        Task { [store] in
            var policy = await store.policy
            policy.minimumDistanceMeters = Double(max(2, minimumDistanceFeet)) * 0.3048
            policy.activeTimeout = TimeInterval(max(1, activeTimeoutSeconds))
            await store.updatePolicy(policy)
        }
    }

    func configurePairedVideoActivity(_ provider: @escaping () -> [String: Date]) {
        pairedVideoActivityProvider = provider
    }

    private func noteRIDMessage(receivedAt: Date) {
        lastRIDMessageAt = max(lastRIDMessageAt ?? receivedAt, receivedAt)
        AppleApplicationCleanupCenter.shared.noteRIDMessage(receivedAt: receivedAt)
    }

    func configureClueArchiveProvider(
        _ provider: @escaping (String, Date, Date) -> [AppleTrackArchiveClue]
    ) {
        clueArchiveProvider = provider
    }

    func configurePublicationSuppression(_ provider: @escaping (String) -> Bool) {
        publicationSuppressionProvider = provider
    }

    func suppressCaltopoPublication(remoteID: String) {
        guard !remoteID.isEmpty else { return }
        pendingPublication.removeValue(forKey: remoteID)
        ownershipActivationTasks.removeValue(forKey: remoteID)?.cancel()
        let publication = publicationChains.removeValue(forKey: remoteID)
        publication?.cancel()
        Task { [caltopoPublisher] in
            _ = await publication?.value
            await caltopoPublisher.discard(remoteID: remoteID)
        }
    }

    func manualCalibrateAltitude(remoteID: String) {
        guard var coordinator = altitudeCoordinatorByAircraftID[remoteID], coordinator.canManualCalibrate else { return }
        coordinator.manualCalibrateAtFiftyFeet()
        altitudeCoordinatorByAircraftID[remoteID] = coordinator
        altitudeDisplayByAircraftID[remoteID] = coordinator.display
        scheduleTerrain(remoteID: remoteID, coordinator: coordinator)
        AppleLog.info("Terrain", "Manual ATO/AGL calibration remoteId=\(remoteID) targetFt=50")
    }

    func projectClueWithTerrain(
        observation: RidObservation,
        headingDegrees: Double?,
        aglMeters: Double?,
        gimbalAngleDegrees: Double
    ) async -> OperationalClueProjection {
        let terrainService = self.terrainService
        return await OperationalClueGeometry.projectWithTerrain(
            droneLatitude: observation.latitude,
            droneLongitude: observation.longitude,
            droneAltitudeMeters: observation.altitudeMeters,
            headingDegrees: headingDegrees,
            aglMeters: aglMeters,
            gimbalAngleDegrees: gimbalAngleDegrees,
            sampleElevationMeters: { latitude, longitude in
                await terrainService.sample(latitude: latitude, longitude: longitude)?.elevationMeters
            }
        )
    }

    func centerpointElevationFeet(
        streamID: String,
        observation: RidObservation,
        headingDegrees: Double,
        aglMeters: Double,
        gimbalAngleDegrees: Double
    ) async -> OperationalCenterpointElevation.Sample? {
        guard headingDegrees.isFinite, aglMeters.isFinite, aglMeters >= 0,
              gimbalAngleDegrees.isFinite, gimbalAngleDegrees < -0.1,
              let altitudeMeters = observation.altitudeMeters, altitudeMeters.isFinite
        else { return nil }
        let latitudeKey = Int64((observation.latitude * 100_000).rounded())
        let longitudeKey = Int64((observation.longitude * 100_000).rounded())
        let altitudeKey = Int64((altitudeMeters * 2).rounded())
        let aglKey = Int64((aglMeters * 2).rounded())
        let headingKey = Int64((headingDegrees * 5).rounded())
        let gimbalKey = Int64((gimbalAngleDegrees * 5).rounded())
        let inputKey = "\(latitudeKey)|\(longitudeKey)|\(altitudeKey)|\(aglKey)|\(headingKey)|\(gimbalKey)"
        if let cached = centerpointElevationCache[streamID], cached.inputKey == inputKey {
            return cached.sample
        }
        let projection = await projectClueWithTerrain(
            observation: observation,
            headingDegrees: headingDegrees,
            aglMeters: aglMeters,
            gimbalAngleDegrees: gimbalAngleDegrees
        )
        guard let terrain = await terrainService.sample(
            latitude: projection.latitude,
            longitude: projection.longitude
        ), terrain.elevationMeters.isFinite else { return nil }
        let elevationFeet = Int((terrain.elevationMeters * 3.28084).rounded())
        let resolution = terrain.horizontalResolutionMeters.flatMap { value in
            value.isFinite && value > 0 ? max(1, Int(value.rounded())) : nil
        }
        let sample = OperationalCenterpointElevation.Sample(
            elevationFeet: elevationFeet,
            demResolutionMeters: resolution
        )
        centerpointElevationCache[streamID] = (inputKey, sample)
        return sample
    }

    func terrainDerivedAglMeters(
        latitude: Double,
        longitude: Double,
        mslAltitudeMeters: Double
    ) async -> Double? {
        guard let ground = await terrainService.sample(
            latitude: latitude,
            longitude: longitude
        )?.elevationMeters else { return nil }
        return OperationalClueGeometry.djiVideoAglMeters(
            mslAltitudeMeters: mslAltitudeMeters,
            groundElevationMeters: ground
        )
    }

    private func updateAltitude(for track: RidAircraftTrack) {
        var coordinator = altitudeCoordinatorByAircraftID[track.aircraftID] ?? OperationalAltitudeCoordinator()
        coordinator.ingest(track.lastObservation)
        if let requestKey = terrainRequestKey(for: coordinator),
           terrainResolvedKeyByAircraftID[track.aircraftID] != requestKey {
            coordinator.markCurrentTerrainPending()
        }
        altitudeCoordinatorByAircraftID[track.aircraftID] = coordinator
        altitudeDisplayByAircraftID[track.aircraftID] = coordinator.display
        scheduleTerrain(remoteID: track.aircraftID, coordinator: coordinator)
    }

    private func scheduleTerrain(remoteID: String, coordinator: OperationalAltitudeCoordinator) {
        guard let current = coordinator.currentCoordinate, let takeoff = coordinator.takeoffCoordinate else { return }
        let requestKey = terrainRequestKey(for: coordinator)!
        if terrainResolvedKeyByAircraftID[remoteID] == requestKey { return }
        if terrainTasks[remoteID] != nil, terrainRequestKeyByAircraftID[remoteID] == requestKey { return }
        terrainTasks.removeValue(forKey: remoteID)?.cancel()
        terrainRequestKeyByAircraftID[remoteID] = requestKey
        terrainTasks[remoteID] = Task { [weak self, terrainService] in
            async let takeoffSample = terrainService.sample(latitude: takeoff.latitude, longitude: takeoff.longitude)
            async let currentSample = terrainService.sample(latitude: current.latitude, longitude: current.longitude)
            let samples = await (takeoffSample, currentSample)
            guard !Task.isCancelled, let self,
                  var latest = self.altitudeCoordinatorByAircraftID[remoteID]
            else { return }
            latest.applyTakeoffTerrain(samples.0)
            latest.applyCurrentTerrain(samples.1, coordinate: current)
            self.altitudeCoordinatorByAircraftID[remoteID] = latest
            self.altitudeDisplayByAircraftID[remoteID] = latest.display
            if samples.0 != nil, samples.1 != nil {
                self.terrainResolvedKeyByAircraftID[remoteID] = requestKey
            }
            self.terrainTasks.removeValue(forKey: remoteID)
            self.terrainRequestKeyByAircraftID.removeValue(forKey: remoteID)
        }
    }

    private func terrainRequestKey(for coordinator: OperationalAltitudeCoordinator) -> String? {
        guard let current = coordinator.currentCoordinate, let takeoff = coordinator.takeoffCoordinate else { return nil }
        return "\(OperationalAltitudeCoordinator.terrainKey(takeoff))|\(OperationalAltitudeCoordinator.terrainKey(current))"
    }

    private func logAcceptedObservation(_ track: RidAircraftTrack) {
        let observation = track.lastObservation
        let receivedMilliseconds = Int64((observation.receivedAt.timeIntervalSince1970 * 1_000).rounded())
        let altitude = observation.altitudeMeters.map { String(format: "%.1f", $0) } ?? "unknown"
        let rssi = observation.signalStrengthDbm.map(String.init) ?? "unknown"
        AppleLog.info(
            "RemoteID",
            String(
                format: "rid_rx remoteId=%@ waypoint=%d wall=%lld lat=%.6f lng=%.6f altM=%@ transport=%@ rssi=%@ distanceFt=%.1f totalDistanceFt=%.1f",
                track.aircraftID,
                track.points.count,
                receivedMilliseconds,
                observation.latitude,
                observation.longitude,
                altitude,
                observation.source.rawValue,
                rssi,
                track.points.count > 1 ? Self.distanceFeet(from: track.points[track.points.count - 2], to: track.points[track.points.count - 1]) : 0,
                track.distanceMeters * 3.28084
            )
        )
    }

    private static func distanceFeet(from: RidTrackPoint, to: RidTrackPoint) -> Double {
        (RidGeometry.relativePosition(
            fromLatitude: from.latitude,
            longitude: from.longitude,
            toLatitude: to.latitude,
            longitude: to.longitude
        )?.distanceMeters ?? 0) * 3.28084
    }

    func configureCaltopo(
        _ configuration: AppleCaltopoConfiguration,
        trackFolderName: String = "Drone Tracks"
    ) {
        Task { [caltopoPublisher] in
            await caltopoPublisher.configure(
                configuration,
                trackFolderName: trackFolderName
            )
            if let marker = await MainActor.run(body: { self.localDeviceMarker }) {
                await caltopoPublisher.publishDeviceMarker(marker, force: true)
            }
        }
    }

    func publishLocalDeviceMarker(_ marker: CaltopoDeviceMarker, force: Bool = false) {
        localDeviceMarker = marker
        guard localDeviceMarkerPublishingEnabled else { return }
        Task { [caltopoPublisher] in
            await caltopoPublisher.publishDeviceMarker(marker, force: force)
        }
    }

    func setLocalDeviceMarkerPublishingEnabled(_ enabled: Bool) async {
        localDeviceMarkerPublishingEnabled = enabled
        if enabled {
            if let localDeviceMarker {
                await caltopoPublisher.publishDeviceMarker(localDeviceMarker, force: true)
            }
        } else {
            await caltopoPublisher.removeDeviceMarker()
        }
    }

    func configureTrackArchive(
        trackerURLPrefix: String,
        trackerAPIKey: String,
        organization: String,
        incident: String,
        operationalPeriod: String,
        mapID: String,
        identities: [RidAircraftIdentity]
    ) {
        let configuration = AppleTrackArchiveConfiguration(
            tracker: TrackerArchiveUploadConfiguration(
                urlPrefix: trackerURLPrefix,
                apiKey: trackerAPIKey,
                organization: organization
            ),
            incident: incident,
            operationalPeriod: operationalPeriod,
            mapID: mapID,
            identities: Dictionary(uniqueKeysWithValues: identities.map { ($0.remoteID, $0) })
        )
        archiveConfiguration = configuration
        trackerArchiveStatus = configuration.tracker.isConfigured
            ? "Checking saved tracks…"
            : "Tracker archive not configured"
        Task { [weak self, archiveStore] in
            await archiveStore.configure(configuration)
            let replay = await archiveStore.replayUnreported()
            guard let self else { return }
            if replay.uploaded > 0 {
                self.trackerArchiveStatus = "Uploaded \(replay.uploaded) saved track\(replay.uploaded == 1 ? "" : "s")"
            } else if replay.pending > 0 {
                self.trackerArchiveStatus = "\(replay.pending) track upload pending"
            } else if configuration.tracker.isConfigured {
                self.trackerArchiveStatus = "Tracker archive ready"
            }
        }
    }

    func configurePeerCoordination(
        _ coordinator: AppleTrackerCoordinator,
        identityProvider: @escaping (String) -> RidAircraftIdentity?,
        peerConfirmationConsumer: @escaping (TrackerCoordinationIdentity) -> Void,
        peerConfirmationClearer: @escaping (String) -> Void
    ) {
        peerCoordinator = coordinator
        self.identityProvider = identityProvider
        self.peerConfirmationConsumer = peerConfirmationConsumer
        self.peerConfirmationClearer = peerConfirmationClearer
        guard coordinationEventTask == nil else { return }
        coordinationEventTask = Task { [weak self, coordinator] in
            for await event in coordinator.events {
                guard !Task.isCancelled, let self else { return }
                switch event {
                case let .ownershipChanged(remoteID, _, localOwner, alertEligible):
                    self.ownershipActivationTasks.removeValue(forKey: remoteID)?.cancel()
                    if localOwner, alertEligible,
                       self.publicationSuppressionProvider?(remoteID) != true {
                        self.ownershipActivationTasks[remoteID] = Task { [weak self, coordinator] in
                            try? await Task.sleep(for: .seconds(2))
                            guard !Task.isCancelled, let self,
                                  coordinator.publicationAllowed(remoteID: remoteID)
                            else { return }
                            self.flushPendingPublication(remoteID: remoteID)
                            self.ownershipActivationTasks.removeValue(forKey: remoteID)
                        }
                    }
                case let .relaySighting(relay):
                    self.publishRelay(relay)
                case let .droneConfirmed(identity, _, _):
                    self.peerConfirmationConsumer?(identity)
                case let .ownerExpired(remoteID):
                    self.peerConfirmationClearer?(remoteID)
                default:
                    break
                }
            }
        }
    }

    func startSimulatorDemo(
        proximityAlert: Bool = false,
        predictiveAlert: Bool = false,
        altitudeAlert: Bool = false
    ) {
        guard demoTask == nil else { return }
        let routes = predictiveAlert ? [
            DemoAircraft(
                id: "DEMOALPHA01",
                latitude: 39.7392,
                longitude: -104.9903,
                latitudeStep: 0,
                longitudeStep: 0,
                altitude: 1_620,
                heading: 90
            ),
            DemoAircraft(
                id: "DEMOBRAVO02",
                latitude: 39.7392,
                longitude: -104.9900,
                latitudeStep: 0,
                longitudeStep: -0.0001,
                altitude: 1_620,
                heading: 270
            ),
        ] : proximityAlert ? [
            DemoAircraft(
                id: "DEMOALPHA01",
                latitude: 39.7392,
                longitude: -104.9903,
                latitudeStep: 0,
                longitudeStep: 0,
                altitude: 1_620,
                heading: 90
            ),
            DemoAircraft(
                id: "DEMOBRAVO02",
                latitude: 39.7392,
                longitude: -104.9900,
                latitudeStep: 0,
                longitudeStep: -0.00005,
                altitude: 1_620,
                heading: 270
            ),
        ] : [
            DemoAircraft(
                id: "DEMOALPHA01",
                latitude: 39.7392,
                longitude: -104.9903,
                latitudeStep: 0.00006,
                longitudeStep: 0.00009,
                altitude: 1_620,
                heading: 48
            ),
            DemoAircraft(
                id: "DEMOBRAVO02",
                latitude: 39.7368,
                longitude: -104.9868,
                latitudeStep: 0.00008,
                longitudeStep: -0.00004,
                altitude: 1_680,
                heading: 338
            ),
        ]
        demoTask = Task { [weak self] in
            var step = 0
            while !Task.isCancelled, let self {
                let routeStep = predictiveAlert ? min(step, 1) : proximityAlert ? min(step, 4) : step
                for route in routes {
                    let altitude = altitudeAlert && route.id == "DEMOALPHA01" ? 1_665.0 : route.altitude
                    let observation = RidObservation(
                        source: .bluetoothLegacy,
                        aircraftId: route.id,
                        receivedAt: Date(),
                        latitude: route.latitude + Double(routeStep) * route.latitudeStep,
                        longitude: route.longitude + Double(routeStep) * route.longitudeStep,
                        altitudeMeters: altitude,
                        heightMeters: max(0, altitude - 1_600),
                        heightReference: .takeoff,
                        headingDegrees: route.heading,
                        speedMetersPerSecond: 11,
                        signalStrengthDbm: -48 - step % 8
                    )
                    await self.ingest(observation)
                }
                step += 1
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    func archiveActiveTracks() {
        Task { [weak self] in
            guard let self else { return }
            for track in await store.snapshot() {
                await archive(track)
            }
        }
    }

    func shutdown() async {
        demoTask?.cancel()
        demoTask = nil
        agingTask?.cancel()
        agingTask = nil
        observationTasks.values.forEach { $0.cancel() }
        observationTasks.removeAll()
        aircraftMessageTasks.values.forEach { $0.cancel() }
        aircraftMessageTasks.removeAll()
        ridMessageTimeTasks.values.forEach { $0.cancel() }
        ridMessageTimeTasks.removeAll()
        caltopoEventTask?.cancel()
        caltopoEventTask = nil
        coordinationEventTask?.cancel()
        coordinationEventTask = nil
        terrainTasks.values.forEach { $0.cancel() }
        terrainTasks.removeAll()
        ownershipActivationTasks.values.forEach { $0.cancel() }
        ownershipActivationTasks.removeAll()

        for track in await store.snapshot() {
            await archive(track)
            await finishPublication(track: track)
        }
    }

    func resubmitRecentTracks(days: Int) async -> String {
        trackerArchiveStatus = "Resubmitting recent tracks…"
        let summary = await archiveStore.resubmitRecent(days: days)
        trackerArchiveStatus = summary.description
        return summary.description
    }

    func localArchiveDirectories() async -> [AppleArchiveDirectoryOption] {
        await archiveStore.archiveDirectories()
    }

    func deleteLocalArchiveDirectories(_ names: Set<String>) async -> String {
        let failed = await archiveStore.deleteArchiveDirectories(names)
        let deleted = max(0, names.count - failed.count)
        let message = failed.isEmpty
            ? "Deleted \(deleted) archive folder\(deleted == 1 ? "" : "s")."
            : "Deleted \(deleted); failed: \(failed.joined(separator: ", "))."
        archiveStatus = message
        return message
    }

    private func archive(_ track: RidAircraftTrack) async {
        let identity = identityProvider?(track.aircraftID)
        let archiveConfiguration = archiveConfiguration
        let metadata = RidTrackArchiveMetadata(
            mappedID: identity?.mappedID ?? track.aircraftID,
            owner: identity?.pilotCallsign ?? "",
            model: identity?.droneDescription ?? "",
            organization: identity?.organization ?? "",
            incident: archiveConfiguration?.incident ?? "",
            operationalPeriod: archiveConfiguration?.operationalPeriod ?? "",
            mapID: archiveConfiguration?.mapID ?? "",
            deviceName: AppleDeviceIdentity.displayName,
            buildVersion: AppleBuildMetadata.version,
            buildTime: AppleBuildMetadata.buildTime
        )
        do {
            let start = track.points.first?.receivedAt ?? track.lastSignalAt
            let clues = clueArchiveProvider?(track.aircraftID, start, Date()) ?? []
            let outcome = try await archiveStore.archive(
                track: track,
                metadata: metadata,
                clues: clues
            )
            archivedTrackCount += 1
            latestArchiveURL = outcome.url
            switch outcome.trackerResult {
            case .notConfigured:
                archiveStatus = "Saved locally • tracker not configured"
                trackerArchiveStatus = "Tracker archive not configured"
            case let .uploaded(code):
                archiveStatus = "Saved locally • uploaded to tracker"
                trackerArchiveStatus = "Last upload succeeded (\(code))"
            case let .rejected(code):
                archiveStatus = "Saved locally • tracker rejected upload (\(code))"
                trackerArchiveStatus = "Tracker rejected last upload (\(code))"
            case let .pending(code):
                archiveStatus = "Saved locally • tracker upload pending"
                trackerArchiveStatus = "Retry pending (\(code))"
            case let .skipped(reason):
                archiveStatus = "Saved locally • tracker skipped"
                trackerArchiveStatus = "Last track not eligible: \(Self.archiveEligibilityText(reason))"
            }
        } catch {
            archiveStatus = "Archive failed: \(error.localizedDescription)"
        }
    }

    private static func archiveEligibilityText(_ reason: TrackerArchiveEligibility) -> String {
        switch reason {
        case .eligible: "eligible"
        case .malformedArchive: "invalid archive"
        case .localArchiveOnly: "local only"
        case .missingOrganization: "organization missing"
        case .organizationMismatch: "organization mismatch"
        case .unknownTeamAircraft: "aircraft not in team config"
        }
    }

    private func publish(_ track: RidAircraftTrack) {
        let label = identityProvider?(track.aircraftID)?.mappedID ?? track.aircraftID
        enqueuePublication(
            remoteID: track.aircraftID,
            label: label,
            observation: track.lastObservation
        )
    }

    private func enqueuePublication(remoteID: String, label: String, observation: RidObservation) {
        let previous = publicationChains[remoteID]
        let publicationObservation = RidObservation(
            source: observation.source,
            aircraftId: observation.aircraftId,
            receivedAt: observation.receivedAt,
            // RID remains the canonical aircraft track. SEI position is used only for clue geometry.
            latitude: observation.latitude,
            longitude: observation.longitude,
            altitudeMeters: observation.altitudeMeters,
            heightMeters: observation.heightMeters,
            heightReference: observation.heightReference,
            horizontalAccuracyCode: observation.horizontalAccuracyCode,
            headingDegrees: observation.headingDegrees,
            speedMetersPerSecond: observation.speedMetersPerSecond,
            operatorLatitude: observation.operatorLatitude,
            operatorLongitude: observation.operatorLongitude,
            signalStrengthDbm: observation.signalStrengthDbm,
            droneScoutRelay: observation.droneScoutRelay
        )
        let cameraMetadata = peerCoordinator?.caltopoCameraMetadata(
            droneDesignator: label
        )
        let task = Task { [caltopoPublisher] in
            _ = await previous?.value
            guard !Task.isCancelled else { return }
            await caltopoPublisher.publish(
                remoteID: remoteID,
                label: label,
                observation: publicationObservation,
                cameraMetadata: cameraMetadata
            )
        }
        publicationChains[remoteID] = task
    }

    private func flushPendingPublication(remoteID: String) {
        guard publicationSuppressionProvider?(remoteID) != true else {
            pendingPublication.removeValue(forKey: remoteID)
            return
        }
        let queued = pendingPublication.removeValue(forKey: remoteID) ?? []
        for track in queued { publish(track) }
    }

    private func publishRelay(_ relay: TrackerRelaySighting) {
        guard publicationSuppressionProvider?(relay.remoteID) != true else { return }
        guard peerCoordinator?.publicationAllowed(remoteID: relay.remoteID) == true else { return }
        let observation = RidObservation(
            source: .trackerRelay,
            aircraftId: relay.remoteID,
            receivedAt: relay.droneTimestampMilliseconds > 0
                ? Date(timeIntervalSince1970: Double(relay.droneTimestampMilliseconds) / 1_000)
                : Date(),
            latitude: relay.latitude,
            longitude: relay.longitude,
            altitudeMeters: relay.altitudeMeters,
            headingDegrees: relay.headingDegrees,
            speedMetersPerSecond: relay.groundSpeedKnots.map { $0 / 1.943_844 }
        )
        let label = identityProvider?(relay.remoteID)?.mappedID ?? relay.remoteID
        enqueuePublication(remoteID: relay.remoteID, label: label, observation: observation)
    }

    private func finishPublication(track: RidAircraftTrack) async {
        let remoteID = track.aircraftID
        ownershipActivationTasks.removeValue(forKey: remoteID)?.cancel()
        let publication = publicationChains.removeValue(forKey: remoteID)
        _ = await publication?.value
        let identity = identityProvider?(remoteID)
        let trackStartedAt = track.points.first?.receivedAt ?? track.lastObservation.receivedAt
        let trackEndedAt = track.points.last?.receivedAt ?? track.lastObservation.receivedAt
        let videoURL = peerCoordinator?.capturedVideoURL(matching: [
            identity?.mappedID ?? "",
            remoteID,
        ], trackStartedAt: trackStartedAt, trackEndedAt: trackEndedAt)
        let description = CaltopoArchiveDescription.build(capturedVideoURL: videoURL)
        let archivedTrack = await caltopoPublisher.finish(
            remoteID: remoteID,
            description: description
        )
        guard description.isEmpty, let archivedTrack else { return }
        Task { [weak self] in
            await self?.attachDeferredVideoLink(
                to: archivedTrack,
                designators: [identity?.mappedID ?? "", remoteID],
                trackStartedAt: trackStartedAt,
                trackEndedAt: trackEndedAt
            )
        }
    }

    private func attachDeferredVideoLink(
        to archivedTrack: AppleArchivedCaltopoTrack,
        designators: [String],
        trackStartedAt: Date,
        trackEndedAt: Date
    ) async {
        for _ in 0..<Self.deferredVideoLinkRetryCount {
            guard !Task.isCancelled else { return }
            try? await Task.sleep(nanoseconds: Self.deferredVideoLinkRetryNanoseconds)
            guard let videoURL = peerCoordinator?.capturedVideoURL(
                matching: designators,
                trackStartedAt: trackStartedAt,
                trackEndedAt: trackEndedAt
            ) else { continue }
            let description = CaltopoArchiveDescription.build(capturedVideoURL: videoURL)
            guard !description.isEmpty else { return }
            if await caltopoPublisher.updateArchivedDescription(
                archivedTrack,
                description: description
            ) {
                return
            }
        }
        AppleLog.warning(
            "CalTopo",
            "Recording did not become linkable before the deferred CalTopo link deadline."
        )
    }
}

private struct DemoAircraft: Sendable {
    let id: String
    let latitude: Double
    let longitude: Double
    let latitudeStep: Double
    let longitudeStep: Double
    let altitude: Double
    let heading: Double
}
