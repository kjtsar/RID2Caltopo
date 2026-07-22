import CryptoKit
import Foundation

public struct CaltopoLiveConfiguration: Sendable, Equatable {
    public let domainAndPort: String
    public let mapID: String
    public let credentialID: String
    public let credentialSecretBase64: String

    public init(
        domainAndPort: String = "caltopo.com",
        mapID: String,
        credentialID: String,
        credentialSecretBase64: String
    ) {
        self.domainAndPort = domainAndPort
        self.mapID = mapID
        self.credentialID = credentialID
        self.credentialSecretBase64 = credentialSecretBase64
    }
}

public enum CaltopoLiveClientError: Error, Sendable, Equatable {
    case invalidConfiguration
    case invalidCredentialSecret
    case invalidURL
    case httpStatus(Int, String)
    case missingResult
    case missingLiveTrackID
}

public enum CaltopoRequestSigner {
    public static func signature(
        method: String,
        path: String,
        expiresMilliseconds: Int64,
        payload: String,
        credentialSecretBase64: String
    ) throws -> String {
        guard let keyData = Data(
            base64Encoded: credentialSecretBase64,
            options: [.ignoreUnknownCharacters]
        ), !keyData.isEmpty else {
            throw CaltopoLiveClientError.invalidCredentialSecret
        }
        let message = "\(method) \(path)\n\(expiresMilliseconds)\n\(payload)"
        let code = HMAC<SHA256>.authenticationCode(
            for: Data(message.utf8),
            using: SymmetricKey(data: keyData)
        )
        return Data(code).base64EncodedString()
    }
}

