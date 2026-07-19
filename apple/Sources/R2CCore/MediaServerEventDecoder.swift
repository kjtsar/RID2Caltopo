import Foundation

public enum MediaServerEventDecoder {
    private struct WireEvent: Decodable {
        let type: String
        let path: String?
        let publisherConnId: String?
        let reason: String?
        let version: String?
        let phase: String?
        let elapsedMs: Int?
        let detail: String?
    }

    public static func decode(json: String) throws -> MediaServerEvent? {
        let wire = try JSONDecoder().decode(WireEvent.self, from: Data(json.utf8))
        let connectionID = wire.publisherConnId?.nilIfEmpty

        switch wire.type {
        case "stream_connecting":
            return wire.path.map(MediaServerEvent.streamConnecting)
        case "stream_started":
            return wire.path.map { .streamStarted(path: $0, publisherConnectionID: connectionID) }
        case "stream_publisher_handoff":
            return wire.path.map { .streamPublisherHandoff(path: $0, publisherConnectionID: connectionID) }
        case "stream_stopped":
            return wire.path.map { .streamStopped(path: $0, publisherConnectionID: connectionID) }
        case "stream_error":
            guard let detail = wire.reason?.nilIfEmpty ?? wire.detail?.nilIfEmpty else { return nil }
            return .streamError(path: wire.path, publisherConnectionID: connectionID, detail: detail)
        case "rtmp_session_closed":
            return wire.path.map {
                .rtmpSessionClosed(
                    path: $0,
                    publisherConnectionID: connectionID,
                    reason: wire.reason?.nilIfEmpty
                )
            }
        case "rtmp_publish_diagnostic":
            guard let path = wire.path, let phase = wire.phase else { return nil }
            return .rtmpPublishDiagnostic(
                path: path,
                publisherConnectionID: connectionID,
                phase: phase,
                elapsedMilliseconds: wire.elapsedMs,
                detail: wire.detail?.nilIfEmpty
            )
        case "server_started":
            return wire.version.map(MediaServerEvent.serverStarted)
        case "hls_started":
            return wire.path.map(MediaServerEvent.hlsStreamStarted)
        default:
            return nil
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
