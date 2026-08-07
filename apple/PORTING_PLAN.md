# Apple port bring-up plan

## Runtime shape

Keep the Apple UI and platform radios thin around portable contracts:

```text
CoreBluetooth (direct RID and DS110 bridge)
                      |
                      v
                RidObservation
                      |
                      v
           tracking and CalTopo policy

controller RTMP -> embedded MediaMTX -> Low-Latency HLS -> AVFoundation frames
                                                               |
                                                               v
                                                      portable anomaly core
```

The MediaMTX server must run in-process on iOS. The Android launcher currently
uses `fork()` and `exec()` from `mediamtx_jni.c`; that process model is not an
iOS option. The Apple bridge will instead expose a small C ABI from a Go archive
and start MediaMTX's server core on a dedicated Go goroutine. Swift owns only
the lifecycle calls and structured event callback.

## First validation ladder

1. Build `R2CCore` and run its unit tests on macOS.
2. Build and launch `RID2CaltopoApple` in iPhone and iPad Simulators.
3. Build the verified MediaMTX tree as an arm64 iOS archive and prove
   start/stop plus RTMP-to-RTSP loopback on a physical iPad.
4. Feed decoded `CVPixelBuffer` frames through the portable C anomaly core.
5. Add CoreBluetooth Remote ID ingestion and compare decoded observations with
   the Android implementation using the same transmitters.
6. Qualify Wi-Fi Remote ID aircraft through the DS110 Bluetooth relay.

The field architecture intentionally uses a DS110 wireless relay for Wi-Fi
Beacon/NAN Remote ID aircraft. Apple consumes the relayed report through the
same CoreBluetooth parser as direct Bluetooth Remote ID. The former UDP
compatibility listener and Android forwarding path have been removed.

## Current checkpoint

- Xcode 26.6 and the iOS 26.5 Simulator runtime are installed.
- `R2CCore` decodes ASTM Bluetooth service data, including Basic ID, Location,
  System, and message packs. Synthetic parity tests cover the Android wire
  formulas and serial-number preference.
- `R2CAppleRadios.BluetoothRIDScanner` performs a foreground CoreBluetooth scan
  filtered to service UUID `FFFA`, validates application code `0D`, and emits
  normalized observations through `AsyncStream`.
- The universal SwiftUI Status screen starts Bluetooth ingest
  automatically, presents Android-style active-aircraft rows and details, and
  uses a real `CLLocationManager` fix for the MapKit user annotation plus
  device-relative range/bearing. Closest-pair horizontal/vertical/3D separation
  is visible and feeds the operator proximity-alert workflow.
- Every newly active flight now automatically opens Android's Save/Ignore
  confirmation once, including known aircraft, and clears that session state
  when the flight ends. Aircraft details retain the same confirmation fields
  for organization, pilot callsign, and drone description. The shared mapped-ID
  builder matches Android's callsign filtering and model abbreviation rules.
  Confirmations improve aircraft labels and diagnostics, propagate through
  tracker peer coordination, and are required before this device may alert.
- Android configuration interoperability now covers all three QR token families:
  organization (`R2C2`), FAA (`R2CFAA1`), and mutual aid (`R2CMA1`). The shared
  core decodes Android's shuffled alphabet and XOR envelope and parses its
  encrypted credential bundles. The app supports camera scan, pasted token, and
  custom-URL handoff; secrets go to Keychain and imported RID mappings persist.
  Organization bundles also apply embedded FAA configuration and mutual-aid
  template credentials. Cross-language vectors and bundle tests are green.
  Camera capture and a real public Drive bundle remain physical-device gates.
- The regular-width iPad hierarchy now uses the Android operator-dashboard
  shape: side-by-side incident/operational-period context, CalTopo/coordinator/
  team-drone status, prominent map/config actions, restriction status, and the
  active-aircraft list. Compact iPhone presentation retains native grouped
  navigation while preserving the same controls and workflow.
- Organization QR credentials now open a signed Android-equivalent CalTopo team
  map browser with account/folder/bookmark hierarchy, recent activity, search,
  folder navigation, and one-tap map selection; manual Map ID remains a fallback.
- Live Map enters MapKit operator-follow mode when an accurate iPad location is
  available, then yields permanently to an operator pan or focused-drone follow.
