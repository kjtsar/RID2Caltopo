# RID2Caltopo Privacy Policy

Last updated: July 19, 2026

RID2Caltopo is an incident-support application for Android, iPhone, and iPad.
It does not contain advertising or analytics SDKs, does not track people across
apps or websites, and does not sell personal data.

## Data used on the device

- Bluetooth and compatible external-network Remote ID observations are decoded
  to display nearby aircraft and create local track archives.
- Location permission is used to place the operator relative to aircraft on the
  map and support configured tracker coordination. Location is not collected
  for advertising or cross-app tracking.
- Local-network access is used for controller video, optional external Remote ID
  observations, and configured incident coordination services.
- Configuration QR codes may contain organization, incident, Remote ID mapping,
  CalTopo, tracker, FAA, and mutual-aid settings. Credential secrets are stored
  in platform-protected storage, including Apple Keychain on iPhone and iPad.
- When tracker peer coordination is configured, the app uses an app-install
  zone identifier and device zone name to coordinate aircraft ownership with
  other RID2Caltopo instances.

## Network services

CalTopo publishing is disabled until it is configured and enabled by the
operator. When enabled, aircraft positions and associated telemetry are sent to
the operator-selected CalTopo map.

When enabled by imported organization configuration, tracker peer coordination
sends the app-install zone identifier, device zone name, operator position,
confirmed drone identity, and aircraft sightings to the configured tracker.
This is used to prevent field devices from independently publishing or warning
for the same aircraft.

Apple MapKit or the configured Android map provider may contact its map service
to load map content. A configuration import may contact its specified public
file host to retrieve the operator-selected configuration bundle.

## Files and diagnostic sharing

Track archives and diagnostic logs remain in the application's local storage
unless the operator explicitly exports or shares them. On iPhone and iPad,
these files are also available through the Files app when file sharing is used.

The operator can select log days, create a diagnostic bundle, and choose a
recipient through the platform share sheet. A shared bundle may contain Remote
IDs, aircraft and operator positions, the app-install coordination identifier,
app events, device and operating-system details, local-network addresses, and
operational status. Credential secrets and configuration tokens are excluded.
Nothing is transmitted by the log sharing feature until the operator chooses a
destination.

## Retention and deletion

Locally stored archives and logs can be deleted through the application's file
storage or by deleting the app and its data. Deleting the app removes its local
app-install zone identifier. A diagnostic recipient controls retention after a
bundle is shared. CalTopo and the configured tracker operator control retention
of data deliberately sent to those services.

## Contact

Questions about this policy or deletion of a diagnostic bundle can be sent to
[kjtsar@kjt.us](mailto:kjtsar@kjt.us).
