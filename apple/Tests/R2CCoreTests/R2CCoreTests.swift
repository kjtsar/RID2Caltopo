import Foundation
import Testing
@testable import R2CCore

@Test func observationPreservesTransportAndIdentity() {
    let observation = RidObservation(
        source: .bluetoothLegacy,
        aircraftId: "1SAR-TEST-01",
        receivedAt: Date(timeIntervalSince1970: 1_700_000_000),
        latitude: 39.7392,
        longitude: -104.9903,
        signalStrengthDbm: -63
    )

    #expect(observation.source == .bluetoothLegacy)
    #expect(observation.aircraftId == "1SAR-TEST-01")
    #expect(observation.signalStrengthDbm == -63)
}

@Test func proximityAlertRequiresTeamTrafficAndLocalEligibility() {
    var engine = RidProximityAlertEngine()
    let ineligible = proximityDrone(
        id: "A",
        longitude: 0,
        team: true,
        eligible: false
    )
    let nearbyUnknown = proximityDrone(
        id: "B",
        longitude: 0.00005,
        team: false,
        eligible: false
    )
    #expect(engine.update(drones: [ineligible, nearbyUnknown], thresholdFeet: 40).activeAlert == nil)

    engine.reset()
    let eligible = proximityDrone(
        id: "A",
        longitude: 0,
        team: true,
        eligible: true
    )
    let output = engine.update(drones: [eligible, nearbyUnknown], thresholdFeet: 40)
    #expect(output.activeAlert?.pairKey == "A|B")
    #expect(output.activeAlert?.horizontalSeparationFeet ?? 100 < 40)
}

@Test func proximityAlertUsesVerticalSpacingOnlyForTwoTeamDrones() {
    var engine = RidProximityAlertEngine()
    let eligibleTeam = proximityDrone(
        id: "A",
        longitude: 0,
        altitude: 100,
        team: true,
        eligible: true
    )
    let highUnknown = proximityDrone(
        id: "B",
        longitude: 0.00005,
        altitude: 200,
        team: false,
        eligible: false
    )
    #expect(engine.update(drones: [eligibleTeam, highUnknown], thresholdFeet: 40).activeAlert != nil)

    engine.reset()
    let highTeam = proximityDrone(
        id: "B",
        longitude: 0.00005,
        altitude: 200,
        team: true,
        eligible: false
    )
    #expect(engine.update(drones: [eligibleTeam, highTeam], thresholdFeet: 40).activeAlert == nil)
}

@Test func proximityAlertSuspendsResumesAndClearsAfterAndroidDelay() {
    var engine = RidProximityAlertEngine()
    let start = Date(timeIntervalSince1970: 1_000)
    let first = proximityDrone(id: "A", longitude: 0, team: true, eligible: true)
    let second = proximityDrone(id: "B", longitude: 0.00005, team: true, eligible: false)

    let active = engine.update(drones: [first, second], thresholdFeet: 40, now: start)
    #expect(active.activeAlert != nil)
    let suspended = engine.suspend()
    #expect(suspended.activeAlert == nil)
    #expect(suspended.canResume)
    #expect(engine.resume().activeAlert != nil)

    let separated = proximityDrone(id: "B", longitude: 0.001, team: true, eligible: false)
    #expect(engine.update(drones: [first, separated], thresholdFeet: 40, now: start).activeAlert != nil)
    #expect(engine.update(drones: [first, separated], thresholdFeet: 40, now: start.addingTimeInterval(2.9)).activeAlert != nil)
    #expect(engine.update(drones: [first, separated], thresholdFeet: 40, now: start.addingTimeInterval(3.1)).activeAlert == nil)
}

@Test func predictiveHeadAlertsBeforeReportedPositionsCrossThreshold() {
    let firstDate = Date(timeIntervalSince1970: 2_000)
    let secondDate = firstDate.addingTimeInterval(1)
    let firstInitial = proximityDrone(
        id: "A",
        longitude: 0,
        sampleDate: firstDate,
        team: true,
        eligible: true
    )
    let secondInitial = proximityDrone(
        id: "B",
        longitude: 0.00030,
        sampleDate: firstDate,
        team: true,
        eligible: false
    )
    let firstCurrent = proximityDrone(
        id: "A",
        longitude: 0,
        sampleDate: secondDate,
        team: true,
        eligible: true
    )
    let secondCurrent = proximityDrone(
        id: "B",
        longitude: 0.00020,
        sampleDate: secondDate,
        team: true,
        eligible: false
    )

    var predictive = RidProximityAlertEngine()
    #expect(predictive.update(
        drones: [firstInitial, secondInitial],
        thresholdFeet: 40,
        predictiveEnabled: true,
        now: firstDate
    ).activeAlert == nil)
    let projected = predictive.update(
        drones: [firstCurrent, secondCurrent],
        thresholdFeet: 40,
        predictiveEnabled: true,
        now: secondDate
    )
    #expect(projected.activeAlert != nil)
    #expect(projected.activeAlert?.horizontalSeparationFeet ?? 100 < 40)
    #expect(projected.activeAlert?.currentHorizontalSeparationFeet ?? 0 > 40)
    #expect(projected.activeAlert?.usesProjection == true)

    var reportedOnly = RidProximityAlertEngine()
    _ = reportedOnly.update(
        drones: [firstInitial, secondInitial],
        thresholdFeet: 40,
        predictiveEnabled: false,
        now: firstDate
    )
    #expect(reportedOnly.update(
        drones: [firstCurrent, secondCurrent],
        thresholdFeet: 40,
        predictiveEnabled: false,
        now: secondDate
    ).activeAlert == nil)
}