- The Status menu now opens the Android-shaped copyable build/scanner/configuration
  report instead of being a placeholder. It adds current Apple receiver,
  tracker, MediaMTX, video, and persisted QR mapping state while excluding all
  credential secrets. The same universal build has been visually qualified on
  iPhone and iPad Simulators.
- The app declares `bluetooth-central` for limited background discovery. Apple
  coalesces duplicate advertisements and slows background scans, so continuity
  remains a physical-device qualification gate rather than an assumed parity.
- The verified MediaMTX fork builds as a module-enabled XCFramework with arm64
  device and arm64 Simulator slices.
- The Swift actor bridge starts MediaMTX in-process, receives complete log lines
  through an opaque-context C callback, and converts lifecycle lines to
  `MediaServerEvent` values.
- Simulator qualification has proven MediaMTX v1.16.2 listening on RTMP port
  1935, RTSP port 8554, and HLS port 8888 from inside the app.
- AVFoundation reconnects when the HLS playlist initially returns 404, then
  pulls 32BGRA `CVPixelBuffer` frames from the live stream.
- `Native/AnomalyCore` builds the same portable C sources used by Android and
  the standalone regression harness as arm64 device and Simulator slices.
- End-to-end Simulator qualification published `docs/PowerHouse.mp4` over
  RTMP, decoded 640 x 512 frames, analyzed every delivered frame, and returned
  a live thermal anomaly annotation. The visible operator view rendered the
  same stream with an aligned red annotation at 1,138 analyzed frames and zero
  dropped analysis frames. Overlay geometry now follows the decoded raster's
  aspect ratio. The native regression harness remained green at 4594 passed,
  0 failed.
- Apple Live View now exposes persisted Off, Color Uniqueness, and Infrared
  detector modes instead of hard-coding thermal analysis. Mode changes rebuild
  detector state on its serial worker while decoded video continues. A live
  640 x 512 H.264/LL-HLS Simulator run processed more than 2,400 Color
  Uniqueness frames with zero analysis drops; a separate Off-mode run kept the
  stream playing with zero analyzed frames and zero drops. Visible-light Color
  box quality and device thermal cost remain physical/fixture qualification gates.
- `RidTrackStore` now applies the Android waypoint fundamentals: canonical
  alphanumeric Remote IDs, duplicate suppression with three-second stationary
  keepalives, the 293.3 ft/s coordinate-jump ceiling, per-transport counts, and
  30-second active-track aging.
- The CoreBluetooth observation stream feeds that store directly. A MapKit
  operator view renders active aircraft, heading-oriented icons, colored track
  polylines, operator locations when present, and accepted/filtered counters.
- The operational map now uses an `MKMapView` host with Android-equivalent OSM
  and ArcGIS imagery contracts, optional USGS contours, disk-backed tile reuse,
  offline-only display, signed CalTopo artifact snapshots, searchable nested
  folder/item visibility with per-map persistence, orphan grouping,
  hidden-parent media handling, and per-map offline artifact snapshots. Map,
  video, split, and both primary/inset layouts persist locally. Apple 1.2 adds
  FAA-configured nearby NOTAM/TFR queries, status/age diagnostics, operator
  controls, details, severity-colored map geometry, and Android-equivalent
  one-mile FAA UAS Facility Map status with airport/class/ceiling/LAANC details.
- Apple 1.2 adds Android-shaped offline preparation for the visible viewport or
  selected CalTopo line/polygon, the Android Overview/Ops/Full Detail zoom
  presets, optional contours and USGS 1-degree GeoTIFF acquisition, estimates,
  progress/cancellation, cache size/age maintenance, and bad-tile quarantine,
  clearing, and export. Terrain sampling reads downloaded GeoTIFF pixels
  directly, then falls back to the existing point cache and USGS service.
- Confirmed aircraft now use Android-equivalent mapped designators and
  per-pilot persisted active/archive colors and bearing preference. The map
  draws the full-flight archive path thin, the recent active path thick, and
  the optional heading bearing from the aircraft to the viewport edge.
- Aircraft overlays now use Android's two-line telemetry label format,
  collision-avoidance candidate order, displaced-label leaders, focused-drone
  selection/follow behavior, and bounded predictive-head projection. The
  Apple altitude coordinator preserves ASTM takeoff/ground-relative height,
  applies Android's six-sample takeoff-reference convergence, samples and
  caches USGS terrain, marks stale terrain with `?`, computes ATO/AGL/range,
  supports manual 50-foot calibration, and applies Android's 180/200-foot
  yellow/red aircraft tint thresholds.
