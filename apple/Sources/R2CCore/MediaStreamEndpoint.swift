import Foundation

/// Network contract between a drone controller, embedded MediaMTX, and the decoder.
public struct MediaStreamEndpoint: Sendable, Equatable {
    public static let defaultRtmpPort = 1935
    public static let defaultRtspPort = 8554
    public static let defaultHlsPort = 8888

    public let designator: String
    public let rtmpPort: Int
    public let rtspPort: Int
    public let hlsPort: Int

    public init(
        designator: String,
        rtmpPort: Int = Self.defaultRtmpPort,
        rtspPort: Int = Self.defaultRtspPort,
        hlsPort: Int = Self.defaultHlsPort
    ) {
        self.designator = designator
        self.rtmpPort = rtmpPort
        self.rtspPort = rtspPort
        self.hlsPort = hlsPort
    }

    /// Local MediaMTX RTSP endpoint consumed by the Apple decode/anomaly pipeline.
    public var loopbackRtspURL: URL? {
        var components = URLComponents()
        components.scheme = "rtsp"
        components.host = "127.0.0.1"
        components.port = rtspPort
        components.path = "/\(designator)"
        return components.url
    }

    /// Low-Latency HLS endpoint decoded by AVFoundation on Apple platforms.
    public var loopbackHlsURL: URL? {
        var components = URLComponents()
        components.scheme = "http"
        components.host = "127.0.0.1"
        components.port = hlsPort
        components.path = "/\(designator)/index.m3u8"
        return components.url
    }
}