private func proximityDrone(
    id: String,
    longitude: Double,
    altitude: Double? = 100,
    sampleDate: Date = Date(),
    team: Bool,
    eligible: Bool
) -> RidProximityDrone {
    RidProximityDrone(
        remoteID: id,
        mappedID: "MAP-\(id)",
        latitude: 39,
        longitude: -105 + longitude,
        altitudeMeters: altitude,
        sampleDate: sampleDate,
        teamDrone: team,
        localAlertEligible: eligible
    )
}

@Test func relativePositionReportsOperatorRangeAndBearing() throws {
    let east = try #require(RidGeometry.relativePosition(
        fromLatitude: 0,
        longitude: 0,
        toLatitude: 0,
        longitude: 0.001
    ))
    #expect(abs(east.distanceMeters - 111.195) < 0.1)
    #expect(abs(east.bearingDegrees - 90) < 0.001)
    #expect(east.cardinalDirection == "E")

    let north = try #require(RidGeometry.relativePosition(
        fromLatitude: 39,
        longitude: -105,
        toLatitude: 39.001,
        longitude: -105
    ))
    #expect(abs(north.bearingDegrees) < 0.001)
    #expect(north.cardinalDirection == "N")
    #expect(RidGeometry.relativePosition(
        fromLatitude: 91,
        longitude: 0,
        toLatitude: 0,
        longitude: 0
    ) == nil)
}

@Test func closestTrafficPairReportsThreeDimensionalSeparationWhenAvailable() throws {
    let closest = try #require(RidTrafficSeparation.closestPair(in: [
        RidTrafficPosition(aircraftID: "ALPHA", latitude: 0, longitude: 0, altitudeMeters: 100),
        RidTrafficPosition(aircraftID: "BRAVO", latitude: 0, longitude: 0.0001, altitudeMeters: 103),
        RidTrafficPosition(aircraftID: "CHARLIE", latitude: 0, longitude: 0.01, altitudeMeters: 100),
    ]))

    #expect(closest.firstAircraftID == "ALPHA")
    #expect(closest.secondAircraftID == "BRAVO")
    #expect(abs(closest.horizontalMeters - 11.1195) < 0.1)
    #expect(closest.verticalMeters == 3)
    #expect(abs((closest.threeDimensionalMeters ?? 0) - hypot(11.1195, 3)) < 0.1)
}

@Test func trafficSeparationHandlesMissingAltitudeAndInsufficientTraffic() throws {
    let pair = try #require(RidTrafficSeparation.closestPair(in: [
        RidTrafficPosition(aircraftID: "ALPHA", latitude: 39, longitude: -105, altitudeMeters: nil),
        RidTrafficPosition(aircraftID: "BRAVO", latitude: 39.001, longitude: -105, altitudeMeters: 100),
    ]))
    #expect(pair.verticalMeters == nil)
    #expect(pair.threeDimensionalMeters == nil)
    #expect(RidTrafficSeparation.closestPair(in: [
        RidTrafficPosition(aircraftID: "ALPHA", latitude: 39, longitude: -105, altitudeMeters: nil),
    ]) == nil)
}

@Test func streamEndpointUsesMediaMtxLoopbackContract() {
    let endpoint = MediaStreamEndpoint(designator: "drone-one")

    #expect(endpoint.rtmpPort == 1935)
    #expect(endpoint.loopbackRtspURL?.absoluteString == "rtsp://127.0.0.1:8554/drone-one")
    #expect(endpoint.loopbackHlsURL?.absoluteString == "http://127.0.0.1:8888/drone-one/index.m3u8")
}

@Test func bluetoothServiceDataDecodesBasicID() throws {
    let advertisement = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(message: basicIDMessage("RID2CALTOPO12345"), counter: 7)
    )

    #expect(advertisement.messageCounter == 7)
    #expect(advertisement.messages.count == 1)
    guard case let .basicID(basicID) = advertisement.messages[0].payload else {
        Issue.record("Expected a Basic ID payload")
        return
    }
    #expect(basicID.idType == 1)
    #expect(basicID.aircraftType == 2)
    #expect(basicID.uasID == "RID2CALTOPO12345")
}

@Test func locationDecoderMatchesAndroidWireFormulas() throws {
    let advertisement = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(message: locationMessage(), counter: 9)
    )

    guard case let .location(location) = advertisement.messages[0].payload else {
        Issue.record("Expected a Location payload")
        return
    }
    #expect(abs(location.latitude - 39.7392) < 0.0000001)
    #expect(abs(location.longitude - -104.9903) < 0.0000001)
    #expect(location.pressureAltitudeMeters == 100)
    #expect(location.geodeticAltitudeMeters == 200)
    #expect(location.heightMeters == 50)
    #expect(location.directionDegrees == 90)
    #expect(location.horizontalSpeedMetersPerSecond == 10)
    #expect(location.verticalSpeedMetersPerSecond == -2)
}

@Test func packedBasicIDAndLocationProduceObservation() async throws {
    let basic = basicIDMessage("SESSION-ID")
    let serial = basicIDMessage("RID2CALTOPO12345")
    let location = locationMessage()
    let pack = messagePack([basic, serial, location])
    let advertisement = try OpenDroneIDParser.parseBluetoothServiceData(
        bluetoothServiceData(message: pack, counter: 11)
    )

    #expect(advertisement.messages.count == 3)
    let assembler = OpenDroneIDTrackAssembler()
    let observation = await assembler.ingest(
        advertisement,
        transmitterID: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!,
        source: .bluetoothExtended,
        receivedAt: Date(timeIntervalSince1970: 1_700_000_000),
        signalStrengthDbm: -48
    )

    #expect(observation?.aircraftId == "RID2CALTOPO12345")
    #expect(observation?.source == .bluetoothExtended)
    #expect(observation?.altitudeMeters == 100)
    #expect(observation?.signalStrengthDbm == -48)
}