- The map/video layouts now expose decoded-frame clue capture without relying
  on the visible `AVPlayer` view. The submission sheet includes image preview,
  aircraft selection, drone/clue telemetry, gimbal projection, title, and
  description. Every accepted clue is written locally first under
  `Documents/RID2Caltopo/Clues`, including a thumbnail and durable upload
  state; local clues render as camera markers and can be opened, shared,
  retried, or deleted. CalTopo submission follows Android's signed four-step
  Marker/media/data/MapMediaObject contract with stable IDs and persistent
  exponential retry. A credentialed live-map photo upload remains a physical
  device/test-map qualification gate.
- Synthetic two-aircraft route qualification passed on both the iPad Pro
  11-inch and iPhone 17 Simulators using the same live tracking path.
- Inactive or explicitly archived tracks are written to the app's Documents
  container using Android `WaypointTrack`'s GeoJSON envelope, coordinate order,
  timestamp units, and `r2c_prop` identity/incident fields. Simulator runtime
  qualification produced two readable `FeatureCollection` files, each with
  the expected three route points.
- The shared CalTopo client reproduces Android's base64-secret HMAC-SHA256
  signing contract, signed `LiveTrack` creation payload, and public drone
  position-report query including elevation, ground speed, and track heading.
  Credentials are configured in-app, the secret is stored in Keychain, and
  publishing remains off until explicitly enabled. Request-shape tests are
  green. Inactive aircraft also drive a signed `LiveTrack/{id}` stop after the
  local archive succeeds; a real CalTopo test-map transaction remains a
  credentialed device gate.
- Wi-Fi Beacon/NAN field qualification now uses the DS110 wireless relay into
  Apple's Bluetooth intake. The UDP compatibility listener and Android sender
  are deliberately absent.
- Tracker peer coordination now matches Android's `/ws/r2c` protocol and
  `X-SAR-Token` authentication. The Apple adapter maintains hello/heartbeat
  health, reconnects with bounded backoff, replays active sightings and pending
  confirmations, rejects stale leases, and exposes live peer/link state.
- CalTopo publication is gated by both the current local ownership lease and a
  local Save confirmation. Accepted points wait through Android's two-second
  handoff delay and publish in order; non-owner sightings relay to the owner.
  A two-zone iPhone/iPad Simulator run against the real local tracker proved
  authenticated links, peer discovery, ownership transfer after confirmation,
  negative alert eligibility on the non-owner, and bidirectional sighting relay.
  Physical-device testing with real aircraft and a credentialed CalTopo map
  remains required.
- The shared proximity engine now applies Android's imported spacing threshold,
  team-drone and local-ownership eligibility rule, approach/crossing gate,
  high-severity classification, suspension/resume state, and three-second clear
  delay. SwiftUI presents a warning card with Map/Suspend controls and visible
  Resume action, plus speech and haptic feedback. A tracker-backed Simulator run
  crossed the 40 ft threshold at 28 ft and emitted the alert only after local
  Save made the owned drone eligible.
- Apple 1.2 also applies Android's learned-cadence telemetry-loss gate to locally
  owned confirmed flights and its 180/200-foot AGL caution/over-limit policy.
  Both provide visible Map/Mute controls, speech, haptics, cooldowns, and logs.
  Android's controller-strength alert cannot be reproduced because public iOS
  APIs do not expose the current Wi-Fi RSSI percentage.
- Apple 1.2 implements operational NOTAM/TFR retrieval through the imported FAA
  NMS OAuth configuration, retained-result error handling, refresh/age
  diagnostics, operator visibility controls, restriction details, and
  point/line/polygon map overlays. It also matches Android's one-mile FAA UAS
  Facility Map query and operator status/details. Credentialed NOTAM and live
  facility-map physical-location qualification remains required.
- MediaMTX publisher events now feed a four-stream admission registry matching
  Android's capacity, controller-profile path, publisher-identity, and stale
  connecting/error rules. Operators can focus a stream or use a live grid.
  App-managed external-display scenes offer streams, map, split, and observer
  layouts with phone/external/both alert routing; attached iOS display scenes
  may be system-designated noninteractive.
