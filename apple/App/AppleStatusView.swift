import Foundation
import R2CCore
import SwiftUI
import UIKit

struct AppleStatusSnapshot {
    let bluetoothStatus: String
    let bluetoothObservations: Int
    let bluetoothRejected: Int
    let externalReceiverStatus: String
    let externalRelayDestination: String
    let externalObservations: Int
    let externalRejected: Int
    let wifiAwareStatus: String
    let locationStatus: String
    let configSource: String
    let organization: String
    let incident: String
    let operationalPeriod: String
    let trackerStatus: String
    let trackerDetail: String
    let trackerArchiveStatus: String
    let peerCount: Int
    let caltopoStatus: String
    let activeAircraft: Int
    let acceptedTrackPoints: Int
    let filteredObservations: Int
    let archivedTracks: Int
    let mediaMTXStatus: String
    let videoStatus: String
    let anomalyMode: String
    let importedMappings: [RidAircraftIdentity]

    var buildVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
    }

    var buildNumber: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown"
    }

    var buildArtifactDate: String {
        guard let executableURL = Bundle.main.executableURL,
              let values = try? executableURL.resourceValues(forKeys: [.contentModificationDateKey]),
              let date = values.contentModificationDate
        else { return "unknown" }
        return date.formatted(date: .abbreviated, time: .standard)
    }

    var copyText: String {
        var lines = [
            "RID2Caltopo Apple Status",
            "BUILD_VERSION: \(buildVersion)",
            "BUILD_NUMBER: \(buildNumber)",
            "BUILD_ARTIFACT_DATE: \(buildArtifactDate)",
            "",
            "Scanner Status",
            "Bluetooth Remote ID: \(bluetoothStatus)",
            "Bluetooth observations: \(bluetoothObservations)",
            "Rejected Bluetooth packets: \(bluetoothRejected)",
            "External Wi-Fi receiver: \(externalReceiverStatus)",
            "Android relay destination: \(externalRelayDestination):7654",
            "External observations: \(externalObservations)",
            "Rejected UDP datagrams: \(externalRejected)",
            "Wi-Fi Aware host: \(wifiAwareStatus)",
            "Location: \(locationStatus)",
            "",
            "Loaded Configuration",
            "Source: \(configSource)",
            "Organization: \(display(organization))",
            "Incident: \(display(incident))",
            "Operational period: \(display(operationalPeriod))",
            "Tracker: \(trackerStatus)",
            "Tracker detail: \(trackerDetail)",
            "Tracker archive: \(trackerArchiveStatus)",
            "Peer zones: \(peerCount)",
            "CalTopo: \(caltopoStatus)",
            "",
            "Runtime",
            "Active aircraft: \(activeAircraft)",
            "Accepted track points: \(acceptedTrackPoints)",
            "Filtered observations: \(filteredObservations)",
            "Archived tracks: \(archivedTracks)",
            "MediaMTX: \(mediaMTXStatus)",
            "Video decoder: \(videoStatus)",
            "Anomaly detector: \(anomalyMode)",
            "",
            "Persisted Drone Mappings",
        ]
        if importedMappings.isEmpty {
            lines.append("No persisted drone mappings.")
        } else {
            lines.append(contentsOf: importedMappings.map { identity in
                "remoteId: \(identity.remoteID)    mappedId: \(identity.mappedID)    org: \(identity.organization)    owner: \(identity.pilotCallsign)    model: \(identity.droneDescription)"
            })
        }
        return lines.joined(separator: "\n")
    }

    private func display(_ value: String) -> String {
        value.isEmpty ? "Not configured" : value
    }
}

struct AppleStatusView: View {
    let snapshot: AppleStatusSnapshot
    @State private var copied = false

    var body: some View {
        List {
            Section("Build") {
                LabeledContent("Version", value: versionText)
                LabeledContent("Artifact date", value: artifactDate)
            }

            Section("Scanner Status") {
                LabeledContent("Bluetooth Remote ID", value: snapshot.bluetoothStatus)
                LabeledContent("Bluetooth observations", value: "\(snapshot.bluetoothObservations)")
                LabeledContent("Rejected Bluetooth packets", value: "\(snapshot.bluetoothRejected)")
                LabeledContent("External Wi-Fi receiver", value: snapshot.externalReceiverStatus)
                LabeledContent("Android relay destination", value: "\(snapshot.externalRelayDestination):7654")
                    .textSelection(.enabled)
                LabeledContent("External observations", value: "\(snapshot.externalObservations)")
                LabeledContent("Rejected UDP datagrams", value: "\(snapshot.externalRejected)")
                LabeledContent("Wi-Fi Aware host", value: snapshot.wifiAwareStatus)
                LabeledContent("Location", value: snapshot.locationStatus)
            }

            Section("Loaded Configuration") {
                LabeledContent("Source", value: snapshot.configSource)
                LabeledContent("Organization", value: display(snapshot.organization))
                LabeledContent("Incident", value: display(snapshot.incident))
                if !snapshot.operationalPeriod.isEmpty {
                    LabeledContent("Operational period", value: snapshot.operationalPeriod)
                }
                LabeledContent("Tracker", value: snapshot.trackerStatus)
                Text(snapshot.trackerDetail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                LabeledContent("Tracker archive", value: snapshot.trackerArchiveStatus)
                LabeledContent("Peer zones", value: "\(snapshot.peerCount)")
                LabeledContent("CalTopo", value: snapshot.caltopoStatus)
            }

            Section("Runtime") {
                LabeledContent("Active aircraft", value: "\(snapshot.activeAircraft)")
                LabeledContent("Accepted track points", value: "\(snapshot.acceptedTrackPoints)")
                LabeledContent("Filtered observations", value: "\(snapshot.filteredObservations)")
                LabeledContent("Archived tracks", value: "\(snapshot.archivedTracks)")
                LabeledContent("MediaMTX", value: snapshot.mediaMTXStatus)
                LabeledContent("Video decoder", value: snapshot.videoStatus)
                LabeledContent("Anomaly detector", value: snapshot.anomalyMode)
            }

            Section("Persisted Drone Mappings") {
                if snapshot.importedMappings.isEmpty {
                    Text("No persisted drone mappings.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(snapshot.importedMappings, id: \.remoteID) { identity in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(identity.remoteID).font(.headline)
                            Text(identity.mappedID)
                            Text(mappingDetail(identity))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .textSelection(.enabled)
                    }
                }
            }

            Section {
                Button(copied ? "Status Copied" : "Copy Status", systemImage: copied ? "checkmark" : "doc.on.doc") {
                    copyStatus()
                }
            } footer: {
                Text("The copied report intentionally excludes configuration tokens and credential secrets.")
            }
        }
        .navigationTitle("Status")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Copy", systemImage: "doc.on.doc", action: copyStatus)
            }
        }
    }

    private var versionText: String {
        "\(snapshot.buildVersion) (\(snapshot.buildNumber))"
    }

    private var artifactDate: String {
        snapshot.buildArtifactDate
    }

    private func display(_ value: String) -> String {
        value.isEmpty ? "Not configured" : value
    }

    private func mappingDetail(_ identity: RidAircraftIdentity) -> String {
        [identity.organization, identity.pilotCallsign, identity.droneDescription]
            .filter { !$0.isEmpty }
            .joined(separator: " • ")
    }

    private func copyStatus() {
        UIPasteboard.general.string = snapshot.copyText
        copied = true
        AppleLog.info("Status", "Status copied to clipboard")
    }
}
