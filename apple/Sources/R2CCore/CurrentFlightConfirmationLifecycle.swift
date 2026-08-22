import Foundation

public struct CurrentFlightConfirmationReconciliation: Sendable, Equatable {
    public let endedRemoteIDs: Set<String>
    public let candidateRemoteID: String?
}

/// Tracks prompt presentation only for the currently active flight. The caller supplies
/// confirmed/ignored decisions so they can be discarded when a definitive flight end occurs.
public struct CurrentFlightConfirmationLifecycle: Sendable, Equatable {
    private var activeRemoteIDs: Set<String> = []
    private var promptedRemoteIDs: Set<String> = []

    public init() {}

    public mutating func reconcile(
        orderedRemoteIDs: [String],
        confirmedRemoteIDs: Set<String>,
        ignoredRemoteIDs: Set<String>
    ) -> CurrentFlightConfirmationReconciliation {
        let currentRemoteIDs = Set(orderedRemoteIDs.filter { !$0.isEmpty })
        let endedRemoteIDs = activeRemoteIDs.subtracting(currentRemoteIDs)
        promptedRemoteIDs.subtract(endedRemoteIDs)
        activeRemoteIDs = currentRemoteIDs

        let decisionsAfterFlightEnd = confirmedRemoteIDs
            .union(ignoredRemoteIDs)
            .subtracting(endedRemoteIDs)
        let candidate = orderedRemoteIDs.first { remoteID in
            !remoteID.isEmpty
                && !promptedRemoteIDs.contains(remoteID)
                && !decisionsAfterFlightEnd.contains(remoteID)
        }
        if let candidate {
            promptedRemoteIDs.insert(candidate)
        }
        return CurrentFlightConfirmationReconciliation(
            endedRemoteIDs: endedRemoteIDs,
            candidateRemoteID: candidate
        )
    }

    public mutating func reset() {
        activeRemoteIDs.removeAll()
        promptedRemoteIDs.removeAll()
    }
}
