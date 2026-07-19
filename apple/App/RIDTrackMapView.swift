import MapKit
import R2CCore
import SwiftUI

struct RIDTrackMapView: View {
    @ObservedObject var model: RIDTrackViewModel
    @ObservedObject var locationProvider: AppleLocationProvider
    @State private var position: MapCameraPosition = .automatic

    var body: some View {
        Map(position: $position) {
            if locationProvider.lastLocation != nil {
                UserAnnotation()
            }
            ForEach(Array(model.tracks.enumerated()), id: \.element.id) { index, track in
                let color = trackColor(index)
                if track.points.count > 1 {
                    MapPolyline(coordinates: track.points.map(\.coordinate))
                        .stroke(color, lineWidth: 4)
                }
                if let latest = track.points.last {
                    Annotation("", coordinate: latest.coordinate, anchor: .center) {
                        VStack(spacing: 2) {
                            Image(systemName: "airplane")
                                .font(.title2)
                                .foregroundStyle(.white)
                                .padding(7)
                                .background(color, in: Circle())
                                .rotationEffect(.degrees((latest.headingDegrees ?? 0) - 90))
                            Text(track.aircraftID)
                                .font(.caption2.bold())
                                .padding(.horizontal, 5)
                                .padding(.vertical, 2)
                                .background(.regularMaterial, in: Capsule())
                        }
                    }
                }
                if let latitude = track.lastObservation.operatorLatitude,
                   let longitude = track.lastObservation.operatorLongitude,
                   latitude != 0, longitude != 0 {
                    Marker(
                        "Operator \(track.aircraftID)",
                        systemImage: "person.wave.2",
                        coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
                    )
                    .tint(color)
                }
            }
        }
        .mapControls {
            MapCompass()
            MapScaleView()
            MapUserLocationButton()
        }
        .safeAreaInset(edge: .top) {
            HStack {
                Label("\(model.tracks.count) active", systemImage: "airplane.circle")
                Spacer()
                Text("\(model.acceptedObservationCount) points")
                Text("\(model.filteredObservationCount) filtered")
            }
            .font(.subheadline.monospacedDigit())
            .padding(.horizontal)
            .padding(.vertical, 10)
            .background(.regularMaterial)
        }
        .navigationTitle("Remote ID Tracks")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func trackColor(_ index: Int) -> Color {
        [.blue, .orange, .purple, .green, .pink, .cyan][index % 6]
    }
}

private extension RidTrackPoint {
    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }
}
