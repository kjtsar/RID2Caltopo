import SwiftUI

struct DiagnosticLogView: View {
    @ObservedObject var diagnostics: AppleDiagnosticsCenter

    var body: some View {
        List {
            Section {
                Text("Select the same way you would on Android. Today is selected automatically; the resulting compressed ZIP includes device and app details, each selected log, and matching JSON track archives.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text("You choose where to send it. The bundle can contain Remote IDs, aircraft positions, the app-install coordination identifier, local network addresses, and operational status. It never includes the CalTopo credential secret.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("DJI SEI Research") {
                Toggle(
                    "Capture full type-245 payloads",
                    isOn: Binding(
                        get: { AppleSEIHexDiagnostics.enabled },
                        set: { enabled in
                            AppleSEIHexDiagnostics.enabled = enabled
                            AppleLog.info(
                                "DjiSeiHex",
                                "DJI SEI hex capture \(enabled ? "enabled" : "disabled")"
                            )
                        }
                    )
                )
                Text("Off by default and reset when the app restarts. While enabled, each payload sample observed by the video frame source is written to the diagnostic log, which can grow quickly.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Select Log Days") {
                if diagnostics.days.isEmpty {
                    ContentUnavailableView("No logs", systemImage: "doc.text.magnifyingglass")
                } else {
                    ForEach(diagnostics.days) { day in
                        Button {
                            if diagnostics.selectedDays.contains(day.name) {
                                diagnostics.selectedDays.remove(day.name)
                            } else {
                                diagnostics.selectedDays.insert(day.name)
                            }
                        } label: {
                            HStack {
                                Image(systemName: diagnostics.selectedDays.contains(day.name) ? "checkmark.circle.fill" : "circle")
                                VStack(alignment: .leading) {
                                    Text(day.name)
                                    Text("\(day.logCount) log file\(day.logCount == 1 ? "" : "s")\(day.isToday ? " • today" : "")")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            Section {
                Button("Package Logs") {
                    Task { await diagnostics.prepareSelectedBundle() }
                }
                .disabled(diagnostics.selectedDays.isEmpty || diagnostics.isPreparing)

                if diagnostics.isPreparing {
                    ProgressView("Preparing log bundle…")
                }
                if let bundleURL = diagnostics.bundleURL {
                    ShareLink(
                        item: bundleURL,
                        subject: Text("RID2Caltopo Logs"),
                        message: Text("RID2Caltopo field-test diagnostic logs")
                    ) {
                        Label("Send Logs via…", systemImage: "square.and.arrow.up")
                    }
                }
                Text(diagnostics.status)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section {
                NavigationLink("About diagnostic data and privacy") {
                    AboutPrivacyView()
                }
            }
        }
        .navigationTitle("Send App Logs")
        .task { await diagnostics.beginPackagingSession() }
    }
}
