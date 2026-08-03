import Foundation

public enum ManagedVideoLinkCapacity: String, Sendable {
    case enough
    case marginal
    case fallback
    case insufficient
}

public struct ManagedVideoQualityOption: Equatable, Sendable, Identifiable {
    public let preset: String
    public let width: Int
    public let height: Int
    public let fps: Double
    public let estimatedBitrateBps: Int64
    public let capacity: ManagedVideoLinkCapacity

    public var id: String {
        "\(preset.lowercased())-\(width)x\(height)-\(Int((fps * 10).rounded()))-\(estimatedBitrateBps)"
    }
}

public enum ManagedVideoQualityPolicy {
    private struct Preset {
        let name: String
        let maximumLongEdge: Int
        let fps: Double
        let bitrateBps: Int64
    }

    // Each entry is a complete preset. In particular, High is always the
    // 720p30/3 Mbps target when the source can supply that resolution.
    private static let presets = [
        Preset(name: "High", maximumLongEdge: 1280, fps: 30, bitrateBps: 3_000_000),
        Preset(name: "Balanced", maximumLongEdge: 960, fps: 15, bitrateBps: 1_200_000),
        Preset(name: "Low", maximumLongEdge: 640, fps: 10, bitrateBps: 500_000),
        Preset(name: "Emergency", maximumLongEdge: 640, fps: 5, bitrateBps: 200_000),
    ]

    public static func options(
        sourceWidth: Int,
        sourceHeight: Int,
        sourceFps: Double,
        sourceBitrateBps: Int64,
        usableUplinkBps: Int64
    ) -> [ManagedVideoQualityOption] {
        let width = max(2, sourceWidth > 0 ? sourceWidth : 1920)
        let height = max(2, sourceHeight > 0 ? sourceHeight : 1080)
        let observedFps = sourceFps > 0 ? sourceFps : 30
        // Controller RTMP cadence is bursty and often under-reports a nominal
        // 30 fps source. Treat observations of at least 15 fps as a 30 fps
        // source option; the sender still reports and caps its actual output.
        let fps = observedFps >= 15 ? 30.0 : observedFps
        let sourceLongEdge = max(width, height)
        let options = presets.map { preset in
                let targetLongEdge = min(sourceLongEdge, preset.maximumLongEdge)
                let scale = Double(targetLongEdge) / Double(sourceLongEdge)
                let targetSize = evenSize(
                    width: Int((Double(width) * scale).rounded(.down)),
                    height: Int((Double(height) * scale).rounded(.down))
                )
                let targetFps = min(fps, preset.fps)
                let referencePixels = Double(preset.maximumLongEdge * preset.maximumLongEdge) *
                    Double(min(width, height)) / Double(sourceLongEdge)
                let actualPixels = Double(targetSize.0 * targetSize.1)
                let pixelScale = min(1, actualPixels / max(1, referencePixels))
                let rateScale = min(1, targetFps / preset.fps)
                let minimumBitrate: Int64 = preset.name == "Emergency" ? 100_000 : 150_000
                let boundedBitrate = min(
                    preset.bitrateBps,
                    max(minimumBitrate, Int64((Double(preset.bitrateBps) * pixelScale * rateScale).rounded()))
                )
                let capacity: ManagedVideoLinkCapacity
                if usableUplinkBps > 0 && usableUplinkBps * 100 >= boundedBitrate * 135 {
                    capacity = .enough
                } else if usableUplinkBps >= boundedBitrate {
                    capacity = .marginal
                } else {
                    capacity = .insufficient
                }
                return ManagedVideoQualityOption(
                    preset: preset.name,
                    width: targetSize.0,
                    height: targetSize.1,
                    fps: targetFps,
                    estimatedBitrateBps: boundedBitrate,
                    capacity: capacity
                )
        }
        guard options.allSatisfy({ $0.capacity == .insufficient }) else {
            return options
        }
        guard let fallbackIndex = options.indices.min(by: { left, right in
            let lhs = options[left]
            let rhs = options[right]
            return (lhs.estimatedBitrateBps, max(lhs.width, lhs.height), lhs.fps)
                < (rhs.estimatedBitrateBps, max(rhs.width, rhs.height), rhs.fps)
        }) else {
            return options
        }
        var adjusted = options
        let fallback = options[fallbackIndex]
        adjusted[fallbackIndex] = ManagedVideoQualityOption(
            preset: fallback.preset,
            width: fallback.width,
            height: fallback.height,
            fps: fallback.fps,
            estimatedBitrateBps: fallback.estimatedBitrateBps,
            capacity: .fallback
        )
        return adjusted
    }

    private static func evenSize(width: Int, height: Int) -> (Int, Int) {
        (max(2, width - width % 2), max(2, height - height % 2))
    }
}
