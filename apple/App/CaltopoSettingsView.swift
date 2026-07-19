import SwiftUI

struct CaltopoSettingsView: View {
    @ObservedObject var settings: AppleCaltopoSettings
    @ObservedObject var orgSettings: AppleOrgConfigSettings
    let onSave: (AppleCaltopoConfiguration) -> Void

    var body: some View {
        Form {
            Section("Publishing") {
                Toggle("Enable live CalTopo publishing", isOn: $settings.enabled)
                TextField("Domain", text: $settings.domainAndPort)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("Map ID", text: $settings.mapID)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            Section("Team credential") {
                TextField("Credential ID", text: $settings.credentialID)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Credential secret", text: $settings.credentialSecret)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Text("The credential secret is stored in the Apple Keychain. Publishing is off by default and no request is made until enabled.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section("Traffic safety") {
                Toggle(
                    "Predictive Head",
                    isOn: Binding(
                        get: { orgSettings.predictiveHeadEnabled },
                        set: { enabled in
                            orgSettings.setPredictiveHeadEnabled(enabled)
                        }
                    )
                )
                LabeledContent(
                    "Proximity spacing",
                    value: "\(orgSettings.proximityAlertSpacingFeet) ft"
                )
                Text("Predictive Head projects the latest aircraft motion forward by up to two seconds, matching Android. The spacing threshold comes from the imported organization configuration.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Section {
                Button("Save CalTopo Configuration") {
                    onSave(settings.save())
                }
                Text(settings.status)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Settings")
    }
}