@Test func malformedServiceDataIsRejected() {
    #expect(throws: OpenDroneIDParserError.invalidApplicationCode(0x00)) {
        try OpenDroneIDParser.parseBluetoothServiceData(Data([0x00, 0x01]))
    }
    #expect(throws: OpenDroneIDParserError.truncatedMessage(expected: 25, actual: 3)) {
        try OpenDroneIDParser.parseBluetoothServiceData(Data([0x0D, 0x01, 0x02, 0x12, 0x41]))
    }
}

@Test func externalRawAstmMessagesDecodeWithoutBluetoothEnvelope() throws {
    let raw = Data(basicIDMessage("RAW-WIFI-01") + locationMessage())
    let advertisement = try OpenDroneIDParser.parseExternalDatagram(raw)

    #expect(advertisement.messageCounter == 0)
    #expect(advertisement.messages.count == 2)
    guard case let .basicID(basicID) = advertisement.messages[0].payload else {
        Issue.record("Expected raw Basic ID payload")
        return
    }
    #expect(basicID.uasID == "RAW-WIFI-01")
    guard case let .location(location) = advertisement.messages[1].payload else {
        Issue.record("Expected raw Location payload")
        return
    }
    #expect(abs(location.latitude - 39.7392) < 0.0000001)
}

@Test func externalBluetoothEnvelopeAndMessagePackDecode() throws {
    let pack = messagePack([basicIDMessage("PACK-WIFI-01"), locationMessage()])
    let serviceAdvertisement = try OpenDroneIDParser.parseExternalDatagram(
        bluetoothServiceData(message: pack, counter: 23)
    )
    let rawPackAdvertisement = try OpenDroneIDParser.parseExternalDatagram(Data(pack))

    #expect(serviceAdvertisement.messageCounter == 23)
    #expect(serviceAdvertisement.messages.count == 2)
    #expect(rawPackAdvertisement.messageCounter == 0)
    #expect(rawPackAdvertisement.messages == serviceAdvertisement.messages)
}

@Test func malformedExternalRawAstmDatagramIsRejected() {
    #expect(throws: OpenDroneIDParserError.invalidRawDatagramLength(24)) {
        try OpenDroneIDParser.parseExternalDatagram(Data(repeating: 0, count: 24))
    }
    #expect(throws: OpenDroneIDParserError.invalidRawDatagramLength(26)) {
        try OpenDroneIDParser.parseExternalDatagram(Data(repeating: 0, count: 26))
    }
}

