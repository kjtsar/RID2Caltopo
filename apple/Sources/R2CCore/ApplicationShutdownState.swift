public enum ApplicationShutdownPhase: Equatable, Sendable {
    case running
    case cleaning
    case cleaned
}

public struct ApplicationShutdownRequest: Equatable, Sendable {
    public let shouldStartCleanup: Bool
    public let shouldDismissWindow: Bool
}

public struct ApplicationShutdownState: Equatable, Sendable {
    public private(set) var phase: ApplicationShutdownPhase = .running
    private var dismissWindowAfterCleanup = false

    public init() {}

    public var isShutdownRequested: Bool {
        phase != .running
    }

    public mutating func request(dismissWindow: Bool) -> ApplicationShutdownRequest {
        dismissWindowAfterCleanup = dismissWindowAfterCleanup || dismissWindow
        switch phase {
        case .running:
            phase = .cleaning
            return ApplicationShutdownRequest(
                shouldStartCleanup: true,
                shouldDismissWindow: false
            )
        case .cleaning:
            return ApplicationShutdownRequest(
                shouldStartCleanup: false,
                shouldDismissWindow: false
            )
        case .cleaned:
            return ApplicationShutdownRequest(
                shouldStartCleanup: false,
                shouldDismissWindow: dismissWindow
            )
        }
    }

    public mutating func cleanupCompleted() -> Bool {
        phase = .cleaned
        return dismissWindowAfterCleanup
    }

    public mutating func reset() {
        self = ApplicationShutdownState()
    }
}
