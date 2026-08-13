import Foundation

public struct ManagedVideoIncidentScopeResolution: Equatable, Sendable {
    public let startedAt: Date
    public let startsByScope: [String: TimeInterval]
    public let migrationCompleted: Bool

    public init(
        startedAt: Date,
        startsByScope: [String: TimeInterval],
        migrationCompleted: Bool
    ) {
        self.startedAt = startedAt
        self.startsByScope = startsByScope
        self.migrationCompleted = migrationCompleted
    }
}

public enum ManagedVideoIncidentScopePolicy {
    public static func resolve(
        scopeKey: String,
        startsByScope: [String: TimeInterval],
        migrationCompleted: Bool,
        now: Date,
        calendar: Calendar = .current
    ) -> ManagedVideoIncidentScopeResolution {
        var updatedStarts = startsByScope
        let isMapScope = scopeKey.hasPrefix("map:")
        let hasLegacyIncidentScope = startsByScope.keys.contains {
            $0.hasPrefix("incident:")
        }

        if isMapScope && !migrationCompleted && hasLegacyIncidentScope {
            let startedAt = calendar.startOfDay(for: now)
            updatedStarts[scopeKey] = startedAt.timeIntervalSince1970
            return ManagedVideoIncidentScopeResolution(
                startedAt: startedAt,
                startsByScope: updatedStarts,
                migrationCompleted: true
            )
        }

        if let storedStart = startsByScope[scopeKey] {
            return ManagedVideoIncidentScopeResolution(
                startedAt: Date(timeIntervalSince1970: storedStart),
                startsByScope: startsByScope,
                migrationCompleted: migrationCompleted || isMapScope
            )
        }

        let startedAt = startsByScope.isEmpty
            ? calendar.startOfDay(for: now)
            : now
        updatedStarts[scopeKey] = startedAt.timeIntervalSince1970
        return ManagedVideoIncidentScopeResolution(
            startedAt: startedAt,
            startsByScope: updatedStarts,
            migrationCompleted: migrationCompleted || isMapScope
        )
    }
}
