import Foundation

public enum ManagedVideoPresencePolicy {
    public static let maximumDecodedFrameAge: TimeInterval = 6

    public static func hasRecentDecodedFrame(
        frameCount: Int,
        decodedFrameAge: TimeInterval?,
        maximumAge: TimeInterval = maximumDecodedFrameAge
    ) -> Bool {
        guard frameCount > 0, let decodedFrameAge else { return false }
        return decodedFrameAge >= 0 && decodedFrameAge <= maximumAge
    }
}
