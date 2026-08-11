import CryptoKit
import Foundation

public enum TrackerTabletLink {
    private static let codeDigestBytes = 4

    public static func organizationDesignator(from trackerURLPrefix: String) -> String? {
        let trimmed = trackerURLPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed) else { return nil }
        return components.path
            .split(separator: "/")
            .last
            .map { String($0).lowercased() }
    }

    public static func shortURL(
        trackerURLPrefix: String,
        tabletName: String
    ) -> URL? {
        let trimmedPrefix = trackerURLPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let organization = organizationDesignator(from: trimmedPrefix),
              !normalize(tabletName).isEmpty,
              var components = URLComponents(string: trimmedPrefix),
              components.scheme != nil,
              components.host != nil
        else { return nil }
        components.path = "/t/" + code(
            organization: organization,
            tabletName: tabletName
        )
        components.query = nil
        components.fragment = nil
        return components.url
    }

    public static func markerDescription(
        trackerURLPrefix: String,
        tabletName: String
    ) -> String {
        guard let url = shortURL(
            trackerURLPrefix: trackerURLPrefix,
            tabletName: tabletName
        ) else { return "" }
        return "R2C tablet: \(url.absoluteString)"
    }

    public static func streamShortURL(
        trackerURLPrefix: String,
        tabletName: String,
        videoStream: String
    ) -> URL? {
        let trimmedPrefix = trackerURLPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let organization = organizationDesignator(from: trimmedPrefix),
              !normalize(tabletName).isEmpty,
              !normalize(videoStream).isEmpty,
              var components = URLComponents(string: trimmedPrefix),
              components.scheme != nil,
              components.host != nil
        else { return nil }
        components.path = "/s/" + streamCode(
            organization: organization,
            tabletName: tabletName,
            videoStream: videoStream
        )
        components.query = nil
        components.fragment = nil
        return components.url
    }

    public static func thumbnailURL(
        trackerURLPrefix: String,
        tabletName: String,
        streamSessionID: String
    ) -> URL? {
        let trimmedPrefix = trackerURLPrefix.trimmingCharacters(in: .whitespacesAndNewlines)
        let sessionID = streamSessionID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let organization = organizationDesignator(from: trimmedPrefix),
              !normalize(tabletName).isEmpty,
              !sessionID.isEmpty,
              var components = URLComponents(string: trimmedPrefix),
              components.scheme != nil,
              components.host != nil
        else { return nil }
        let tabletCode = code(organization: organization, tabletName: tabletName)
        components.path = "/r2c-thumbnail/\(tabletCode)/\(sessionID).jpg"
        components.query = nil
        components.fragment = nil
        return components.url
    }

    public static func code(
        organization: String,
        tabletName: String
    ) -> String {
        let material = "/\(normalize(organization))/streams/\(normalize(tabletName))"
        let digest = SHA256.hash(data: Data(material.utf8))
        return Data(digest.prefix(codeDigestBytes))
            .base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func streamCode(
        organization: String,
        tabletName: String,
        videoStream: String
    ) -> String {
        let material = "/\(normalize(organization))/streams/\(normalize(tabletName))/\(normalize(videoStream))"
        let digest = SHA256.hash(data: Data(material.utf8))
        return Data(digest.prefix(codeDigestBytes))
            .base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func normalize(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}
