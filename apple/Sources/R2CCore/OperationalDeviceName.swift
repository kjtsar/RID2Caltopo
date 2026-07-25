import Foundation

public enum OperationalDeviceName {
    public static func preferredDisplayName(
        stored: String?,
        userAssigned: String?,
        hostname: String,
        fallback: String = "iPad"
    ) -> String {
        let storedName = normalized(stored)
        if let storedName, !isReplaceableFallback(storedName) {
            return storedName
        }
        if let assignedName = normalizedUserAssignedName(userAssigned) {
            return assignedName
        }
        if let hostnameName = displayName(fromHostname: hostname) {
            return hostnameName
        }
        if let storedName, !looksLikeInternetHostname(storedName) {
            return canonicalDeviceWords(in: storedName)
        }
        return fallback
    }

    public static func displayName(fromHostname hostname: String) -> String? {
        var stem = hostname.trimmingCharacters(in: .whitespacesAndNewlines)
        let lowerHostname = stem.lowercased()
        let isLocal = lowerHostname.hasSuffix(".local") || lowerHostname.hasSuffix(".coredevice.local")
        let isDeviceLikeBareName = !stem.contains(".")
            && ["ipad", "iphone", "ipod"].contains(where: lowerHostname.contains)
        guard isLocal || isDeviceLikeBareName else { return nil }
        for suffix in [".coredevice.local", ".local"] where stem.lowercased().hasSuffix(suffix) {
            stem.removeLast(suffix.count)
            break
        }
        guard !stem.isEmpty,
              stem.lowercased() != "localhost",
              !looksLikeOpaqueIdentifier(stem)
        else { return nil }
        let formatted = canonicalDeviceWords(in: stem)
        return isGenericDeviceName(formatted) && !stem.lowercased().contains("ipad")
            ? nil
            : formatted
    }

    private static func normalizedUserAssignedName(_ value: String?) -> String? {
        guard let name = normalized(value), !isGenericDeviceName(name) else { return nil }
        return canonicalDeviceWords(in: name)
    }

    private static func normalized(_ value: String?) -> String? {
        guard let value else { return nil }
        let name = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? nil : name
    }

    private static func isReplaceableFallback(_ value: String) -> Bool {
        isGenericDeviceName(value) || looksLikeInternetHostname(value)
    }

    private static func isGenericDeviceName(_ value: String) -> Bool {
        ["ipad", "iphone", "ipod", "apple device"].contains(value.lowercased())
    }

    private static func looksLikeInternetHostname(_ value: String) -> Bool {
        let lower = value.lowercased()
        return value.contains(".")
            && !lower.hasSuffix(".local")
            && !lower.hasSuffix(".coredevice.local")
    }

    private static func looksLikeOpaqueIdentifier(_ value: String) -> Bool {
        let compact = value.replacingOccurrences(of: "-", with: "")
        return compact.count >= 20 && compact.allSatisfy { $0.isHexDigit }
    }

    private static func canonicalDeviceWords(in value: String) -> String {
        let separators = CharacterSet(charactersIn: "-_ ").union(.whitespacesAndNewlines)
        let sourceWords = value.components(separatedBy: separators).filter { !$0.isEmpty }
        guard !sourceWords.isEmpty else { return "iPad" }
        let deviceWords = Set(["ipad", "iphone", "ipod"])
        return sourceWords.enumerated().map { index, source in
            let lower = source.lowercased()
            switch lower {
            case "ipad": return "iPad"
            case "iphone": return "iPhone"
            case "ipod": return "iPod"
            case "pro": return "Pro"
            case "air": return "Air"
            case "mini": return "mini"
            default:
                if index == 0,
                   lower.hasSuffix("'s") || lower.hasSuffix("’s") {
                    return String(source.dropLast(2)) + "'s"
                }
                if index == 0,
                   source.count > 1,
                   lower.hasSuffix("s"),
                   sourceWords.dropFirst().contains(where: { deviceWords.contains($0.lowercased()) }) {
                    return String(source.dropLast()) + "'s"
                }
                return source
            }
        }.joined(separator: " ")
    }
}
