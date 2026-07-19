# Apple version 1.0 release acceptance

Version 1.0 is a **no-go** until every required row below is green. Simulator
coverage is evidence for shared logic and layout, but it is not a substitute
for radio, camera, networking, performance, or background testing on Apple
hardware.

## Automated and Simulator gate

| Capability | Android-aligned operator contract | Current evidence | Status |
| --- | --- | --- | --- |
| Configuration import | Import Android `R2C1`, `R2CFAA1`, and `R2CMA1` QR/config payloads and preserve imported mappings and settings. | Cross-language vectors, bundle tests, paste/custom-URL flow, and Simulator UI are green. | Green except physical camera/Drive proof |
| Remote ID core | Decode ASTM Basic ID, Location, System, and Message Packs and apply Android filtering, canonical IDs, aging, and archive policy. | Shared tests and live synthetic Simulator observations are green. | Green |
| Bluetooth scanning | Start automatically, expose scanner state and aircraft details, and produce Android-shaped diagnostics. | CoreBluetooth implementation and UI are built and Simulator-safe. Real advertisements and background behavior cannot be proven in Simulator. | Physical gate |
| Wi-Fi Remote ID | Preserve Wi-Fi Beacon/NAN observations through the same tracking pipeline. | Public iOS cannot passively monitor ASTM NAN. The opt-in Android UDP relay and binary/JSON receiver pass contract and Simulator loopback tests. | Physical Android-to-Apple gate |
| Map and tracks | Show aircraft/operator positions, heading, trails, range/bearing, closest-pair separation, and Android-compatible GeoJSON archives. | iPhone/iPad Simulator route and archive proofs are green. | Green except physical GPS accuracy |
| Tracker/CalTopo | Match `/ws/r2c` ownership/relay behavior, require local Save, and publish/stop Android-shaped CalTopo tracks. | Two-zone tracker Simulator run and signed-request tests are green. | Credentialed physical gate |
| Proximity alerts | Match threshold, ownership/team eligibility, predictive head, speech/haptic, Map, Suspend, Resume, and delayed clear behavior. | Shared tests and tracker-backed Simulator exercises are green. | Physical controlled-spacing gate |
| Video/MediaMTX | Run MediaMTX in-process, accept controller RTMP, and render local HLS video with reconnect behavior. | Native ABI, Simulator RTMP/HLS loopback, and signed arm64 packaging are green. | Physical network/thermal gate |
| Anomaly detector | Offer persisted Off, Color, and Infrared modes; keep video alive when Off; align overlays and bound analysis drops. | Native regression/realtime suites and sustained Simulator runs are green. | Physical quality/performance gate |
| Logs and status | Provide Android-like Status, copyable diagnostics, daily log selection/package/share, and exclude credential secrets. | Swift tests and visually reviewed iPhone/iPad Simulator UI are green. | Green except physical share-sheet proof |
| Signing/package | Universal iPhone/iPad arm64 app, privacy manifest, release entitlements, and App Store profile. | Verified distribution-signed IPA and nine-stage release gate are green. | Green |

## Required physical-device run

Use a supported iPhone or iPad with an Android comparison device and retain the
resulting diagnostic bundle, screenshots, and test notes. Complete a copy of
`DEVICE_QUALIFICATION_REPORT.md` for each Apple device and exact TestFlight
build; an untested or unevidenced required row remains a no-go.

1. Install a Release/TestFlight build and accept Bluetooth, Local Network,
   Location, and Camera permissions.
2. Scan Android-generated organization, FAA, and mutual-aid QR payloads as
   applicable; include one real public Drive-hosted bundle.
3. Compare a known ASTM Bluetooth transmitter side-by-side with Android,
   including foreground updates, lock/background behavior, and recovery.
4. Relay Android Wi-Fi Beacon/NAN observations over the shared access point and
   compare IDs, positions, transport labels, and rejected-datagram diagnostics.
5. Verify physical GPS position, range/bearing, MapKit layout, track completion,
   Files export, and the system log-sharing sheet.
6. Publish a controller stream to the displayed RTMP address. Exercise Off,
   Color, and Infrared against representative visible and thermal fixtures and
   record frame counts, drops, CPU, memory, battery, and thermal state.
7. Join the real tracker and a disposable CalTopo test map with Android and
   Apple devices. Exercise ownership handoff, non-owner relay, reconnect, Save,
   ordered publication, and track stop.
8. Run a controlled two-aircraft spacing exercise and verify speech, haptic,
   Map, Suspend, Resume, predictive head, delayed clear, and non-owner silence.
9. Inspect every primary screen in portrait and landscape at the supported text
   sizes. Confirm Android-equivalent control names and workflow order remain
   recognizable while native iPhone/iPad navigation remains usable.

## Store release gate

Before upload or release:

1. Run `apple/release-check.sh` from a cleanly understood worktree.
2. Publish the reviewed `PrivacyPolicy.md` and verify the public URL.
3. Complete category, age rating, App Privacy, review contact/instructions,
   screenshots, territories, price, export compliance, and DSA status in App
   Store Connect.
4. Upload a newly numbered build and let App Store Connect finish processing.
5. Run the physical-device matrix above using that exact build through
   TestFlight.
6. Submit for App Review only after all evidence is green. Release publicly
   only after approval and one final smoke test of the approved build.
