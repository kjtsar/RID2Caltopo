import CoreLocation
import R2CCore
import SwiftUI
import UIKit

@MainActor
final class AppleProximityAlertCenter: ObservableObject {
    @Published private(set) var activeAlert: RidProximityAlertState?
    @Published private(set) var suspendedAlert: RidProximityAlertState?
    @Published private(set) var canResume = false

    private var engine = RidProximityAlertEngine()
    private var lastAnnouncementByPair: [String: Date] = [:]

    func update(
        tracks: [RidAircraftTrack],
        thresholdFeet: Int,
        predictiveEnabled: Bool,
        operatorLocation: CLLocation?,
        identityProvider: (String) -> RidAircraftIdentity?,
        alertEligibility: (String) -> Bool,
        now: Date = Date()
    ) {
        let drones = tracks.map { track in
            let identity = identityProvider(track.aircraftID)
            let observation = track.lastObservation
            let distance = operatorLocation.flatMap { location in
                RidGeometry.relativePosition(
                    fromLatitude: location.coordinate.latitude,
                    longitude: location.coordinate.longitude,
                    toLatitude: observation.latitude,
                    longitude: observation.longitude
                )?.distanceMeters
            }
            return RidProximityDrone(
                remoteID: track.aircraftID,
                mappedID: identity?.mappedID ?? track.aircraftID,
                latitude: observation.latitude,
                longitude: observation.longitude,
                altitudeMeters: observation.altitudeMeters,
                sampleDate: observation.receivedAt,
                distanceToOperatorMeters: distance,
                teamDrone: identity != nil,
                localAlertEligible: alertEligibility(track.aircraftID)
            )
        }
        apply(
            engine.update(
                drones: drones,
                thresholdFeet: Double(thresholdFeet),
                predictiveEnabled: predictiveEnabled,
                now: now
            ),
            now: now
        )
    }

    func suspend() {
        apply(engine.suspend(), now: Date(), announce: false)
        AppleLog.info("ProximityAlert", "Current proximity alert suspended")
    }

    func resume() {
        apply(engine.resume(), now: Date(), announce: false)
        AppleLog.info("ProximityAlert", "Suspended proximity alert resumed")
    }

    private func apply(
        _ output: RidProximityAlertOutput,
        now: Date,
        announce: Bool = true
    ) {
        let previousID = activeAlert?.alertInstanceID
        activeAlert = output.activeAlert
        suspendedAlert = output.suspendedAlert
        canResume = output.canResume
        guard announce,
              let alert = output.activeAlert,
              alert.alertInstanceID != previousID
        else { return }

        let last = lastAnnouncementByPair[alert.pairKey] ?? .distantPast
        guard now.timeIntervalSince(last) >= 30 else { return }
        lastAnnouncementByPair[alert.pairKey] = now
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
        AppleSpokenWarningCenter.shared.speak("Proximity warning")
        AppleLog.info(
            "ProximityAlert",
            "Alert pair=\(alert.pairKey) horizontalFt=\(Int(alert.horizontalSeparationFeet.rounded())) verticalFt=\(Int(alert.verticalSeparationFeet.rounded())) currentHorizontalFt=\(Int(alert.currentHorizontalSeparationFeet.rounded())) currentVerticalFt=\(Int(alert.currentVerticalSeparationFeet.rounded())) projected=\(alert.usesProjection) thresholdFt=\(Int(alert.thresholdFeet.rounded()))"
        )
    }
}

struct ProximityAlertBanner: View {
    let alert: RidProximityAlertState
    let onMap: () -> Void
    let onSuspend: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Proximity Alert", systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(alert.highSeverity ? .red : .orange)
            Text("Both horizontal and vertical spacing crossed the \(feet(alert.thresholdFeet)) threshold.")
                .font(.subheadline)
            HStack {
                VStack(alignment: .leading) {
                    Text("Near: \(alert.nearestDroneMappedID)")
                    Text("High: \(alert.highestDroneMappedID)")
                }
                Spacer()
                VStack(alignment: .trailing) {
                    Text("\(alert.usesProjection ? "Projected H" : "H"): \(feet(alert.horizontalSeparationFeet))")
                    Text("V: \(feet(alert.verticalSeparationFeet))")
                }
                .fontWeight(.semibold)
            }
            HStack {
                Button("Map", action: onMap)
                    .buttonStyle(.borderedProminent)
                Button("Suspend", action: onSuspend)
                    .buttonStyle(.bordered)
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .overlay {
            RoundedRectangle(cornerRadius: 16)
                .stroke(alert.highSeverity ? .red : .orange, lineWidth: 2)
        }
        .shadow(radius: 8)
        .padding()
        .accessibilityIdentifier("proximity-alert")
    }

    private func feet(_ value: Double) -> String {
        "\(Int(value.rounded())) ft"
    }
}
