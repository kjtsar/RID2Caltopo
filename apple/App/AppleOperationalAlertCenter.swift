import AVFoundation
import CoreLocation
import R2CCore
import SwiftUI
import UIKit

struct AppleSignalLossAlert: Identifiable, Equatable {
    var id: String { remoteID }
    let remoteID: String
    let mappedID: String
    let idleSeconds: Double
    let distanceFeet: Double
}

struct AppleAltitudeComplianceAlert: Identifiable, Equatable {
    var id: String { remoteID }
    let remoteID: String
    let mappedID: String
    let aglFeet: Double
    let severity: OperationalAltitudeSeverity
}

@MainActor
final class AppleOperationalAlertCenter: ObservableObject {
    @Published private(set) var signalLossAlerts: [AppleSignalLossAlert] = []
    @Published private(set) var altitudeAlerts: [AppleAltitudeComplianceAlert] = []
    @Published private(set) var mutedSignalFlights: Set<String> = []
    @Published private(set) var mutedAltitudeFlights: Set<String> = []

    private let speech = AVSpeechSynthesizer()
    private var exceededBridge: Set<String> = []
    private var lastSpoken: [String: Date] = [:]

    func update(
        tracks: [RidAircraftTrack],
        altitudeDisplay: [String: OperationalAircraftAltitudeDisplay],
        operatorLocation: CLLocation?,
        identityProvider: (String) -> RidAircraftIdentity?,
        alertEligibility: (String) -> Bool,
        bridgeCheckDistanceFeet: Double = 20,
        maximumTrackDelaySeconds: Double = 30,
        now: Date = Date()
    ) {
        let activeIDs = Set(tracks.map(\.aircraftID))
        exceededBridge.formIntersection(activeIDs)
        mutedSignalFlights.formIntersection(activeIDs)
        mutedAltitudeFlights.formIntersection(activeIDs)

        var lost: [AppleSignalLossAlert] = []
        var altitude: [AppleAltitudeComplianceAlert] = []
        for track in tracks {
            guard let identity = identityProvider(track.aircraftID), alertEligibility(track.aircraftID) else { continue }
            if let operatorLocation,
               let currentDistance = Self.distanceFeet(
                   from: operatorLocation.coordinate,
                   to: track.lastObservation.coordinate
               ) {
                let takeoffDistance = track.points.first.flatMap { point in
                    Self.distanceFeet(
                        from: CLLocationCoordinate2D(latitude: point.latitude, longitude: point.longitude),
                        to: track.lastObservation.coordinate
                    )
                }
                let intervals = zip(track.points.dropFirst(), track.points).map {
                    $0.0.receivedAt.timeIntervalSince($0.1.receivedAt)
                }.filter { $0 > 0 }
                let decision = OperationalSignalLossPolicy.evaluate(.init(
                    signalIdleSeconds: max(0, now.timeIntervalSince(track.lastSignalAt)),
                    learnedIntervalSeconds: intervals.isEmpty ? nil : intervals.reduce(0, +) / Double(intervals.count),
                    learnedSamples: intervals.count,
                    distanceFromDeviceFeet: currentDistance,
                    distanceFromTakeoffFeet: takeoffDistance,
                    bridgeCheckDistanceFeet: bridgeCheckDistanceFeet,
                    maximumTrackDelaySeconds: maximumTrackDelaySeconds,
                    hasPreviouslyExceededBridgeDistance: exceededBridge.contains(track.aircraftID)
                ))
                if decision.hasExceededBridgeDistance { exceededBridge.insert(track.aircraftID) }
                if decision.alert, !mutedSignalFlights.contains(track.aircraftID) {
                    lost.append(.init(
                        remoteID: track.aircraftID,
                        mappedID: identity.mappedID,
                        idleSeconds: max(0, now.timeIntervalSince(track.lastSignalAt)),
                        distanceFeet: currentDistance
                    ))
                }
            }

            if let agl = altitudeDisplay[track.aircraftID]?.aglFeet {
                let severity = OperationalAltitudeAlertPolicy.severity(aglFeet: agl)
                if severity != .normal, !mutedAltitudeFlights.contains(track.aircraftID) {
                    altitude.append(.init(
                        remoteID: track.aircraftID,
                        mappedID: identity.mappedID,
                        aglFeet: agl,
                        severity: severity
                    ))
                }
            }
        }
        lost.sort { $0.idleSeconds > $1.idleSeconds }
        altitude.sort { lhs, rhs in
            lhs.severity != rhs.severity ? lhs.severity > rhs.severity : lhs.aglFeet > rhs.aglFeet
        }
        announceNewSignalAlerts(lost, now: now)
        announceAltitudeAlerts(altitude, now: now)
        signalLossAlerts = lost
        altitudeAlerts = altitude
    }

