import CoreLocation
import Foundation

@MainActor
final class AppleLocationProvider: NSObject, ObservableObject, @preconcurrency CLLocationManagerDelegate {
    @Published private(set) var authorizationStatus: CLAuthorizationStatus
    @Published private(set) var lastLocation: CLLocation?
    @Published private(set) var locationOverride: CLLocation?
    @Published private(set) var errorMessage: String?

    private var manager: CLLocationManager?
    private var physicalLocation: CLLocation?
    private var started = false

    override init() {
        authorizationStatus = .notDetermined
        super.init()
    }

    private func makeManager() -> CLLocationManager {
        if let manager { return manager }
        let manager = CLLocationManager()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 5
        self.manager = manager
        return manager
    }

    func start() {
        guard !started else { return }
        started = true
        guard CLLocationManager.locationServicesEnabled() else {
            errorMessage = "Location Services are disabled"
            AppleLog.error("Location", "Location Services are disabled")
            return
        }
        let manager = makeManager()
        applyAuthorizationStatus(manager.authorizationStatus)
    }

    func stop() {
        manager?.stopUpdatingLocation()
        started = false
    }

    func requestPermission() {
        let manager = makeManager()
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            manager.startUpdatingLocation()
        case .denied, .restricted:
            errorMessage = "Location permission is unavailable"
        @unknown default:
            errorMessage = "Unknown location authorization state"
        }
    }

    func setLocationOverride(latitude: Double, longitude: Double) {
        guard CLLocationCoordinate2DIsValid(.init(latitude: latitude, longitude: longitude)) else { return }
        let location = CLLocation(
            coordinate: .init(latitude: latitude, longitude: longitude),
            altitude: physicalLocation?.altitude ?? 0,
            horizontalAccuracy: 1,
            verticalAccuracy: physicalLocation?.verticalAccuracy ?? -1,
            timestamp: Date()
        )
        locationOverride = location
        lastLocation = location
        errorMessage = nil
        AppleLog.info(
            "Location",
            "Developer override enabled latitude=\(String(format: "%.6f", latitude)) longitude=\(String(format: "%.6f", longitude))"
        )
    }

    func clearLocationOverride() {
        guard locationOverride != nil else { return }
        locationOverride = nil
        lastLocation = physicalLocation
        AppleLog.info("Location", "Developer override cleared")
    }

    var statusText: String {
        if let errorMessage { return errorMessage }
        switch authorizationStatus {
        case .notDetermined: return "Permission needed"
        case .restricted: return "Restricted"
        case .denied: return "Denied"
        case .authorizedAlways, .authorizedWhenInUse:
            guard let lastLocation else { return "Locating…" }
            return "±\(Int(lastLocation.horizontalAccuracy.rounded())) m"
        @unknown default: return "Unknown"
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        applyAuthorizationStatus(manager.authorizationStatus)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last,
              location.horizontalAccuracy >= 0,
              abs(location.timestamp.timeIntervalSinceNow) < 30
        else { return }
        physicalLocation = location
        if locationOverride == nil {
            lastLocation = location
        }
        errorMessage = nil
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if let locationError = error as? CLError, locationError.code == .locationUnknown {
            return
        }
        errorMessage = error.localizedDescription
        AppleLog.error("Location", "Update failed: \(error.localizedDescription)")
    }

    private func applyAuthorizationStatus(_ status: CLAuthorizationStatus) {
        authorizationStatus = status
        AppleLog.info("Location", "Authorization: \(authorizationDescription(status))")
        switch status {
        case .notDetermined:
            if started { manager?.requestWhenInUseAuthorization() }
        case .authorizedAlways, .authorizedWhenInUse:
            errorMessage = nil
            if started { manager?.startUpdatingLocation() }
        case .denied:
            manager?.stopUpdatingLocation()
            errorMessage = "Location permission denied"
        case .restricted:
            manager?.stopUpdatingLocation()
            errorMessage = "Location permission restricted"
        @unknown default:
            manager?.stopUpdatingLocation()
            errorMessage = "Unknown location authorization state"
        }
    }

    private func authorizationDescription(_ status: CLAuthorizationStatus) -> String {
        switch status {
        case .notDetermined: "not determined"
        case .restricted: "restricted"
        case .denied: "denied"
        case .authorizedAlways: "always"
        case .authorizedWhenInUse: "when in use"
        @unknown default: "unknown"
        }
    }
}
