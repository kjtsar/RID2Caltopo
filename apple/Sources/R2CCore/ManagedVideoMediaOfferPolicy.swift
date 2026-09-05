public enum ManagedVideoMediaOfferDecision: Equatable, Sendable {
    case start
    case ignoreReplay
    case replaceActivePeer
}

public enum ManagedVideoMediaOfferPolicy {
    public static func decision(
        activeOfferSDP: String?,
        incomingOfferSDP: String
    ) -> ManagedVideoMediaOfferDecision {
        guard let activeOfferSDP else { return .start }
        return activeOfferSDP == incomingOfferSDP
            ? .ignoreReplay
            : .replaceActivePeer
    }
}