- Apple 1.2 adds passphrase-encrypted local configuration backup/restore and
  Android-compatible mutual-aid ZIP export/import. Packages contain the
  encrypted incident profile plus cached tiles and DEM data for the chosen
  viewport/preset, enforce expiration and archive-path safety, and import into
  the same Apple caches used by the operational map. Encrypted iCloud Drive
  backup/restore and optional automatic debounced updates cover Apple's native
  archive path; Files continues to provide local/iCloud/installed-provider
  transfer. Provider-specific Google Drive account sync is intentionally not
  required for Apple field use.
- Apple 1.2 adds one-at-a-time captured-video review with replacement-safe
  staging, Back/Run/Pause/Step/scrub, pause-on-open, local detector overlays,
  verdict summaries, clearing, paused-frame annotations, and durable/exportable
  Android schema-v2 sidecars including anomaly box/debug fields.
- Apple 1.2 exposes the portable detector's advanced operational controls,
  including target-color families, motion/saliency, sensitivity, scan/cadence,
  registration, thermal, color candidate, and diagnostic display settings.
- Predictive Head now mirrors Android's two-sample projection, including its
  one-foot movement gate and two-second cap for horizontal and vertical motion.
  The Android QR setting is imported, persisted, and exposed in Apple Settings.
  A tracker-backed Simulator run proved the reported pair remained 56 ft apart
  while the projected separation reached 28 ft and triggered the 40 ft warning;
  stopping the synthetic motion removed the forecast and cleared the alert.
- The paid Apple team is configured and Xcode automatic distribution signing
  has produced a locally verified, device-free TestFlight IPA. The release gate
  now checks the public cross-platform privacy policy and App Store metadata
  limits. A repeatable Release-build capture script produced visually reviewed
  6.9-inch iPhone and 13-inch iPad Nearby Aircraft, Live Map, and Status images
  at Apple's required pixel dimensions. App Store Connect version 1.0 exists as
  Apple ID `6792518823`; no build has been uploaded. Upload remains intentionally
  explicit and release remains gated by `RELEASE_ACCEPTANCE.md`.

## M1 iPad hardware gate

1. Connect the 2021 11-inch iPad Pro, trust the Mac, and enable Developer Mode
   when iPadOS requests it.
2. Select an Apple Development team and a unique bundle identifier in Xcode.
3. Run `RID2CaltopoApple` on the iPad and approve Bluetooth, Local Network, and
   Location permissions.
4. Scan one organization QR generated by Android. Verify organization, incident,
   operational period, tracker settings, CalTopo credentials, and team RID
   mappings, then separately exercise FAA and mutual-aid QR payloads if used.
5. Start the Bluetooth scan beside a known-good ASTM Remote ID transmitter.
   Confirm the UI reports `Scanning`, then capture Basic ID and Location updates
   from the same aircraft using the Android device as a side-by-side reference.
6. Start MediaMTX and verify the controller can reach the iPad's Wi-Fi address
   on port 1935. Confirm local HLS playback and decoded/analyzed frame counters.
7. Compare anomaly boxes against Android on the same saved and live streams,
   then record sustained CPU, memory, battery, and thermal behavior.
8. Join the same tracker map from Android and Apple devices. Confirm peer count,
   Save-driven ownership handoff, non-owner relay, reconnect recovery, and that
   only the locally confirmed owner publishes to the CalTopo test map.
9. Fly a controlled two-aircraft spacing exercise. Verify speech/haptic warning,
   Map, Suspend, Resume, three-second clearing, and that a non-owner Apple device
   never produces the alert.

## Simulator versus device

The Simulator is the fast gate for SwiftUI, shared policy, persistence, and
synthetic stream tests. The 2021 M1 iPad Pro is the primary device gate for
Bluetooth advertisements, controller networking, sustained VideoToolbox
decode, thermal behavior, and anomaly-detector performance.

## Source ownership

- `Sources/R2CCore`: portable Swift models and policy.
- `App`: SwiftUI application shell.
- `Sources/R2CAppleRadios`: CoreBluetooth and Apple networking adapters.
- `Native/AnomalyCore`: Apple facade and repeatable XCFramework build over the
  existing portable C sources.
- `Native/MediaMTX`: mobile bridge and repeatable XCFramework build; the
  authoritative Go source remains the verified MediaMTX tree.
