# RID2Caltopo for Apple platforms

This directory contains the Apple-platform implementation of RID2Caltopo. Open
`RID2CaltopoApple.xcodeproj` to run the universal SwiftUI shell on an iPhone or
iPad Simulator.

The initial architecture keeps platform radio and UI code thin around portable
contracts and native cores:

1. CoreBluetooth or an external receiver produces `RidObservation` values.
2. Shared tracking policy consumes observations and publishes accepted updates.
3. The verified MediaMTX Go core receives controller RTMP and exposes Low-Latency HLS locally.
4. AVFoundation produces BGRA `CVPixelBuffer` frames for the portable C anomaly core.
5. SwiftUI and Metal present operator state, video, and anomaly annotations.

`RidObservationProvider` is the radio boundary and `MediaServerController` is
the in-process Go server boundary. Both use `AsyncStream`, which keeps their
worker threads and goroutines out of SwiftUI.

Build both native dependencies before opening the app project on a fresh checkout:

```sh
apple/Native/MediaMTX/build-xcframework.sh
apple/Native/AnomalyCore/build-xcframework.sh
```

The Swift package can be built and tested without the application target:

```sh
cd apple
swift test
```

The primary pre-release gate is:

```sh
apple/release-check.sh
```

It rebuilds the current MediaMTX and anomaly XCFramework device/Simulator
slices, checks their exported C ABI, runs the portable anomaly regression and
realtime qualification suites, runs all shared Swift tests, performs a clean
arm64 Simulator link, and verifies a fresh unsigned arm64 iPhone/iPad archive. Use
`--skip-native-rebuild` only for an intentional fast rerun after the native
artifacts have already been rebuilt from the unchanged source trees.

Radio, background behavior, controller networking, and performance must be
qualified on a physical device even when equivalent Simulator tests pass.

The Apple app accepts the same configuration QR payloads produced by Android:
organization (`R2C1:`), FAA (`R2CFAA1:`), and mutual-aid (`R2CMA1:`). Choose
**Import Config** from the incident card or the More menu, then scan the QR code
or paste its text. Android's corresponding `r2c1://`, `r2cfaa1://`, and
`r2cma1://` links also open the importer. Public Google Drive bundles use the
same Android token decoding and encrypted-field formats; credential secrets are
stored in Keychain while non-secret incident settings and imported Remote ID
mappings are persisted locally. Organization bundles also apply Android's
embedded FAA configuration and mutual-aid credential template. Simulator tests
cover decoding and the import UI, but camera scanning and a real Drive-hosted
bundle remain physical-device field-test gates.

The app owns a `CLLocationManager`, requests When In Use authorization at
startup, publishes accuracy/error state on the Status screen, and renders the
operator using MapKit's user annotation. This is the Apple foundation for
operator-relative mapping and proximity behavior; physical-device accuracy and
background policy remain part of the hardware gate.

For a Simulator stream smoke test, launch the app with `--start-mediamtx`,
`--start-video`, and `--show-anomaly`, then publish a source to
`rtmp://127.0.0.1:1935/demo`. The live view renders the HLS player and overlays
normalized detector boxes on the same decoded raster. It also reports decoded
frames, analyzed frames, backpressure drops, and current anomaly boxes.
The detector control matches Android's high-level appearance choices: **Off**
keeps video connected without spending analysis CPU, **Color** runs the shared
fresh-RGBA Color Uniqueness detector, and **Infrared** runs the shared thermal
detector. The persisted choice can be changed from either the main Anomaly
Detector section or Live View and is included in the copyable Status report.
On physical hardware, the Anomaly Detector section displays the device's
copyable `rtmp://<wifi-address>:1935/demo` controller target.

For a deterministic Remote ID map smoke test, launch with `--demo-rid` and
`--show-map`. Two synthetic aircraft exercise the same `RidTrackStore` actor
used by live Bluetooth observations, including track polylines and current
aircraft annotations on both iPhone and iPad.

Track completion writes Android-compatible GeoJSON into
`Documents/RID2Caltopo/Tracks/YYYY-MM-DD`. File Sharing and opening documents
in place are enabled, so operators can recover these archives through Finder
or the Files app. `--archive-demo` provides a deterministic Simulator smoke
test when used with `--demo-rid`.

Live CalTopo publishing is disabled by default. The in-app CalTopo screen stores
the credential secret in the Apple Keychain and non-secret map settings in
`UserDefaults`. When explicitly enabled, accepted track points create the same
signed `LiveTrack` object and use the same `position/report/DRONE` endpoint as
Android. When a local aircraft track ages out, the app saves its GeoJSON and
issues the same signed `LiveTrack/{id}` DELETE used by Android, treating 400/404
as an already-completed stop. Real-server qualification still requires an
operator-supplied test map and credential.

When a platform cannot expose passive ASTM Wi-Fi broadcasts, an external radio
or companion computer can send one normalized JSON observation per UDP datagram
to port 7654. Start the listener in the Remote ID screen or use
`--start-external-rid`. The datagram schema is:

```json
{
  "aircraft_id": "RID-SERIAL-01",
  "source": "wifiNan",
  "timestamp_ms": 1700000000123,
  "latitude": 39.7392,
  "longitude": -104.9903,
  "altitude_m": 1620.5,
  "heading_deg": 92,
  "speed_mps": 11.5,
  "operator_latitude": 39.74,
  "operator_longitude": -104.99,
  "rssi_dbm": -61
}
```

