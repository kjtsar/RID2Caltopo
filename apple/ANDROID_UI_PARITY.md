# Android / Apple Operator UI Parity

This is the working UI contract for the Apple port. Android remains the
reference unless an iOS platform restriction or a documented native convention
requires a different presentation. A native sheet or menu is acceptable; a
different label, default, placement, gesture, or state transition is not.

## Current parity

| Area | Android contract | Apple status |
| --- | --- | --- |
| Main Screen | Incident / operational period header, restriction status, aircraft table, confirmation flow with persistent field labels | Implemented. The Apple table includes BT4, BT5, WiFi, NaN, R2C, Total, duration, and RTT columns; drone confirmation fields remain visibly named while populated or edited. |
| Startup | Standalone, Training / Op 1; credentials remain available but no incident is restored | Implemented. |
| Live View navigation | Stream, map, split, and PiP without an unsolicited layout change when a stream arrives | Implemented. |
| Stream focus | One stream fills its pane; tap focuses/restores grid; long press pairs telemetry or resizes PiP | Implemented. |
| Stream grid | One column for two streams, 2 x 2 for three or four, no scrolling | Implemented. |
| Stream chrome | Designator, measured lag, telemetry, selectable Decimal/UTM/USNG coordinates, pairing state, focused-tile settings | Implemented. |
| AD lifecycle | Starts Off with canonical realtime defaults; mode changes select Android's fixed/adaptive defaults; adjustments last only for the process session | Implemented. |
| AD menu | Gear, `AD Mode`, `AD Help`, restart, close, troubleshooting diagnostics | Implemented; Apple also exposes native decoder performance diagnostics. |
| AD scan and ROI display | Scan-zone and small-target controls with 16:9 preview; optional cyan scan/target guides; detections retain native color, weight, rectangle/crosshair, and hottest-region ring | Implemented. Detection ROIs remain visible when guide boxes are disabled, matching Android. |
| Map controls | Layer, Predictive Head, Download Map, Map Folders, Map Management; nested Map Management and Bad Tiles items retain Android order | Implemented in the same order; Apple-only cache diagnostics follow the parity controls. |
| Map gestures | Operator pan stops automatic centering; follow focused drone is explicit; north-up remains reachable | Implemented. |
| Map items | Nested folder visibility, active Drone Tracks, one hidden same-day archive folder, and CalTopo artifacts with source opacity and CalTopo-rendered marker icons | Implemented. |
| Aircraft appearance | Active/archive track colors and bearing-line preference | Implemented. |
| Tablet marker | Unlabeled antenna, one-statute-mile ring, device-name and tracker-health callout on selection | Implemented. |
| Safety overlays | Airspace, NOTAM/TFR, protected-land state and map overlays | Implemented. |
| Video clue | Double tap captures the displayed viewport; empty focused title, Android report template/telemetry, `Local Marker Only`, and `Submit` | Implemented. |
| Quit / idle shutdown | Main Screen menu confirmation and automatic close after the configured maximum idle time | Implemented with UIKit scene destruction after orderly operational cleanup. |

## Functional UI deltas still to close

These are not presentation-only changes; Apple needs equivalent runtime state
or diagnostics before the corresponding controls can be honest.

- Android Settings exposes minimum movement distance, new-track delay, bridge
  check distance, audio alarm volume/test, editable proximity spacing, and
  standalone R2C coordination. Apple currently receives only part
  of this policy from imported organization configuration.
- Android's `Proximity Pairs` panel lists every evaluated aircraft pair and its
  horizontal, vertical, and 3D separation. Apple currently exposes the active
  warning but not the engine's complete debug-pair list.
- Android exposes Person Relevance controls backed by its bundled LiteRT model.
  Apple does not yet have an equivalent iOS inference asset/runtime, so it does
  not show a placeholder control that cannot affect detector behavior.
- Android supports captured-video playback and annotation. Apple records
  incoming streams and supports live clues, but captured playback remains a
  separate lower-priority workflow.
- Android exposes archive-folder cleanup from the Main Screen menu. Apple
  exposes app files through Files but does not yet provide the same in-app
  selective cleanup panel.

## Documented platform differences

- Apple uses iCloud Drive and Files rather than Android's Google Drive workflow.
  Organization export uploads through the enrolled r2c-tracker device credential
  and presents an R2C2 QR containing its opaque public download locator. Both
  platforms can import it; JSON sharing remains available as a backup.
  Other installed document providers remain available through Files.
- iOS does not expose the Wi-Fi RSSI Android uses for controller-strength
  percentage. Apple reports SSID, video health, and telemetry loss instead.
- iOS may suspend processing while locked or backgrounded. Bluetooth
  restoration is best effort under the operating-system policy.
- Apple closes the primary UIKit scene rather than calling `exit`; the system remains responsible for ending the process.
- Apple uses standard iOS sheets, forms, navigation, and share/file pickers
  where Android uses Compose dialogs or Android storage pickers.

## Audit rule

For every future Apple UI change, compare the Android screen first and record
any intentional exception here. Release notes must summarize both newly closed
parity gaps and the remaining operator-visible differences.