@Test func externalReceiverJSONProducesNormalizedObservation() throws {
    let observation = try ExternalRIDObservationDecoder.decode(Data(#"""
    {
      "aircraft_id":"EXT-RID-01",
      "source":"wifiNan",
      "timestamp_ms":1700000000123,
      "latitude":39.7392,
      "longitude":-104.9903,
      "altitude_m":1620.5,
      "heading_deg":92,
      "speed_mps":11.5,
      "operator_latitude":39.74,
      "operator_longitude":-104.99,
      "rssi_dbm":-61
    }
    """#.utf8))

    #expect(observation.aircraftId == "EXT-RID-01")
    #expect(observation.source == .wifiNan)
    #expect(observation.receivedAt == Date(timeIntervalSince1970: 1_700_000_000.123))
    #expect(observation.altitudeMeters == 1_620.5)
    #expect(observation.operatorLatitude == 39.74)
    #expect(observation.signalStrengthDbm == -61)
}

@Test func externalReceiverRejectsInvalidCoordinates() {
    #expect(throws: ExternalRIDObservationDecoderError.invalidCoordinate) {
        try ExternalRIDObservationDecoder.decode(Data(#"""
        {
          "aircraft_id":"EXT-RID-01",
          "latitude":91,
          "longitude":-104.9903
        }
        """#.utf8))
    }
}

@Test func trackStoreMatchesAndroidDedupAndSpeedPolicy() async {
    let policy = RidTrackPolicy(maximumSpeedMetersPerSecond: 90)
    let store = RidTrackStore(policy: policy)
    let start = Date(timeIntervalSince1970: 1_700_000_000)

    let first = await store.ingest(trackObservation(
        id: "rid-test_01",
        at: start,
        latitude: 39.7392,
        longitude: -104.9903
    ))
    guard case let .accepted(firstTrack) = first else {
        Issue.record("Expected first waypoint to be accepted")
        return
    }
    #expect(firstTrack.aircraftID == "RIDTEST01")

    let duplicate = await store.ingest(trackObservation(
        id: "RIDTEST01",
        at: start.addingTimeInterval(1),
        latitude: 39.7392,
        longitude: -104.9903
    ))
    guard case let .signalOnly(duplicateTrack, reason) = duplicate else {
        Issue.record("Expected duplicate to refresh signal without adding a point")
        return
    }
    #expect(reason == .duplicatePosition)
    #expect(duplicateTrack.points.count == 1)
    #expect(duplicateTrack.lastSignalAt == start.addingTimeInterval(1))

    let impossible = await store.ingest(trackObservation(
        id: "RIDTEST01",
        at: start.addingTimeInterval(2),
        latitude: 40.7392,
        longitude: -104.9903
    ))
    guard case let .signalOnly(impossibleTrack, reason) = impossible else {
        Issue.record("Expected coordinate jump to be signal-only")
        return
    }
    guard case let .implausibleSpeed(speed) = reason else {
        Issue.record("Expected implausible-speed reason")
        return
    }
    #expect(speed > 90)
    #expect(impossibleTrack.points.count == 1)
}

@Test func trackStoreAcceptsStationaryKeepaliveAndAgesTracks() async {
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    let store = RidTrackStore()
    _ = await store.ingest(trackObservation(
        id: "KEEPALIVE",
        at: start,
        latitude: 39.7392,
        longitude: -104.9903
    ))
    let keepalive = await store.ingest(trackObservation(
        id: "KEEPALIVE",
        at: start.addingTimeInterval(3),
        latitude: 39.7392,
        longitude: -104.9903
    ))
    guard case let .accepted(track) = keepalive else {
        Issue.record("Expected the three-second stationary keepalive to be retained")
        return
    }
    #expect(track.points.count == 2)
    #expect(await store.activeSnapshot(at: start.addingTimeInterval(32)).count == 1)
    #expect(await store.activeSnapshot(at: start.addingTimeInterval(34)).isEmpty)
    #expect(await store.removeInactive(at: start.addingTimeInterval(34)).map(\.aircraftID) == ["KEEPALIVE"])
}

@Test func trackGeoJsonMatchesAndroidArchiveEnvelope() async throws {
    let store = RidTrackStore()
    let start = Date(timeIntervalSince1970: 1_700_000_000)
    _ = await store.ingest(trackObservation(
        id: "ARCHIVE01",
        at: start,
        latitude: 39.7392,
        longitude: -104.9903
    ))
    _ = await store.ingest(trackObservation(
        id: "ARCHIVE01",
        at: start.addingTimeInterval(10),
        latitude: 39.7398,
        longitude: -104.9897
    ))
    let track = try #require(await store.snapshot().first)
    let data = try RidTrackGeoJSON.encode(
        track: track,
        metadata: RidTrackArchiveMetadata(
            mappedID: "ALPHA1",
            organization: "NCSSAR",
            incident: "Test Incident",
            operationalPeriod: "1",
            mapID: "map-123"
        )
    )
    let root = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    #expect(root["type"] as? String == "FeatureCollection")
    let features = try #require(root["features"] as? [[String: Any]])
    let feature = try #require(features.first)
    let geometry = try #require(feature["geometry"] as? [String: Any])
    #expect(geometry["type"] as? String == "LineString")
    let coordinates = try #require(geometry["coordinates"] as? [[String]])
    #expect(coordinates.count == 2)
    #expect(coordinates[0][0] == "-104.990300")
    #expect(coordinates[0][1] == "39.739200")
    #expect(coordinates[0][3] == "1700000000000")
    let properties = try #require(feature["properties"] as? [String: Any])
    #expect(properties["title"] as? String == "ALPHA1")
    let r2c = try #require(properties["r2c_prop"] as? [String: Any])
    #expect(r2c["rid"] as? String == "ARCHIVE01")
    #expect(r2c["org"] as? String == "NCSSAR")
    #expect(r2c["map_id"] as? String == "map-123")
}

@Test func caltopoSignerMatchesAndroidHmacContract() throws {
    let signature = try CaltopoRequestSigner.signature(
        method: "POST",
        path: "/api/v1/map/map123/LiveTrack",
        expiresMilliseconds: 1_700_000_010_000,
        payload: #"{"type":"Feature"}"#,
        credentialSecretBase64: "c2VjcmV0"
    )
    #expect(signature == "4c5G2oSEYaecJyf81tFERXDlcClrG4EZZPYWysPN+4Q=")
}

@Test func caltopoPointRequestMatchesAndroidPositionEndpoint() async throws {
    let client = try CaltopoLiveClient(configuration: CaltopoLiveConfiguration(
        domainAndPort: "caltopo.com",
        mapID: "map123",
        credentialID: "credential",
        credentialSecretBase64: "c2VjcmV0"
    ))
    let observation = RidObservation(
        source: .bluetoothLegacy,
        aircraftId: "RID01",
        receivedAt: Date(timeIntervalSince1970: 1_700_000_000),
        latitude: 39.7392,
        longitude: -104.9903,
        altitudeMeters: 1_600.8,
        headingDegrees: 92,
        speedMetersPerSecond: 10
    )
    let request = try await client.makePointRequest(remoteID: "RID01", observation: observation)
    let url = try #require(request.url)
    let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
    #expect(components.path == "/api/v1/position/report/DRONE")
    let values = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
    #expect(values["id"] == "RID01")
    #expect(values["lat"] == "39.7392000")
    #expect(values["lng"] == "-104.9903000")
    #expect(values["elevation"] == "1600")
    let aircraftData = try #require(values["aircraft"]?.data(using: .utf8))
    let aircraft = try #require(JSONSerialization.jsonObject(with: aircraftData) as? [String: Double])
    #expect(abs((aircraft["gs"] ?? 0) - 19.4384449) < 0.000001)
    #expect(aircraft["track"] == 92)
}

@Test func caltopoStopRequestMatchesAndroidLiveTrackDelete() async throws {
    let client = try CaltopoLiveClient(configuration: CaltopoLiveConfiguration(
        domainAndPort: "caltopo.com",
        mapID: "map123",
        credentialID: "credential",
        credentialSecretBase64: "c2VjcmV0"
    ))
    let request = try await client.makeStopLiveTrackRequest(
        liveTrackID: "track456",
        now: Date(timeIntervalSince1970: 1_700_000_000)
    )
    #expect(request.httpMethod == "DELETE")
    let components = try #require(request.url.flatMap {
        URLComponents(url: $0, resolvingAgainstBaseURL: false)
    })
    #expect(components.path == "/api/v1/map/map123/LiveTrack/track456")
    let values = Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
    #expect(values["id"] == "credential")
    #expect(values["expires"] == "1700000010000")
    let expectedSignature = try CaltopoRequestSigner.signature(
        method: "DELETE",
        path: components.path,
        expiresMilliseconds: 1_700_000_010_000,
        payload: "",
        credentialSecretBase64: "c2VjcmV0"
    )
    #expect(values["signature"] == expectedSignature)
}

@Test func mediaServerEventDecoderPreservesPublisherIdentity() throws {
    let event = try MediaServerEventDecoder.decode(
        json: #"{"type":"stream_started","path":"scout1","publisherConnId":"10.0.0.1:12000"}"#
    )

    #expect(event == .streamStarted(path: "scout1", publisherConnectionID: "10.0.0.1:12000"))
}

@Test func mediaMtxLogParserUnwrapsJSONAndTracksCleanPublisherStop() {
    var parser = MediaMTXLogEventParser()

    let started = parser.parse(
        line: #"{"timestamp":"2026-07-18T00:00:00Z","level":"INF","message":"MediaMTX v1.16.2"}"#
    )
    #expect(started == .serverStarted(version: "v1.16.2"))

    let published = parser.parse(
        line: "[RTMP] [conn 10.0.0.1:12000] is publishing to path 'scout1'"
    )
    #expect(published == .streamStarted(
        path: "scout1",
        publisherConnectionID: "10.0.0.1:12000"
    ))

    let command = parser.parse(
        line: "[RTMP] [conn 10.0.0.1:12000] RTMP control: received command 'FCUnpublish' (id=1) during publish on path 'scout1'"
    )
    #expect(command == nil)

    let stopped = parser.parse(line: "[RTMP] [conn 10.0.0.1:12000] closed: terminated")
    #expect(stopped == .streamStopped(
        path: "scout1",
        publisherConnectionID: "10.0.0.1:12000"
    ))
}

@Test func mediaMtxLogParserReportsUnexpectedDisconnect() {
    var parser = MediaMTXLogEventParser()
    _ = parser.parse(line: "[RTMP] [conn 192.168.1.10:5000] is publishing to path 'alpha'")

    let event = parser.parse(line: "[RTMP] [conn 192.168.1.10:5000] closed: unexpected EOF")
    #expect(event == .streamError(
        path: "alpha",
        publisherConnectionID: "192.168.1.10:5000",
        detail: "RTMP closed: publisher disconnected unexpectedly"
    ))
}

private func bluetoothServiceData(message: [UInt8], counter: UInt8) -> Data {
    Data([OpenDroneIDParser.applicationCode, counter] + message)
}

@Test func aircraftIdentityBuildsAndroidCompatibleMappedID() {
    let identity = RidAircraftIdentity(
        remoteID: "1581F8HGX12345678901",
        organization: "NCSSAR",
        pilotCallsign: " Eagle 1! ",
        droneDescription: "DJI Matrice 4TD"
    )

    #expect(identity.isComplete)
    #expect(identity.mappedID == "Eagle1DjMtrc4td")
    #expect(RidAircraftIdentity.modelAbbreviation("Autel Evo 2 Dual 640T") == "lEv2Dl640t")
    #expect(RidAircraftIdentity.modelAbbreviation("Potensic Atom LT") == "PtnscAtm2lt")
}

@Test func aircraftIdentityFallsBackToRemoteIDWithoutCallsign() {
    let identity = RidAircraftIdentity(
        remoteID: "DEMOALPHA01",
        organization: "NCSSAR",
        pilotCallsign: "!!!",
        droneDescription: "DJI Mini 4 Pro"
    )

    #expect(identity.isComplete)
    #expect(identity.mappedID == "DEMOALPHA01")
}

@Test func orgConfigTokenRoundTripsAndroidWireFormatAndQrURI() throws {
    let original = OrgConfigJoinToken(
        organizationName: "NCSSAR",
        driveFileID: "1AbCdEfGhIjKlMn",
        isPublic: true,
        version: 1
    )
    let token = try OrgConfigTokenCodec.encode(original)

    #expect(token.hasPrefix("R2C1:"))
    #expect(OrgConfigTokenCodec.decode(token) == original)
    #expect(OrgConfigTokenCodec.decode("r2c1://\(token.dropFirst(5))") == original)
    #expect(OrgConfigTokenCodec.decode("R2C1:UGedORwNSuM8Sk4NMR5dSs25A0m0NDWVPNAFSA0IcrmsQv0MDCPiKRszONFNFvEC") == original)
    #expect(OrgConfigTokenCodec.decode("R2C1:not-valid") == nil)
}

@Test func orgConfigBundleDecryptsCredentialsAndReadsRidmap() throws {
    let credentialObject: [String: Any] = [
        "type": "ct_credentials",
        "org_name": "NCSSAR",
        "team_id": "team-1",
        "credential_id": "credential-1",
        "credential_secret": "c2VjcmV0",
        "domain_and_port": "caltopo.example",
        "incident": "Search 42",
        "op_period": "2",
        "tracker_api_key": "tracker-token",
        "tracker_url_prefix": "https://tracker.example",
        "use_peers": true,
        "predictive_head_enabled": false,
        "proximity_alert_spacing_feet": 40,
    ]
    let credentialData = try JSONSerialization.data(withJSONObject: credentialObject, options: [.sortedKeys])
    let credentialJSON = String(decoding: credentialData, as: UTF8.self)
    let encrypted = try androidCompatibleEncryptPayload(credentialJSON)
    let faaPlaintext: [String: Any] = [
        "source_label": "NCSSAR FAA",
        "notam_client_id": "faa-client",
        "notam_client_secret": "faa-secret",
    ]
    let faaJSON = String(decoding: try JSONSerialization.data(withJSONObject: faaPlaintext), as: UTF8.self)
    let faaPayload = try JSONSerialization.data(withJSONObject: [
        "type": "ct_faa_credentials_enc",
        "enc": try androidCompatibleEncryptPayload(faaJSON),
    ])
    let mutualAidPlaintext: [String: Any] = [
        "type": "ct_mutual_aid_credentials",
        "team_id": "ma-team",
        "credential_id": "ma-credential",
        "credential_secret": "ma-secret",
        "source_label": "Mutual Org",
        "target_folder_hint": "MAI",
    ]
    let mutualAidJSON = String(decoding: try JSONSerialization.data(withJSONObject: mutualAidPlaintext), as: UTF8.self)
    let root: [String: Any] = [
        "format": "rid2caltopo_org_config",
        "version": 1,
        "org_name": "NCSSAR",
        "configs": [
            [
                "type": "ct_ridmap",
                "map": [[
                    "remoteId": "1581F8HGX123",
                    "mappedId": "Eagle1DjMtrc4td",
                    "org": "NCSSAR",
                    "model": "DJI Matrice 4TD",
                    "owner": "Eagle1",
                ]],
            ],
            ["type": "ct_credentials_enc", "enc": encrypted],
            [
                "type": "ct_faa_remote_config",
                "faa_payload_enc": String(decoding: faaPayload, as: UTF8.self),
            ],
            ["type": "ct_credentials_enc", "enc": try androidCompatibleEncryptPayload(mutualAidJSON)],
        ],
    ]
    let bundle = try OrgConfigTokenCodec.parseBundle(JSONSerialization.data(withJSONObject: root))

    #expect(bundle.organizationName == "NCSSAR")
    #expect(bundle.mappings.first?.mappedID == "Eagle1DjMtrc4td")
    #expect(bundle.credentials?.credentialSecret == "c2VjcmV0")
    #expect(bundle.credentials?.trackerURLPrefix == "https://tracker.example")
    #expect(bundle.credentials?.usePeers == true)
    #expect(bundle.credentials?.predictiveHeadEnabled == false)
    #expect(bundle.faaConfig?.clientID == "faa-client")
    #expect(bundle.mutualAidTemplate?.credentialID == "ma-credential")
}

@Test func androidConfigTokenCodecRecognizesAllQrFamilies() throws {
    let org = "R2C1:UGedORwNSuM8Sk4NMR5dSs25A0m0NDWVPNAFSA0IcrmsQv0MDCPiKRszONFNFvEC"
    let faa = try sharedConfigToken(prefix: "R2CFAA1:", displayKey: "l", display: "FAA Shared", fileID: "faa-file")
    let mutualAid = try sharedConfigToken(prefix: "R2CMA1:", displayKey: "o", display: "Mutual Org", fileID: "ma-file")

    #expect(AndroidConfigTokenCodec.decode(org)?.kind == .organization)
    #expect(AndroidConfigTokenCodec.decode("r2cfaa1://\(faa.dropFirst(8))")?.kind == .faa)
    #expect(AndroidConfigTokenCodec.decode("r2cma1://\(mutualAid.dropFirst(7))")?.kind == .mutualAid)
}

@Test func androidConfigCodecParsesFaaAndMutualAidBundles() throws {
    let faaPlaintext: [String: Any] = [
        "source_label": "Shared FAA",
        "notam_api_base_url": "https://api.example",
        "notam_token_url": "https://token.example",
        "notam_client_id": "client",
        "notam_client_secret": "secret",
        "notam_scope": "scope",
    ]
    let faaJSON = String(decoding: try JSONSerialization.data(withJSONObject: faaPlaintext), as: UTF8.self)
    let faaWrapper = ["type": "ct_faa_credentials_enc", "enc": try androidCompatibleEncryptPayload(faaJSON)]
    let faa = try AndroidConfigTokenCodec.parseFaaBundle(JSONSerialization.data(withJSONObject: faaWrapper))
    #expect(faa.clientID == "client")
    #expect(faa.sourceLabel == "Shared FAA")

    let profile: [String: Any] = [
        "profile_id": "profile-1",
        "display_name": "Mutual Aid 1",
        "team_id": "team",
        "credential_id": "credential",
        "credential_secret": "secret",
        "domain_and_port": "caltopo.com",
        "incident": "Search 42",
        "op_period": "2",
        "target_map_id": "map-42",
        "expires_at_epoch_ms": 1_900_000_000_000 as Int64,
    ]
    let profileJSON = String(decoding: try JSONSerialization.data(withJSONObject: profile), as: UTF8.self)
    let maRoot: [String: Any] = [
        "format": "rid2caltopo_mutual_aid_profile",
        "version": 1,
        "profile": ["type": "caltopo_profile_enc", "enc": try androidCompatibleEncryptPayload(profileJSON)],
    ]
    let mutualAid = try AndroidConfigTokenCodec.parseMutualAidBundle(JSONSerialization.data(withJSONObject: maRoot))
    #expect(mutualAid.profileID == "profile-1")
    #expect(mutualAid.targetMapID == "map-42")
}

@Test func trackerCoordinationWireMatchesAndroidContract() throws {
    let endpoint = try TrackerCoordinationEndpoint.webSocketURL(from: "https://tracker.example/r2c/")
    #expect(endpoint.absoluteString == "wss://tracker.example/r2c/ws/r2c")

    let client = TrackerCoordinationClient(
        mapID: "MAP1",
        zoneID: "zone-alpha",
        name: "Alpha",
        appVersion: "0.1(1)",
        appVersionCode: 1
    )
    let hello = try jsonObject(TrackerCoordinationWire.hello(
        client: client,
        position: TrackerCoordinationPosition(latitude: 39.1, longitude: -104.2, caltopoRTTMilliseconds: 750)
    ))
    #expect(hello["type"] as? String == "hello")
    #expect(hello["mapId"] as? String == "MAP1")
    #expect(hello["incidentId"] as? String == "MAP1")
    #expect(hello["zoneId"] as? String == "zone-alpha")
    #expect(hello["guid"] as? String == "zone-alpha")
    #expect((hello["appVersionCode"] as? NSNumber)?.intValue == 1)

    let identity = TrackerCoordinationIdentity(
        remoteID: "DRONE1",
        mappedID: "1sar7DjMn4Pr",
        organization: "NCSSAR",
        model: "DJI Mini 4 Pro",
        ownerName: "1sar7"
    )
    let first = try jsonObject(TrackerCoordinationWire.firstSighting(
        client: client,
        sighting: TrackerCoordinationSighting(
            identity: identity,
            droneTimestampMilliseconds: 1_710_000_001_000,
            latitude: 39.2,
            longitude: -104.3,
            altitudeMeters: 1_620,
            distanceFromZoneMeters: 50,
            headingDegrees: 92,
            groundSpeedKnots: 20
        )
    ))
    #expect(first["type"] as? String == "first_sighting")
    #expect(first["remoteId"] as? String == "DRONE1")
    #expect(first["trackLabel"] as? String == "1sar7DjMn4Pr")
    #expect(first["ownerName"] as? String == "1sar7")
    let telemetry = first["telemetry"] as? [String: Any]
    #expect((telemetry?["headingDeg"] as? NSNumber)?.doubleValue == 92)
}

@Test func trackerOwnershipRequiresLocalLeaseAndLocalSave() throws {
    var state = TrackerCoordinationProtocolState(localZoneID: "zone-alpha")
    state.transportOpened(helloSentAtMilliseconds: 1_000)

    let ownerEvent = try state.handleIncoming(
        Data(#"{"type":"owner_assigned","remoteId":"DRONE1","ownerGuid":"zone-alpha","leaseSeq":4}"#.utf8),
        receivedAtMilliseconds: 1_100
    )
    #expect(ownerEvent == [
        .ownershipChanged(remoteID: "DRONE1", ownerZoneID: "zone-alpha", localOwner: true, alertEligible: false),
    ])
    #expect(state.isLocalOwner(remoteID: "DRONE1"))
    #expect(!state.isLocalAlertEligible(remoteID: "DRONE1"))

    #expect(state.confirmLocally(remoteID: "DRONE1") ==
        .ownershipChanged(remoteID: "DRONE1", ownerZoneID: "zone-alpha", localOwner: true, alertEligible: true))
    #expect(state.isLocalAlertEligible(remoteID: "DRONE1"))

    let peerConfirmation = try state.handleIncoming(
        Data(#"{"type":"drone_confirmed","remoteId":"DRONE1","confirmedByGuid":"zone-bravo","mappedId":"Bravo1","org":"NCSSAR","model":"Mavic 3","ownerName":"Bravo"}"#.utf8),
        receivedAtMilliseconds: 1_200
    )
    #expect(peerConfirmation.contains(
        .ownershipChanged(remoteID: "DRONE1", ownerZoneID: "zone-bravo", localOwner: false, alertEligible: false)
    ))
    #expect(!state.isLocalOwner(remoteID: "DRONE1"))
    #expect(!state.isLocalAlertEligible(remoteID: "DRONE1"))
}

@Test func trackerCoordinationRejectsStaleLeasesAndSelfRelay() throws {
    var state = TrackerCoordinationProtocolState(localZoneID: "zone-alpha")
    _ = try state.handleIncoming(
        Data(#"{"type":"owner_assigned","remoteId":"DRONE1","ownerGuid":"zone-alpha","leaseSeq":7}"#.utf8),
        receivedAtMilliseconds: 1_000
    )
    let stale = try state.handleIncoming(
        Data(#"{"type":"owner_assigned","remoteId":"DRONE1","ownerGuid":"zone-bravo","leaseSeq":6}"#.utf8),
        receivedAtMilliseconds: 1_100
    )
    #expect(stale == [.ignored(messageType: "stale owner_assigned")])
    #expect(state.isLocalOwner(remoteID: "DRONE1"))

    let selfRelay = try state.handleIncoming(
        Data(#"{"type":"relay_sighting","remoteId":"DRONE1","fromZoneId":"zone-alpha","lat":39.2,"lng":-104.3,"droneTs":1234}"#.utf8),
        receivedAtMilliseconds: 1_200
    )
    #expect(selfRelay == [.ignored(messageType: "relay_sighting")])

    let peerRelay = try state.handleIncoming(
        Data(#"{"type":"relay_sighting","remoteId":"DRONE1","fromZoneId":"zone-bravo","lat":39.2,"lng":-104.3,"altM":1620,"droneTs":1234}"#.utf8),
        receivedAtMilliseconds: 1_300
    )
    guard case let .relaySighting(relay) = peerRelay.first else {
        Issue.record("Expected peer relay sighting")
        return
    }
    #expect(relay.sourceZoneID == "zone-bravo")
    #expect(relay.altitudeMeters == 1_620)
}

@Test func trackerReconnectClearsStaleHeartbeatWatchdogState() throws {
    var state = TrackerCoordinationProtocolState(localZoneID: "zone-alpha")
    state.transportOpened(helloSentAtMilliseconds: 1_000)
    let firstSequence = state.nextHeartbeatSequence(sentAtMilliseconds: 2_000)
    #expect(firstSequence == 1)
    #expect(state.requiresReconnectForMissingAcknowledgement(nowMilliseconds: 12_001) == "missed hello_ack")

    state.transportOpened(helloSentAtMilliseconds: 20_000)
    let helloAck = try state.handleIncoming(
        Data(#"{"type":"hello_ack"}"#.utf8),
        receivedAtMilliseconds: 20_100
    )
    #expect(helloAck == [.helloAcknowledged(recommendedAppVersionCode: nil, updateURL: nil)])
    #expect(state.requiresReconnectForMissingAcknowledgement(nowMilliseconds: 20_200) == nil)

    let secondSequence = state.nextHeartbeatSequence(sentAtMilliseconds: 21_000)
    #expect(secondSequence == 1)
    let heartbeatAck = try state.handleIncoming(
        Data(#"{"type":"heartbeat_ack","clientSeq":1,"ownerLeaseExpireTs":30000}"#.utf8),
        receivedAtMilliseconds: 21_050
    )
    #expect(heartbeatAck == [.heartbeatAcknowledged(sequence: 1, ownerLeaseExpiresAtMilliseconds: 30_000)])
    #expect(state.requiresReconnectForMissingAcknowledgement(nowMilliseconds: 40_000) == nil)
}

private func jsonObject(_ data: Data) throws -> [String: Any] {
    try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
}

private func sharedConfigToken(
    prefix: String,
    displayKey: String,
    display: String,
    fileID: String
) throws -> String {
    let object: [String: Any] = [displayKey: display, "f": fileID, "p": 1, "v": 1]
    let json = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    let key = Array("RID2CaltopoQR".utf8)
    let encrypted = Array(json).enumerated().map { index, value in value ^ key[index % key.count] }
    let standard = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
    let custom = Array("r2cNOPQRSTUVWXYZABCDEFGHIJKLMnopqstuvwxyzabdefghijklm013456789+/=")
    let remapped = Data(encrypted).base64EncodedString().map { character -> Character in
        guard let index = standard.firstIndex(of: character) else { return character }
        return custom[index]
    }
    return prefix + String(remapped)
}

private func androidCompatibleEncryptPayload(_ plaintext: String) throws -> String {
    let key = Array("RID2CaltopoQR".utf8)
    let encrypted = Array(plaintext.utf8).enumerated().map { index, value in
        value ^ key[index % key.count]
    }
    return Data(encrypted).base64EncodedString()
}

private func trackObservation(
    id: String,
    at date: Date,
    latitude: Double,
    longitude: Double
) -> RidObservation {
    RidObservation(
        source: .bluetoothLegacy,
        aircraftId: id,
        receivedAt: date,
        latitude: latitude,
        longitude: longitude,
        altitudeMeters: 1_600,
        signalStrengthDbm: -55
    )
}

private func basicIDMessage(_ uasID: String) -> [UInt8] {
    var message = [UInt8](repeating: 0, count: OpenDroneIDParser.messageSize)
    message[0] = 0x02
    message[1] = 0x12
    for (index, byte) in uasID.utf8.prefix(20).enumerated() {
        message[index + 2] = byte
    }
    return message
}

private func locationMessage() -> [UInt8] {
    var message = [UInt8](repeating: 0, count: OpenDroneIDParser.messageSize)
    message[0] = 0x12
    message[1] = 0x00
    message[2] = 90
    message[3] = 40
    message[4] = UInt8(bitPattern: -4)
    writeInt32(397_392_000, into: &message, at: 5)
    writeInt32(-1_049_903_000, into: &message, at: 9)
    writeUInt16(2_200, into: &message, at: 13)
    writeUInt16(2_400, into: &message, at: 15)
    writeUInt16(2_100, into: &message, at: 17)
    writeUInt16(123, into: &message, at: 21)
    return message
}

private func messagePack(_ messages: [[UInt8]]) -> [UInt8] {
    [0xF2, UInt8(OpenDroneIDParser.messageSize), UInt8(messages.count)]
        + messages.flatMap { $0 }
}

private func writeUInt16(_ value: UInt16, into bytes: inout [UInt8], at offset: Int) {
    bytes[offset] = UInt8(truncatingIfNeeded: value)
    bytes[offset + 1] = UInt8(truncatingIfNeeded: value >> 8)
}

private func writeInt32(_ value: Int32, into bytes: inout [UInt8], at offset: Int) {
    let bits = UInt32(bitPattern: value)
    for index in 0 ..< 4 {
        bytes[offset + index] = UInt8(truncatingIfNeeded: bits >> UInt32(index * 8))
    }
}
