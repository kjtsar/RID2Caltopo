# RID2Caltopo Apple device qualification report

Complete one copy per Apple device and exact TestFlight build. A release is a
no-go if a required row is untested, has no retained evidence, or fails without
an accepted fix and retest.

## Test identity

- Tester:
- Date/time and time zone:
- TestFlight build (`Settings > Status`):
- App version:
- Apple device model / storage:
- iOS/iPadOS version:
- Android comparison device / RID2Caltopo version:
- Network/access point and client-isolation setting:
- Remote ID transmitter(s):
- Controller/video source:
- Tracker incident and disposable CalTopo map identifiers:

## Required results

Use `PASS`, `FAIL`, or `BLOCKED`. Record the relevant screenshot, video, log
bundle, GeoJSON file, or measurement in Evidence.

| Area | Required observation | Result | Evidence / measurements / notes |
| --- | --- | --- | --- |
| Install and permissions | TestFlight install succeeds; Bluetooth, Local Network, Location, and Camera prompts are understandable and the accepted state appears in Status. |  |  |
| Organization QR | Android-produced `R2C1` scans by camera and imports the expected organization, incident, operational period, and mappings. |  |  |
| FAA QR | Android-produced `R2CFAA1` scans and imports the expected FAA configuration or records why it is not applicable. |  |  |
| Mutual-aid QR | Android-produced `R2CMA1` scans and imports the expected mutual-aid configuration or records why it is not applicable. |  |  |
| Drive bundle | One real public Drive-hosted configuration bundle imports without exposing its token in Status or shared logs. |  |  |
| Bluetooth RID | Beside Android, a known ASTM transmitter shows the same canonical ID, position, altitude, transport, and expected update/expiry behavior. |  |  |
| Background RID | Record aircraft update behavior before lock, during a two-minute lock, after unlock, and after returning to foreground. |  |  |
| Wi-Fi RID relay | Android Beacon/NAN observations relayed over UDP appear with matching IDs, positions, and transport labels; malformed datagrams are rejected and counted. |  |  |
| GPS and map | Operator position, range/bearing, heading, trail, closest-pair separation, portrait/landscape layout, and supported text sizes are usable. |  |  |
| Track archive | Completing a track creates Android-compatible GeoJSON that opens through Files and contains expected coordinates and timestamps. |  |  |
| Tracker archive upload | An eligible mapped team track uploads to the configured tracker; a forced transient failure remains pending and uploads after relaunch. Foreign, unknown, and local-only tracks remain local. |  |  |
| Tracker coordination | Two devices report peer presence; ownership, Save, non-owner relay/silence, handoff, disconnect, and reconnect behave as documented. |  |  |
| CalTopo publication | Owner publishes ordered points to the disposable map; non-owner does not duplicate; completion stops the track. |  |  |
| Proximity alert | Controlled spacing verifies speech, haptic, Map, Suspend, Resume, predictive head on/off, delayed clear, and non-owner silence. |  |  |
| MediaMTX ingest | Controller publishes to the displayed `rtmp://<address>:1935/demo`; video renders, reconnects, and survives detector Off. |  |  |
| Color detector | Representative visible fixture produces aligned yellow boxes; record analyzed/dropped frames and false positives. |  |  |
| Infrared detector | Representative thermal fixture produces aligned red boxes; record analyzed/dropped frames and false positives. |  |  |
| Sustained video | Run at least 20 minutes; record CPU, memory, battery change, highest thermal state, stream interruptions, analyzed frames, and dropped frames. |  |  |
| Status and logs | Copyable Status is useful and contains no QR token, tracker API key, or CalTopo secret. Daily log packaging and the system share sheet succeed. |  |  |
| UI parity | Nearby Aircraft, aircraft details, Live Map, Live View, Status, Settings, configuration import, confirmation, and log sharing remain recognizable beside Android. |  |  |

## Measurements

- Bluetooth observation interval, foreground:
- Bluetooth observations during two-minute lock:
- Time to recover after foregrounding:
- Wi-Fi relay accepted / rejected datagrams:
- Video run duration:
- Stream reconnect count and longest interruption:
- Color analyzed / dropped frames:
- Infrared analyzed / dropped frames:
- Peak CPU / memory:
- Battery start / finish:
- Highest thermal state:

## Retained evidence

- RID2Caltopo log bundle:
- Status copy:
- Screenshots or screen recording:
- Exported GeoJSON:
- CalTopo test-map evidence:
- Android comparison log/version:
- Crash or App Store Connect diagnostic identifier, if any:

## Tester conclusion

- Overall result (`PASS`, `FAIL`, or `BLOCKED`):
- Release-blocking issues:
- Non-blocking differences from Android:
- Retest required:
- Tester name and date:
