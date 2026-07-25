import Foundation

/// Platform-neutral geometry for saving the operator's current zoomed video
/// viewport into a clue snapshot. Positive vertical pan follows screen
/// coordinates, while Core Image's vertical axis points in the other direction.
public struct OperationalVideoViewport: Sendable, Equatable {
    public let scale: Double
    public let normalizedPanX: Double
    public let normalizedPanY: Double

    public init(scale: Double, normalizedPanX: Double, normalizedPanY: Double) {
        self.scale = min(4, max(1, scale.isFinite ? scale : 1))
        self.normalizedPanX = min(1.5, max(-1.5, normalizedPanX.isFinite ? normalizedPanX : 0))
        self.normalizedPanY = min(1.5, max(-1.5, normalizedPanY.isFinite ? normalizedPanY : 0))
    }

    public var needsTransform: Bool {
        scale > 1.001 || abs(normalizedPanX) > 0.0001 || abs(normalizedPanY) > 0.0001
    }

    public func translationX(width: Double) -> Double {
        let center = width / 2
        return center + normalizedPanX * width - scale * center
    }

    public func translationY(height: Double) -> Double {
        let center = height / 2
        return center - normalizedPanY * height - scale * center
    }
}
