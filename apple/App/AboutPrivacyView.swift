import SwiftUI

struct AboutPrivacyView: View {
    var body: some View {
        List {
            Section("RID2Caltopo") {
                LabeledContent("Version", value: appVersion)
                Text("Incident-support software for receiving Remote ID observations, mapping aircraft, publishing operator-authorized tracks, and analyzing live drone video.")
                    .foregroundStyle(.secondary)
            }

            Section("Privacy") {
                Text("RID2Caltopo contains no advertising or analytics SDKs, does not track people across apps or websites, and does not sell personal data.")
                Text("Remote ID observations, track archives, and diagnostic logs remain on this device unless you enable CalTopo publishing, join configured tracker peer coordination, or explicitly share a file.")
                Text("The CalTopo credential secret is stored in Apple Keychain and is never written to diagnostic logs or shared log bundles.")
            }

            Section("Permissions and network use") {
                Label("Bluetooth receives nearby ASTM Remote ID broadcasts.", systemImage: "antenna.radiowaves.left.and.right")
                Label("Location places the operator relative to aircraft on the map.", systemImage: "location")
                Label("Local Network receives controller video and optional external Remote ID observations.", systemImage: "network")
                Text("When enabled by the operator, CalTopo receives aircraft positions and telemetry for the selected map. Configured tracker peer coordination receives the app-install zone identifier, device zone name, operator position, confirmed drone identity, and aircraft sightings needed to coordinate ownership. Apple MapKit may contact Apple's map service to load map content.")
                    .foregroundStyle(.secondary)
            }

            Section("Logs and deletion") {
                Text("You choose which log days to package and where to send the bundle. A bundle may contain Remote IDs, aircraft positions, the app-install coordination identifier, app events, device and OS details, local network addresses, and operational status.")
                Text("Nothing is transmitted by log sharing until you choose a destination in the iOS share sheet. Local logs and track archives can be removed through the Files app; deleting RID2Caltopo removes its locally stored data and app-install zone identifier. CalTopo and the configured tracker control retention of data sent to them.")
            }

            Section("Contact") {
                Link("kjtsar@kjt.us", destination: URL(string: "mailto:kjtsar@kjt.us")!)
            }
        }
        .navigationTitle("About & Privacy")
    }

    private var appVersion: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return "\(version) (\(build))"
    }
}
