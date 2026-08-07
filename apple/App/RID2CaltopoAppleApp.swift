import R2CCore
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
    private var fullCleanupCompleted = false

    func register(markerCleanup: @escaping Cleanup, fullCleanup: @escaping Cleanup) {
        self.markerCleanup = markerCleanup
        self.fullCleanup = fullCleanup
        fullCleanupCompleted = false
    }

    func removeMarkerForBackgrounding() {
        guard markerCleanupTask == nil, let markerCleanup else { return }
        markerCleanupTask = runWithBackgroundTime(name: "Remove CalTopo device marker") {
            await markerCleanup()
            self.markerCleanupTask = nil
        }
    }

    func closePrimaryWindow(reason: String) {
        performFullCleanup(reason: reason, dismissWindow: false)
    }

    func quitPrimaryWindow(reason: String) {
        performFullCleanup(reason: reason, dismissWindow: true)
    }

    func declineLaunchDisclaimer() {
        AppleLog.info("Lifecycle", "Launch disclaimer declined; closing primary window")
        dismissPrimaryWindow()
    }

    private func performFullCleanup(reason: String, dismissWindow: Bool) {
        guard !fullCleanupCompleted, fullCleanupTask == nil, let fullCleanup else { return }
        AppleLog.info("Lifecycle", "Closing primary application session reason=\(reason)")
        fullCleanupTask = runWithBackgroundTime(name: "Close RID2Caltopo session") {
            await fullCleanup()
            self.fullCleanupCompleted = true
            AppleLog.info("Lifecycle", "Primary application session cleanup completed")
            if dismissWindow {
                self.dismissPrimaryWindow()
            }
            self.fullCleanupTask = nil
        }
    }

    private func dismissPrimaryWindow() {
        guard let scene = UIApplication.shared.connectedScenes.first(where: {
            $0.session.role == .windowApplication
        }) else {
            AppleLog.error("Lifecycle", "Could not close primary window: no application scene")
            return
        }
        UIApplication.shared.requestSceneSessionDestruction(
            scene.session,
            options: nil
        ) { error in
            AppleLog.error("Lifecycle", "Could not close primary window: \(error.localizedDescription)")
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

private struct LaunchDisclaimerView: View {
    let onAgree: () -> Void
    let onDisagree: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Text("Safety Disclaimer")
                    .font(.largeTitle.bold())
                    .multilineTextAlignment(.center)
                Text(ApplicationLaunchDisclaimer.text)
                    .font(.body)
                VStack(spacing: 12) {
                    Button(action: onAgree) {
                        Text("I agree")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    Button(role: .destructive, action: onDisagree) {
                        Text("I disagree")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                }
            }
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity, minHeight: 640)
            .padding(32)
        }
    }
}

private struct LaunchDisclaimerGate: View {
    @State private var accepted = false

    var body: some View {
        if accepted {
            ContentView()
        } else {
            LaunchDisclaimerView(
                onAgree: {
                    AppleLog.info("Lifecycle", "Launch disclaimer accepted")
                    accepted = true
                },
                onDisagree: AppleApplicationCleanupCenter.shared.declineLaunchDisclaimer
            )
        }
    }
}

@main
struct RID2CaltopoAppleApp: App {
    @UIApplicationDelegateAdaptor(RID2CaltopoAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            LaunchDisclaimerGate()
        }
    }
}