Only `aircraft_id`, `latitude`, and `longitude` are required. Accepted `source`
values are the `RidObservation.Source` raw values; omitted or unknown values are
recorded as `externalReceiver`. Invalid JSON and out-of-range coordinates are
counted and discarded before they reach the track store.

With an installed Simulator build running and listening, the complete external
receiver-to-track-to-log seam can be checked repeatably with:

```sh
apple/smoke-external-rid.sh booted
```

The script sends a unique Android-shaped Wi-Fi NAN observation and succeeds
only after the app's current daily log contains its accepted `rid_rx` record.

The Android app can provide that Wi-Fi radio path directly. In Android
**Settings**, enable **Apple Wi-Fi Remote ID Relay** and enter the IPv4 address
shown as **Android relay destination** on the Apple Remote ID or Status screen.
Android then forwards accepted Wi-Fi Beacon and Wi-Fi NAN observations to UDP
7654 using the normalized schema above. Bluetooth observations and credentials
are not forwarded. The relay is off by default; `255.255.255.255` can be used
for same-network broadcast when the access point permits it, though the Apple
device's explicit IPv4 address is more reliable.

The same UDP port also accepts compact binary ASTM OpenDroneID payloads from an
external Wi-Fi radio: a raw 25-byte message, concatenated 25-byte messages, a
raw Message Pack, or the complete Bluetooth FFFA service-data value (application
code, message counter, and message). Basic ID and Location messages may arrive
in separate datagrams; state is assembled per UDP sender. The external adapter
should remove device-specific 802.11 frame headers before forwarding.

The Apple app keeps daily diagnostic logs under
`Documents/RID2Caltopo/Logs`. **More > Send app log to Ken…** provides the same
operator flow as Android: select one or more days, package the logs, and choose
a destination using the system share sheet. The bundle includes useful app,
OS, hardware, network, decoder/scanner state, and Android-shaped `rid_rx`
evidence for accepted Remote ID waypoints, but never the CalTopo credential
secret. **More > About & Privacy** explains the behavior in-app, including that
shared diagnostics may contain Remote IDs and aircraft positions.

The primary screen follows Android's operator hierarchy as closely as practical
in SwiftUI: an incident/configuration summary first, followed by scanner and
Nearby Aircraft status, with Live View, log forwarding, Status, Import Config,
Settings, and About & Privacy in the More menu. Like Android, **Status** opens a
copyable report with build, scanner, external receiver, location, loaded QR
configuration, tracker, CalTopo, MediaMTX, video, and persisted-drone-mapping
state; tokens and credential secrets are deliberately omitted. The primary
screen includes an Android-inspired Nearby Aircraft list. Each row
shows Remote ID, per-transport accepted counts, signal bars/RSSI, point count,
altitude, and range from the device when location is available. Tapping a row
opens the latest position, speed, heading, device-relative range and bearing,
reported operator position, accumulated distance, and transport breakdown.
The detail screen also provides **Confirm Drone**, using Android's organization,
pilot callsign, and drone-description fields and mapped-ID naming rule. Manual
confirmations are session-only; Remote ID mappings imported from an Android QR
configuration persist locally.

When tracker peer coordination is enabled, the app connects to the Android
tracker's `/ws/r2c` endpoint with `X-SAR-Token` authentication. It sends the
same hello, heartbeat, first-sighting, sighting, lost, and confirmation payloads,
replays active state after reconnect, and displays link/peer status in the
incident header. CalTopo publication is allowed only after this Apple device
holds the current tracker lease *and* the operator has confirmed the drone
locally. Accepted points queue during handoff, then flush in order after the
same two-second stabilization delay used by Android. A non-owner relays its
sightings to the owner rather than publishing them independently.

With two or more active aircraft, Status also reports the closest pair's
horizontal, vertical, and three-dimensional separation. Apple now applies the
Android proximity threshold imported from the organization QR. An alert is
eligible only when the pair contains a confirmed team drone and at least one
aircraft has both the local tracker lease and a local Save confirmation. The
operator receives a visible warning card, speech and haptic warning, **Map** and
**Suspend** controls, and a visible **Resume Proximity Alert** control while the
pair remains inside the threshold. Alerts clear only after three seconds outside
the threshold. The Android-compatible **Predictive Head** setting is imported
from the organization QR and is also visible in Settings. When enabled, Apple
uses the last two samples to project horizontal and vertical motion forward by
up to two seconds before applying the same threshold; projected warnings are
identified in the alert readout and diagnostics.

See `TESTFLIGHT.md` for distributing a signed beta to field testers who do not
have Xcode or a developer account.

The project is assigned to Apple Developer team `94UV79S6LR`. The TestFlight
script intentionally archives without signing and lets Xcode apply its managed
Apple Distribution certificate and App Store profile during export. This allows
device-free TestFlight packaging; a physical iPhone or iPad is still required
for development-install and field qualification.

Like Android's scanning service, the Apple app starts its Bluetooth Remote ID
scan and external UDP listener automatically at UI startup; operators retain
visible Stop controls. The app declares only the `bluetooth-central` background
mode. iOS may wake it for Bluetooth central events, but background scanning is
slower and coalesces duplicate discoveries, so continuous Remote ID position
updates are not assumed until proven on hardware. UDP, MediaMTX, decoded video,
and anomaly processing remain foreground-only workflows.
