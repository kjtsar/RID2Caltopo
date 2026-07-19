import AVKit
import SwiftUI

struct AnomalyLiveView: View {
    @ObservedObject var model: AppleVideoFrameSource
    let streamURL: URL?

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Color.black
                if let player = model.player {
                    VideoPlayer(player: player)
                    AnomalyBoxOverlay(boxes: model.anomalyBoxes)
                        .allowsHitTesting(false)
                } else {
                    ContentUnavailableView(
                        "Video disconnected",
                        systemImage: "video.slash",
                        description: Text("Start MediaMTX and connect the Apple decoder.")
                    )
                    .foregroundStyle(.white)
                }
            }
            // Match the decoded raster so AVPlayer's aspect-fit image and the
            // detector's normalized coordinates occupy the same rectangle.
            .aspectRatio(model.videoAspectRatio, contentMode: .fit)

            HStack {
                Label(videoStatus, systemImage: "waveform.path.ecg")
                Spacer()
                Text("\(model.analyzedFrameCount) analyzed")
                Text("\(model.droppedAnalysisFrameCount) dropped")
                Text("\(model.anomalyCount) boxes")
            }
            .font(.subheadline.monospacedDigit())
            .padding()

            Picker("Detector", selection: Binding(
                get: { model.anomalyMode },
                set: { model.setAnomalyMode($0) }
            )) {
                ForEach(AppleAnomalyMode.allCases) { mode in
                    Text(mode.compactLabel).tag(mode)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)

            Text(detectorDescription)
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal)
                .padding(.top, 6)

            Button(model.state == .idle ? "Connect Decoder" : "Disconnect Decoder") {
                if model.state == .idle {
                    if let streamURL { model.start(url: streamURL) }
                } else {
                    model.stop()
                }
            }
            .buttonStyle(.borderedProminent)
            .padding(.bottom)
        }
        .navigationTitle("Live Anomaly View")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var videoStatus: String {
        switch model.state {
        case .idle: "Idle"
        case .connecting: "Connecting"
        case .streaming: "Streaming"
        case .failed: "Reconnecting"
        }
    }

    private var detectorDescription: String {
        switch model.anomalyMode {
        case .off:
            "Video remains connected while anomaly analysis is disabled."
        case .colorUniqueness:
            "Color Uniqueness uses the shared Android fresh-RGBA detector; boxes are yellow."
        case .infrared:
            "Infrared uses the shared Android thermal detector; boxes are red."
        }
    }
}

private struct AnomalyBoxOverlay: View {
    let boxes: [AppleAnomalyBox]

    var body: some View {
        GeometryReader { geometry in
            ForEach(boxes) { box in
                let width = max(0, box.right - box.left) * geometry.size.width
                let height = max(0, box.bottom - box.top) * geometry.size.height
                Rectangle()
                    .stroke(boxColor(box), lineWidth: max(2, 4 * box.weight))
                    .frame(width: width, height: height)
                    .position(
                        x: (box.left + box.right) * 0.5 * geometry.size.width,
                        y: (box.top + box.bottom) * 0.5 * geometry.size.height
                    )
            }
        }
    }

    private func boxColor(_ box: AppleAnomalyBox) -> Color {
        switch box.algorithm {
        case Int(R2C_ANOMALY_ALGORITHM_COLOR): .yellow
        case Int(R2C_ANOMALY_ALGORITHM_THERMAL): .red
        default: .cyan
        }
    }
}
