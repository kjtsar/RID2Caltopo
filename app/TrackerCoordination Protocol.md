# Tracker Coordination Protocol

RID2Caltopo can coordinate distributed `r2c zones` through `tracker.kjt.us`
using a single outbound websocket per zone. This avoids the VPN/full-mesh peer
requirement while keeping the owner-side waypoint sequencing in the Android app.

## Roles

- `zone`: one RID2Caltopo instance plus its current location
- `owner`: the zone currently responsible for sequencing a drone's waypoint stream
- `tracker`: public rendezvous service used for zone presence, owner leases, and sighting relay

## Transport

- Websocket endpoint: `/<organization-designator>/ws/r2c`
- Authentication: `X-SAR-Token`
- URL source: existing tracker upload prefix in app config, converted from `http(s)` to `ws(s)`

## Client -> Tracker

### `hello`

Sent when the websocket opens.

```json
{
  "type": "hello",
  "mapId": "ABC-123",
  "incidentId": "ABC-123",
  "zoneId": "device-guid",
  "guid": "device-guid",
  "name": "Alpha",
  "lat": 39.123,
  "lng": -121.456,
  "appVersion": "1.2.5(59)",
  "appVersionCode": 59,
  "caltopoRttMs": 750
}
```

### `heartbeat`

Backup lease signal while an owner is connected but not producing accepted
owner telemetry. Standby clients with no active drones, owner leases, queued
first sightings, or pending Save confirmations can park the websocket instead of
heartbeating forever.

### `first_sighting`

Sent the first time a live track is created locally for a drone.

### `sighting`

Sent for accepted locally-observed waypoints after the first sighting. Non-owner
instances throttle these relays per drone, currently to one update every three
seconds by default. Owner-originated sightings refresh the owner lease; peer
relays do not.

### `drone_lost`

Sent when the local track finishes or the drone is no longer active.

### `drone_confirmed`

Sent only after an operator presses Save in the Drone Confirmation panel. The
tracker broadcasts it to all connected zones on the same map so peers can
suppress any current or future confirmation panel for that `remoteId` during
the app lifecycle. A parked client wakes before flushing this event.

```json
{
  "type": "drone_confirmed",
  "mapId": "ABC-123",
  "remoteId": "DRONE1",
  "zoneId": "device-guid",
  "guid": "device-guid",
  "flightStartMsec": 1710000001000,
  "mappedId": "1SAR7DJ",
  "org": "NCSSAR",
  "model": "Mavic 3",
  "ownerName": "Pilot"
}
```

## Tracker -> Client

### `hello_ack`

Acknowledges the websocket join.

May include optional upgrade advisory fields:

```json
{
  "recommendedAppVersionCode": 77,
  "updateUrl": "https://example.org/r2c-update"
}
```

R2C compares only the numeric Android `versionCode`. The human-readable
`appVersion` string is display-only.

### `zone_update`

Broadcasts the current active zone table for the incident.

### `owner_assigned`

Declares the current owner lease for a `remoteId`.

```json
{
  "type": "owner_assigned",
  "remoteId": "DRONE1",
  "ownerGuid": "device-guid",
  "leaseSeq": 4
}
```

### `owner_expired`

Clears a previous owner lease.

### `relay_sighting`

Forwards a non-owner zone's sighting to the current owner zone. The owner
injects the waypoint into its `CaltopoLiveTrack` via `onPeerWaypoint(...)`.

### `drone_confirmed`

Broadcast of a Save decision from any zone's Drone Confirmation panel.

## Ownership

The tracker service assigns an owner using:

1. earliest first-sighting drone timestamp
2. smallest reported distance from zone
3. zone with a non-default mapped ID
4. lexical GUID tie-breaker

Leases are sticky while healthy. RID2Caltopo falls back to local ownership if
the tracker websocket is unavailable past the short connect grace window.
If the tracker service restarts or the websocket drops, RID2Caltopo now retries
the websocket connection automatically and replays pending first-sighting state
after reconnect. Owner assignments, owner expirations, and Drone Confirmation
Panel Save events remain tracker broadcasts.
