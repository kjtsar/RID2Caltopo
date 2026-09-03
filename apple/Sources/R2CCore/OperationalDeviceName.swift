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

public enum OperationalDeviceModelName {
    public static func apple(machineIdentifier: String, fallback: String = "iPad") -> String {
        let identifier = machineIdentifier.trimmingCharacters(in: .whitespacesAndNewlines)
        if identifier.lowercased().hasPrefix("iphone") { return "iPhone" }
        if identifier.lowercased().hasPrefix("ipod") { return "iPod" }
        guard identifier.lowercased().hasPrefix("ipad") else { return fallback }
        let mini = Set([
            "iPad2,5", "iPad2,6", "iPad2,7", "iPad4,4", "iPad4,5", "iPad4,6",
            "iPad5,1", "iPad5,2", "iPad11,1", "iPad11,2", "iPad14,1", "iPad14,2",
            "iPad16,1", "iPad16,2",
        ])
        if mini.contains(identifier) { return "iPad mini" }
        let air = Set([
            "iPad4,1", "iPad4,2", "iPad4,3", "iPad5,3", "iPad5,4", "iPad11,3",
            "iPad11,4", "iPad13,1", "iPad13,2", "iPad13,16", "iPad13,17",
            "iPad14,8", "iPad14,9", "iPad14,10", "iPad14,11", "iPad15,3",
            "iPad15,4", "iPad15,5", "iPad15,6",
        ])
        if air.contains(identifier) { return "iPad Air" }
        let pro = Set([
            "iPad6,3", "iPad6,4", "iPad6,7", "iPad6,8", "iPad7,1", "iPad7,2",
            "iPad7,3", "iPad7,4", "iPad8,1", "iPad8,2", "iPad8,3", "iPad8,4",
            "iPad8,5", "iPad8,6", "iPad8,7", "iPad8,8", "iPad8,9", "iPad8,10",
            "iPad8,11", "iPad8,12", "iPad13,4", "iPad13,5", "iPad13,6", "iPad13,7",
            "iPad13,8", "iPad13,9", "iPad13,10", "iPad13,11", "iPad14,3", "iPad14,4",
            "iPad14,5", "iPad14,6", "iPad16,3", "iPad16,4", "iPad16,5", "iPad16,6",
        ])
        return pro.contains(identifier) ? "iPad Pro" : "iPad"
    }
}
