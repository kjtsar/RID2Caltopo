import Foundation

public enum OrganizationAccessPolicy {
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
}
