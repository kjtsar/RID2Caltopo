import Darwin
import Foundation

enum AppleNetworkAddress {
    static func preferredIPv4Address() -> String? {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let first = interfaces else { return nil }
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
        return candidates.first(where: { $0.name == "en0" })?.address
            ?? candidates.first?.address
    }
}
