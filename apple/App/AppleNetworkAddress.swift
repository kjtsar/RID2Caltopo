import Darwin
import Foundation
import NetworkExtension
import R2CCore
import UIKit

enum AppleNetworkAddress {
    static func preferredIPv4Address() -> String? {
        let candidates = ipv4Candidates()
        return candidates.first(where: { $0.name == "en0" })?.address
            ?? candidates.first(where: { $0.name.hasPrefix("en") })?.address
    }

    static func ipv4DiagnosticSummary() -> String {
        let candidates = ipv4Candidates()
        guard !candidates.isEmpty else { return "none" }
        return candidates.map { "\($0.name)=\($0.address)" }.joined(separator: ",")
    }

    private static func ipv4Candidates() -> [(name: String, address: String)] {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let first = interfaces else { return [] }
        defer { freeifaddrs(interfaces) }

        var candidates: [(name: String, address: String)] = []
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let interface = cursor?.pointee {
            defer { cursor = interface.ifa_next }
            guard let socketAddress = interface.ifa_addr,
                  socketAddress.pointee.sa_family == UInt8(AF_INET),
                  interface.ifa_flags & UInt32(IFF_UP) != 0,
                  interface.ifa_flags & UInt32(IFF_LOOPBACK) == 0
            else { continue }

            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let result = getnameinfo(
                socketAddress,
                socklen_t(socketAddress.pointee.sa_len),
                &host,
                socklen_t(host.count),
                nil,
                0,
                NI_NUMERICHOST
            )
            guard result == 0 else { continue }
            let address = String(
                decoding: host.lazy.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) },
                as: UTF8.self
            )
            candidates.append((
                name: String(cString: interface.ifa_name),
                address: address
            ))
        }
        return candidates
    }

    static func currentWiFiSSID() async -> String? {
        await withCheckedContinuation { continuation in
            NEHotspotNetwork.fetchCurrent { network in
                let ssid = network?.ssid.trimmingCharacters(in: .whitespacesAndNewlines)
                continuation.resume(returning: ssid?.isEmpty == false ? ssid : nil)
            }
        }
    }
}

enum AppleDeviceIdentity {
    static let storedNameKey = "device.stableDisplayName"

    static var displayName: String {
        let defaults = UserDefaults.standard
        let stored = defaults.string(forKey: storedNameKey)
        let userAssignedName = MainActor.assumeIsolated { UIDevice.current.name }
        let resolved = OperationalDeviceName.preferredDisplayName(
            stored: stored,
            userAssigned: userAssignedName,
            hostname: ProcessInfo.processInfo.hostName
        )
        if stored?.trimmingCharacters(in: .whitespacesAndNewlines) != resolved {
            defaults.set(resolved, forKey: storedNameKey)
        }
        return resolved
    }

    static func displayName(fromHostname hostname: String) -> String {
        OperationalDeviceName.displayName(fromHostname: hostname) ?? "iPad"
    }
}
