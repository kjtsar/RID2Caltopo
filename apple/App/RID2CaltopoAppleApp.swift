import SwiftUI
import UIKit

@MainActor
final class AppleApplicationCleanupCenter {
    static let shared = AppleApplicationCleanupCenter()

    typealias Cleanup = @MainActor () async -> Void

    private var markerCleanup: Cleanup?
    private var fullCleanup: Cleanup?
    private var markerCleanupTask: Task<Void, Never>?
    private var fullCleanupTask: Task<Void, Never>?

    func register(markerCleanup: @escaping Cleanup, fullCleanup: @escaping Cleanup) {
        self.markerCleanup = markerCleanup
        self.fullCleanup = fullCleanup
    }

    func removeMarkerForBackgrounding() {
        guard markerCleanupTask == nil, let markerCleanup else { return }
        markerCleanupTask = runWithBackgroundTime(name: "Remove CalTopo device marker") {
            await markerCleanup()
            self.markerCleanupTask = nil
        }
    }

    func closePrimaryWindow(reason: String) {
        guard fullCleanupTask == nil, let fullCleanup else { return }
        AppleLog.info("Lifecycle", "Closing primary application session reason=\(reason)")
        fullCleanupTask = runWithBackgroundTime(name: "Close RID2Caltopo session") {
            await fullCleanup()
            self.fullCleanupTask = nil
            AppleLog.info("Lifecycle", "Primary application session cleanup completed")
        }
    }

    private func runWithBackgroundTime(
        name: String,
        operation: @escaping @MainActor () async -> Void
    ) -> Task<Void, Never> {
        var taskID = UIBackgroundTaskIdentifier.invalid
        taskID = UIApplication.shared.beginBackgroundTask(withName: name) {
            if taskID != .invalid {
                UIApplication.shared.endBackgroundTask(taskID)
                taskID = .invalid
            }
        }
        return Task { @MainActor in
            await operation()
            if taskID != .invalid {
                UIApplication.shared.endBackgroundTask(taskID)
                taskID = .invalid
            }
        }
    }
}

@main
struct RID2CaltopoAppleApp: App {
    @UIApplicationDelegateAdaptor(RID2CaltopoAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
