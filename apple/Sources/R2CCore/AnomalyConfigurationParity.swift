import Foundation

public struct AnomalyGuideGeometry: Sendable, Equatable {
    public let scanWidth: Double
    public let scanHeight: Double
    public let targetSpan: Double

    public init(scanWidth: Double, scanHeight: Double, targetSpan: Double) {
        self.scanWidth = scanWidth
        self.scanHeight = scanHeight
        self.targetSpan = targetSpan
    }
}

public struct AnomalyDisplayRect: Sendable, Equatable {
    public let x: Double
    public let y: Double
    public let width: Double
    public let height: Double

    public init(x: Double, y: Double, width: Double, height: Double) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
    }
}

/// Shared scalar mappings used by Android's `AnomalyConfig.toNativeConfig`.
/// Keeping them in R2CCore makes Apple detector configuration independently
/// testable without linking the native anomaly framework into SwiftPM tests.
public enum AnomalyConfigurationParity {
    public static let defaultSensitivity = 0.59
    public static let defaultMotionEvidenceSensitivity = 0.60
    public static let defaultMinimumAreaFraction = 0.0015
    public static let colorAdaptiveMinimumFrames = 30
    public static let colorAdaptiveMaximumFrames = 60
    public static let colorAdaptiveMaximumSeconds = 2.0

    public static func scoreThreshold(sensitivity: Double) -> Float {
        let clamped = min(max(sensitivity, 0), 1)
        return Float(min(max(pow(15.0, 1.0 - clamped), 1.0), 15.0))
    }

    public static func motionEvidenceScale(sensitivity: Double) -> Float {
        let clamped = min(max(sensitivity, 0), 1)
        let scale = clamped <= 0.60
            ? 0.25 + (clamped * 1.25)
            : 1.0 + ((clamped - 0.60) * 2.5)
        return Float(min(max(scale, 0.25), 2.0))
    }

    public static func minimumAreaFraction(base: Double, sensitivity: Double) -> Float {
        let clamped = min(max(sensitivity, 0), 1)
        let areaScale = 0.10 + (4.90 * clamped * clamped)
        return Float(min(max(base * areaScale, 0.00005), 0.03))
    }

    public static func usesColorRealtimeCadence(
        isColorMode: Bool,
        strideMode: Int,
        frameStride: Int,
        adaptiveMinimumFrames: Int,
        adaptiveMaximumSeconds: Double
    ) -> Bool {
        guard isColorMode else { return false }
        let baseDefaults = strideMode == 0 &&
            frameStride == 1 &&
            adaptiveMinimumFrames == 2 &&
            abs(adaptiveMaximumSeconds - 1.0) < 0.001
        let colorDefaults = strideMode == 1 &&
            frameStride == colorAdaptiveMinimumFrames &&
            adaptiveMinimumFrames == colorAdaptiveMinimumFrames &&
            abs(adaptiveMaximumSeconds - colorAdaptiveMaximumSeconds) < 0.001
        return baseDefaults || colorDefaults
    }

    public static func colorFrontendMode(isColorMode: Bool, configuredMode: Int) -> Int {
        isColorMode && configuredMode == 0 ? 1 : configuredMode
    }

    public static func pixelStep(isColorMode: Bool, configuredStep: Int) -> Int {
        isColorMode && configuredStep <= 0 ? 1 : min(max(configuredStep, 0), 8)
    }

    /// Matches Android `scanZoneSize()` and `effectiveSmallTargetSpanPx()`.
    public static func guideGeometry(
        frameWidth: Double,
        frameHeight: Double,
        scanZone: Double,
        smallTargetScreenFraction: Double,
        maximumTargetFraction: Double = 0.35
    ) -> AnomalyGuideGeometry {
        let width = max(1, frameWidth)
        let height = max(1, frameHeight)
        let zone = min(max(scanZone, 0.5), 1)
        let targetFraction = min(max(smallTargetScreenFraction, 0.0015), 0.03)
        let targetSpan = min(
            max(hypot(width, height) * targetFraction, 2),
            min(width, height) * max(0, maximumTargetFraction)
        )
        return AnomalyGuideGeometry(
            scanWidth: width * zone,
            scanHeight: height * zone,
            targetSpan: targetSpan
        )
    }

    /// Returns the same centered aspect-fit rectangle used by the live video
    /// layer. Detector coordinates and guide geometry must be laid out inside
    /// this rectangle, not inside the surrounding stream-grid tile.
    public static func aspectFitRect(
        containerWidth: Double,
        containerHeight: Double,
        contentAspectRatio: Double
    ) -> AnomalyDisplayRect {
        let containerWidth = max(0, containerWidth)
        let containerHeight = max(0, containerHeight)
        guard containerWidth > 0, containerHeight > 0 else {
            return AnomalyDisplayRect(x: 0, y: 0, width: 0, height: 0)
        }
        let aspectRatio = contentAspectRatio.isFinite && contentAspectRatio > 0
            ? contentAspectRatio
            : 16.0 / 9.0
        let containerAspectRatio = containerWidth / containerHeight
        let width: Double
        let height: Double
        if containerAspectRatio > aspectRatio {
            height = containerHeight
            width = height * aspectRatio
        } else {
            width = containerWidth
            height = width / aspectRatio
        }
        return AnomalyDisplayRect(
            x: (containerWidth - width) / 2,
            y: (containerHeight - height) / 2,
            width: width,
            height: height
        )
    }
}
