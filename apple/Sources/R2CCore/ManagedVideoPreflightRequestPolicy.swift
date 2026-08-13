public enum ManagedVideoPreflightRequestPolicy {
    public static func shouldAcceptOffer(
        requestID: String,
        pendingOperatorRequestID: String?,
        remoteControlledRequestIDs: Set<String>
    ) -> Bool {
        pendingOperatorRequestID == requestID || remoteControlledRequestIDs.contains(requestID)
    }
}
