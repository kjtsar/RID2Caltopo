import Foundation

public enum OrganizationAccessPolicy {
    public static let backgroundAuthenticationGraceInterval: TimeInterval = 15

    public static func requiresDeviceOwnerAuthentication(
        organizationName: String,
        trackerURLPrefix: String = "",
        trackerAPIKey: String = "",
        caltopoTeamID: String = "",
        caltopoCredentialID: String = "",
        caltopoCredentialSecret: String = ""
    ) -> Bool {
        let organizationConfigured = !organizationName
            .trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let trackerOrganizationConfigured = [trackerURLPrefix, trackerAPIKey]
            .allSatisfy {
                !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
        let teamsAccountConfigured = [
            caltopoTeamID,
            caltopoCredentialID,
            caltopoCredentialSecret,
        ].allSatisfy {
            !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        return organizationConfigured ||
            trackerOrganizationConfigured ||
            teamsAccountConfigured
    }

    public static func authenticatedSessionRemainsValid(
        accessWasGranted: Bool,
        backgroundedAt: Date?,
        resumedAt: Date
    ) -> Bool {
        guard accessWasGranted else { return false }
        guard let backgroundedAt else { return true }
        return max(0, resumedAt.timeIntervalSince(backgroundedAt)) <
            backgroundAuthenticationGraceInterval
    }
}