public actor CaltopoLiveClient {
    private let configuration: CaltopoLiveConfiguration
    private let session: URLSession

    public init(
        configuration: CaltopoLiveConfiguration,
        session: URLSession = .shared
    ) throws {
        guard !configuration.domainAndPort.isEmpty,
              !configuration.mapID.isEmpty,
              !configuration.credentialID.isEmpty,
              !configuration.credentialSecretBase64.isEmpty
        else {
            throw CaltopoLiveClientError.invalidConfiguration
        }
        self.configuration = configuration
        self.session = session
    }

    public func startLiveTrack(
        remoteID: String,
        label: String,
        folderID: String? = nil,
        now: Date = Date()
    ) async throws -> String {
        let request = try makeStartLiveTrackRequest(
            remoteID: remoteID,
            label: label,
            folderID: folderID,
            now: now
        )
        let data = try await perform(request)
        let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard let result = root?["result"] as? [String: Any] else {
            throw CaltopoLiveClientError.missingResult
        }
        guard let id = result["id"] as? String, !id.isEmpty else {
            throw CaltopoLiveClientError.missingLiveTrackID
        }
        return id
    }

    public func publishPoint(remoteID: String, observation: RidObservation) async throws {
        _ = try await perform(makePointRequest(remoteID: remoteID, observation: observation))
    }

    public func stopLiveTrack(liveTrackID: String, now: Date = Date()) async throws {
        _ = try await perform(
            makeStopLiveTrackRequest(liveTrackID: liveTrackID, now: now),
            acceptedStatusCodes: [400, 404]
        )
    }

    public func fetchMapArtifacts(now: Date = Date()) async throws -> CaltopoArtifactSnapshot {
        let request = try makeMapSnapshotRequest(now: now)
        let data = try await perform(request)
        return try CaltopoArtifactDecoder.decode(data: data)
    }

    public func publishPhotoClue(_ clue: CaltopoPhotoClue, now: Date = Date()) async throws -> String {
        let requests = try makePhotoClueRequests(clue, now: now)
        for request in requests { _ = try await perform(request) }
        return clue.markerID.uuidString.lowercased()
    }

    func makePhotoClueRequests(_ clue: CaltopoPhotoClue, now: Date) throws -> [URLRequest] {
        guard !clue.teamID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              clue.latitude.isFinite, clue.longitude.isFinite,
              !clue.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !clue.jpegData.isEmpty
        else { throw CaltopoLiveClientError.invalidConfiguration }
        let markerID = clue.markerID.uuidString.lowercased()
        let mediaID = clue.mediaID.uuidString.lowercased()
        let markerPath = "/api/v1/map/\(configuration.mapID)/Marker/\(markerID)"
        let markerPayload: [String: Any] = [
            "type": "Feature",
            "geometry": [
                "type": "Point",
                "coordinates": [clue.longitude, clue.latitude],
            ],
            "properties": [
                "class": "Marker",
                "updated": Int64(now.timeIntervalSince1970 * 1_000),
                "created": clue.createdMilliseconds,
                "title": clue.title,
                "description": clue.description,
                "marker-color": "#FF0000",
                "marker-symbol": "Drone",
                "marker-size": "1",
                "marker-visibility": "visible",
            ],
        ]
        let mediaPath = "/api/v1/media/\(mediaID)"
        let mediaPayload: [String: Any] = ["properties": ["creator": clue.teamID]]
        let dataPath = mediaPath + "/data"
        let dataPayload: [String: Any] = [
            "creator": clue.teamID,
            "data": clue.jpegData.base64EncodedString(),
        ]
        let linkPath = "/api/v1/map/\(configuration.mapID)/MapMediaObject"
        let linkPayload: [String: Any] = [
            "type": "Feature",
            "geometry": [
                "type": "Point",
                "coordinates": [clue.longitude, clue.latitude],
            ],
            "properties": [
                "title": clue.title,
                "description": clue.description,
                "parentId": "Marker:\(markerID)",
                "backendMediaId": mediaID,
                "heading": NSNull(),
                "class": "MapMediaObject",
                "marker-symbol": "aperture",
                "marker-color": "#FF00FF",
                "marker-size": 1,
                "created": Int64(now.timeIntervalSince1970 * 1_000),
            ],
        ]
        return try [
            makeSignedPostRequest(path: markerPath, object: markerPayload, now: now),
            makeSignedPostRequest(path: mediaPath, object: mediaPayload, now: now),
            makeSignedPostRequest(path: dataPath, object: dataPayload, now: now),
            makeSignedPostRequest(path: linkPath, object: linkPayload, now: now),
        ]
    }

    func makeMapSnapshotRequest(now: Date) throws -> URLRequest {
        let path = "/api/v1/map/\(configuration.mapID)/since/0"
        let expires = Int64(now.timeIntervalSince1970 * 1_000) + 10_000
        let signature = try CaltopoRequestSigner.signature(
            method: "GET",
            path: path,
            expiresMilliseconds: expires,
            payload: "",
            credentialSecretBase64: configuration.credentialSecretBase64
        )
        guard var components = URLComponents(url: httpsURL(path: path)!, resolvingAgainstBaseURL: false)
        else { throw CaltopoLiveClientError.invalidURL }
        components.queryItems = [
            URLQueryItem(name: "id", value: configuration.credentialID),
            URLQueryItem(name: "expires", value: String(expires)),
            URLQueryItem(name: "signature", value: signature),
        ]
        guard let url = components.url else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        return request
    }

    func makeStartLiveTrackRequest(
        remoteID: String,
        label: String,
        folderID: String?,
        now: Date
    ) throws -> URLRequest {
        let path = "/api/v1/map/\(configuration.mapID)/LiveTrack"
        var properties: [String: Any] = [
            "title": label,
            "stroke-width": 2,
            "stroke-opacity": 1,
            "stroke": "#FF0000",
            "pattern": "solid",
            "marker-symbol": "icon-8T781R60-12-0.5-0.5-tf",
            "marker-size": 2,
            "class": "LiveTrack",
            "deviceId": "FLEET:DRONE-\(remoteID)",
        ]
        if let folderID, !folderID.isEmpty {
            properties["folderId"] = folderID
        }
        let payloadData = try JSONSerialization.data(
            withJSONObject: ["type": "Feature", "properties": properties],
            options: [.sortedKeys]
        )
        let payload = String(decoding: payloadData, as: UTF8.self)
        let expires = Int64(now.timeIntervalSince1970 * 1_000) + 10_000
        let signature = try CaltopoRequestSigner.signature(
            method: "POST",
            path: path,
            expiresMilliseconds: expires,
            payload: payload,
            credentialSecretBase64: configuration.credentialSecretBase64
        )
        guard let url = httpsURL(path: path) else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Self.formBody([
            "id": configuration.credentialID,
            "expires": String(expires),
            "signature": signature,
            "json": payload,
        ])
        return request
    }

    private func makeSignedPostRequest(path: String, object: [String: Any], now: Date) throws -> URLRequest {
        let payloadData = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        let payload = String(decoding: payloadData, as: UTF8.self)
        let expires = Int64(now.timeIntervalSince1970 * 1_000) + 10_000
        let signature = try CaltopoRequestSigner.signature(
            method: "POST",
            path: path,
            expiresMilliseconds: expires,
            payload: payload,
            credentialSecretBase64: configuration.credentialSecretBase64
        )
        guard let url = httpsURL(path: path) else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = Self.formBody([
            "id": configuration.credentialID,
            "expires": String(expires),
            "signature": signature,
            "json": payload,
        ])
        return request
    }

    func makePointRequest(remoteID: String, observation: RidObservation) throws -> URLRequest {
        var components = URLComponents()
        components.scheme = "https"
        components.host = hostAndPort.host
        components.port = hostAndPort.port
        components.path = "/api/v1/position/report/DRONE"
        var queryItems = [
            URLQueryItem(name: "id", value: remoteID),
            URLQueryItem(name: "lat", value: String(format: "%.7f", observation.latitude)),
            URLQueryItem(name: "lng", value: String(format: "%.7f", observation.longitude)),
            URLQueryItem(name: "elevation", value: String(Int64(observation.altitudeMeters ?? -1_000))),
        ]
        var aircraft: [String: Double] = [:]
        if let speed = observation.speedMetersPerSecond, speed.isFinite {
            aircraft["gs"] = speed * 1.943_844_49
        }
        if let heading = observation.headingDegrees, heading.isFinite {
            aircraft["track"] = heading
        }
        if !aircraft.isEmpty,
           let data = try? JSONSerialization.data(withJSONObject: aircraft, options: [.sortedKeys]) {
            queryItems.append(URLQueryItem(name: "aircraft", value: String(decoding: data, as: UTF8.self)))
        }
        components.queryItems = queryItems
        guard let url = components.url else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        return request
    }

    func makeStopLiveTrackRequest(liveTrackID: String, now: Date) throws -> URLRequest {
        guard !liveTrackID.isEmpty else { throw CaltopoLiveClientError.missingLiveTrackID }
        let path = "/api/v1/map/\(configuration.mapID)/LiveTrack/\(liveTrackID)"
        let expires = Int64(now.timeIntervalSince1970 * 1_000) + 10_000
        let signature = try CaltopoRequestSigner.signature(
            method: "DELETE",
            path: path,
            expiresMilliseconds: expires,
            payload: "",
            credentialSecretBase64: configuration.credentialSecretBase64
        )
        guard var components = URLComponents(url: httpsURL(path: path)!, resolvingAgainstBaseURL: false)
        else { throw CaltopoLiveClientError.invalidURL }
        components.queryItems = [
            URLQueryItem(name: "id", value: configuration.credentialID),
            URLQueryItem(name: "expires", value: String(expires)),
            URLQueryItem(name: "signature", value: signature),
        ]
        guard let url = components.url else { throw CaltopoLiveClientError.invalidURL }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue("RID2Caltopo/Apple", forHTTPHeaderField: "User-Agent")
        return request
    }

    private func perform(
        _ request: URLRequest,
        acceptedStatusCodes: Set<Int> = []
    ) async throws -> Data {
        let (data, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw CaltopoLiveClientError.httpStatus(0, "Non-HTTP response")
        }
        guard (200 ..< 300).contains(httpResponse.statusCode)
                || acceptedStatusCodes.contains(httpResponse.statusCode)
        else {
            throw CaltopoLiveClientError.httpStatus(
                httpResponse.statusCode,
                String(decoding: data.prefix(1_024), as: UTF8.self)
            )
        }
        return data
    }

    private func httpsURL(path: String) -> URL? {
        var components = URLComponents()
        components.scheme = "https"
        components.host = hostAndPort.host
        components.port = hostAndPort.port
        components.path = path
        return components.url
    }

    private var hostAndPort: (host: String, port: Int?) {
        let parts = configuration.domainAndPort.split(separator: ":", maxSplits: 1)
        return (String(parts[0]), parts.count == 2 ? Int(parts[1]) : nil)
    }

    private static func formBody(_ fields: [String: String]) -> Data {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._*"))
        let body = fields.keys.sorted().map { key in
            let value = fields[key] ?? ""
            let encodedKey = key.addingPercentEncoding(withAllowedCharacters: allowed) ?? key
            let encodedValue = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
            return "\(encodedKey)=\(encodedValue)"
        }.joined(separator: "&")
        return Data(body.utf8)
    }
}
