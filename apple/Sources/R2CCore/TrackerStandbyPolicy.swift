public enum TrackerStandbyPolicy {
    public static func normalizedDelaySeconds(_ advertisedSeconds: Int?) -> Int {
        max(5, min(advertisedSeconds ?? 30, 3_600))
    }

    public static func isEligible(
        standalone: Bool,
        connected: Bool,
        helloAcknowledged: Bool,
        configurationSyncInProgress: Bool,
        activeSightings: Int,
        pendingConfirmations: Int,
        hasLiveVideo: Bool,
        activeMediaConnections: Int
    ) -> Bool {
        standalone
            && connected
            && helloAcknowledged
            && !configurationSyncInProgress
            && activeSightings == 0
            && pendingConfirmations == 0
            && !hasLiveVideo
            && activeMediaConnections == 0
    }
}
