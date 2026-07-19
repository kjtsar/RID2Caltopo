# RID2Caltopo privacy policy

RID2Caltopo is an incident-support application. It does not contain advertising,
does not track users across apps or websites, and does not sell personal data.

## Data used on the device

- Bluetooth and optional external-network Remote ID observations are decoded to
  display nearby aircraft and create track archives.
- Location permission is used to show the operator relative to aircraft on the
  map.
- Local Network permission is used to receive controller video and optional
  external Remote ID observations.
- CalTopo credentials are stored in Apple Keychain. The credential secret is
  not written to diagnostic logs or diagnostic bundles.
- When tracker peer coordination is configured, the app uses a random stable
  app-install zone identifier and the device zone name to coordinate ownership
  with other RID2Caltopo instances.

## Network services

CalTopo publishing is disabled until the operator configures and enables it.
When enabled, aircraft positions and associated telemetry are sent to the
operator-selected CalTopo map. Apple MapKit may contact Apple's map services to
load map content.

When enabled by imported organization configuration, tracker peer coordination
sends the app-install zone identifier, device zone name, operator position,
confirmed drone identity, and aircraft sightings to the configured tracker.
This prevents multiple field devices from independently publishing or warning
for the same aircraft. RID2Caltopo does not use this information for advertising
or tracking across apps or websites and does not include advertising or
analytics SDKs.

## Files and diagnostic sharing

Track archives and diagnostic logs remain in the app's container and are also
available through the Files app. The operator can explicitly select log days,
create a diagnostic bundle, and choose a recipient through the iOS share sheet.
The bundle may contain Remote IDs, app events, device/OS details, and local
network addresses. Nothing is transmitted by this feature until the operator
chooses a share destination.

## Retention and deletion

Deleting files in the Files app or deleting RID2Caltopo removes locally stored
archives and logs. A diagnostic recipient controls retention of a bundle after
the operator shares it. CalTopo controls retention of data deliberately
published to a CalTopo map. The configured tracker operator controls retention
of coordination data. Deleting the app removes its local app-install zone
identifier.

Questions about this policy or deletion of a diagnostic bundle can be sent to
`kjtsar@kjt.us`.
