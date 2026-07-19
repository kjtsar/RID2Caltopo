#if os(iOS) && canImport(WiFiAware)
import WiFiAware
#endif

public enum WiFiAwareRIDCapability: Sendable, Equatable {
    case supportedHost
    case unsupportedHost
    case unavailableOnOS

    public static var current: Self {
        #if os(iOS) && canImport(WiFiAware)
        if #available(iOS 26.0, *) {
            return WACapabilities.supportedFeatures.contains(.wifiAware)
                ? .supportedHost
                : .unsupportedHost
        }
        #endif
        return .unavailableOnOS
    }
}