    func muteSignal(_ remoteID: String) {
        mutedSignalFlights.insert(remoteID)
        signalLossAlerts.removeAll { $0.remoteID == remoteID }
        AppleLog.info("SignalLossAlert", "Muted flight remoteId=\(remoteID)")
    }

    func muteAltitude(_ remoteID: String) {
        mutedAltitudeFlights.insert(remoteID)
        altitudeAlerts.removeAll { $0.remoteID == remoteID }
        AppleLog.info("AltitudeAlert", "Muted flight remoteId=\(remoteID)")
    }

    private func announceNewSignalAlerts(_ alerts: [AppleSignalLossAlert], now: Date) {
        for alert in alerts where shouldSpeak(key: "signal:\(alert.remoteID)", now: now) {
            speak("Drone signal lost, \(alert.mappedID)")
            AppleLog.warning(
                "SignalLossAlert",
                "Alert remoteId=\(alert.remoteID) mappedId=\(alert.mappedID) idleSeconds=\(Int(alert.idleSeconds)) distanceFeet=\(Int(alert.distanceFeet))"
            )
        }
    }

    private func announceAltitudeAlerts(_ alerts: [AppleAltitudeComplianceAlert], now: Date) {
        for alert in alerts where alert.severity == .overLimit && shouldSpeak(key: "altitude:\(alert.remoteID)", now: now) {
            speak("Altitude warning, \(alert.mappedID), \(Int(alert.aglFeet.rounded())) feet A G L")
            AppleLog.warning(
                "AltitudeAlert",
                "Over limit remoteId=\(alert.remoteID) mappedId=\(alert.mappedID) aglFeet=\(Int(alert.aglFeet.rounded()))"
            )
        }
    }

    private func shouldSpeak(key: String, now: Date) -> Bool {
        guard now.timeIntervalSince(lastSpoken[key] ?? .distantPast) >= 30 else { return false }
        lastSpoken[key] = now
        return true
    }

    private func speak(_ text: String) {
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
        let utterance = AVSpeechUtterance(string: text)
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        speech.speak(utterance)
    }

    private static func distanceFeet(from: CLLocationCoordinate2D, to: CLLocationCoordinate2D) -> Double? {
        RidGeometry.relativePosition(
            fromLatitude: from.latitude,
            longitude: from.longitude,
            toLatitude: to.latitude,
            longitude: to.longitude
        ).map { $0.distanceMeters * 3.28084 }
    }
}

struct OperationalAlertBanner: View {
    let signalLoss: AppleSignalLossAlert?
    let altitude: AppleAltitudeComplianceAlert?
    let onMap: () -> Void
    let onMuteSignal: (String) -> Void
    let onMuteAltitude: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let signalLoss {
                Label("Drone Signal Lost", systemImage: "antenna.radiowaves.left.and.right.slash")
                    .font(.headline).foregroundStyle(.red)
                Text("\(signalLoss.mappedID): no telemetry for \(Int(signalLoss.idleSeconds.rounded())) seconds, \(Int(signalLoss.distanceFeet.rounded())) ft from this device.")
                controls { onMuteSignal(signalLoss.remoteID) }
            } else if let altitude {
                Label(
                    altitude.severity == .overLimit ? "Altitude Limit Exceeded" : "Approaching Altitude Limit",
                    systemImage: "arrow.up.to.line.compact"
                )
                .font(.headline)
                .foregroundStyle(altitude.severity == .overLimit ? .red : .orange)
                Text("\(altitude.mappedID): \(Int(altitude.aglFeet.rounded())) ft AGL")
                controls { onMuteAltitude(altitude.remoteID) }
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .overlay { RoundedRectangle(cornerRadius: 16).stroke(.red, lineWidth: 2) }
        .shadow(radius: 8)
        .padding()
        .accessibilityIdentifier("operational-alert")
    }

    private func controls(onMute: @escaping () -> Void) -> some View {
        HStack {
            Button("Map", action: onMap).buttonStyle(.borderedProminent)
            Button("Mute Flight", action: onMute).buttonStyle(.bordered)
        }
    }
}

private extension RidObservation {
    var coordinate: CLLocationCoordinate2D { .init(latitude: latitude, longitude: longitude) }
}
