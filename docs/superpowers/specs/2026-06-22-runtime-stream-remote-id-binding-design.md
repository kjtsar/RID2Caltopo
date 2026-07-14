# Stream Remote ID Binding

## Problem

Manual stream-to-drone pairing currently renames the drone by calling `CtDroneSpec.setMappedId(streamDesignator)`. That makes the controller's RTMP path act as the drone's mapped ID. In the June 21 logs, Jennifer successfully changed the Matrice pilot callsign to `1SAR138`, but a later stream pairing to `1SAR83Mtrc4TD` changed the active track back to `1SAR83Mtrc4TD`.

Controller stream designators are operationally awkward to change and may become generic labels such as `NCSSAR_MTRC4TD-1`. They should identify the video source, not the pilot callsign or archived track identity.

## Goals

- Bind each live RTMP stream designator to telemetry by Remote ID, not mapped ID.
- Use the `mappedId` value from the persisted RID map as the default controller stream designator for a Remote ID.
- Keep manual mismatched pairings runtime-only for the current app process.
- Let pilot callsign / mapped ID edits change active tracks and archive identity without being overwritten by stream pairing.
- Show the controller stream designator in the top-left StreamTile identity label while the stream is red or yellow.
- Show the paired drone's current mapped ID in the top-left StreamTile identity label once the stream is green.
- Keep the controller stream designator available as the internal stream key and for diagnostics.

## Non-Goals

- Do not persist ad hoc "Pair Anyway" mappings.
- Do not change controller-side RTMP naming.
- Do not change tracker ownership or drone-confirmation persistence.
- Do not learn new controller designator mappings from pilot callsign edits.

## Runtime Model

`StreamsViewModel` resolves stream telemetry pairing from two sources:

```text
configured default: rid_map mappedId -> remoteId
runtime override:   streamDesignator -> remoteId
```

The configured default is global to the tablet and independent of map/incident. It comes from the persisted RID mapping cache loaded from `rid_map.json`. Confirmation-panel callsign changes update the active drone's mapped ID for the current flight/run, but they do not rewrite the configured stream default.

The runtime override table is for operator-selected mismatches such as "Pair Anyway". It lasts only for the current app process and must not be written back to the persisted RID map.

The stream designator remains the key for video runtime state: MediaMTX path, stream registry entries, focus, render sessions, anomaly settings, and clue-capture stream source. The Remote ID binding resolves which telemetry/drone state is paired with that stream.

The mapped ID remains the drone identity label and may change through the confirmation flow. Because the stream binding points to Remote ID, the paired StreamTile follows the latest mapped ID without changing the controller stream designator or the binding.

The configured default and runtime override both survive flight turnover during the same app run. If stream `NCSSAR_MTRC4TD` is configured for Remote ID `1581F8...`, the next flight from that Remote ID should automatically make the stream green again when telemetry arrives, even if the pilot later changes the mapped ID in the Drone Confirmation Panel.

## User Flow

When a stream is red, it has video but no available telemetry candidate; the primary StreamTile identity label is the controller stream designator. When a stream is yellow, it has telemetry candidates but no binding; the primary label is still the controller stream designator.

When a stream is yellow/unpaired, long-press opens the telemetry picker. Selecting a drone stores `streamDesignator -> remoteId` only when this is a runtime override. It does not call `setMappedId()` and does not rename an active track.

If the selected Remote ID has a configured default designator that differs from the stream designator, the app warns the operator and recommends changing the controller RTMP stream designator to the configured value. Choosing "Pair Anyway" stores only a runtime override for this app invocation.

When a stream is green/paired, the visible StreamTile identity label should use the paired drone's current mapped ID. If the controller path is `NCSSAR_MTRC4TD` and the paired drone is currently mapped to `1SAR138DjMtrc4td`, the tile's primary identity label is `1SAR138DjMtrc4td`.

Unmatch/remap clears or replaces only the runtime override. It does not clear the drone's mapped ID or the configured RID-map default.

If the controller stream designator changes during the same app run, it is treated as a new stream and must be paired again.

Sequencing is flexible. Video may arrive before telemetry, or telemetry may arrive before video. Pairing should resolve when both sides are present:

- If a stream has a runtime override, telemetry for that Remote ID makes the stream green.
- If no runtime override exists, a configured default whose RID-map mapped ID equals the controller stream designator pairs that stream to the configured Remote ID.
- After a stream is bound to Remote ID, later mapped ID changes do not break the binding.

## Data Flow

1. `StreamRegistry` reports stream paths by controller designator.
2. `StreamsViewModel.designatorStateFor(streamDesignator)` checks the runtime override, then the configured RID-map default.
3. If either source resolves a Remote ID with current telemetry, it finds the current `DroneSpecState` by Remote ID and returns a green state for that drone.
4. If unbound with available telemetry candidates, it returns yellow.
5. If unbound without telemetry candidates, it returns red.
6. `StreamTile` displays the controller stream designator for red/yellow and the green state's current mapped ID for green.
7. "Pair Anyway" writes only the runtime override table.

## Testing

Add focused JVM tests around the pure/stateful ViewModel seams:

- Pairing stream `NCSSAR_MTRC4TD-1` to Remote ID `1581F8...` makes `designatorStateFor("NCSSAR_MTRC4TD-1")` green.
- Pairing does not mutate the selected `CtDroneSpec.mappedId`.
- After the selected drone's mapped ID changes to `1SAR138DjMtrc4td`, the paired stream still resolves green and exposes `1SAR138DjMtrc4td` as the tile display identity.
- Clearing the binding makes the stream yellow/unpaired without clearing the drone's mapped ID.
- Red/yellow streams expose the controller stream designator as the primary tile identity label.
- A stream with a configured RID-map default automatically becomes green when telemetry for that Remote ID appears on a later flight.
- A stream with an existing runtime override automatically becomes green when telemetry for that Remote ID appears on a later flight.
- Selecting a Remote ID whose configured controller designator differs from the current stream produces a warning.
- Choosing "Pair Anyway" creates a runtime override without changing configured defaults.
- After a default or runtime override resolves by Remote ID, a subsequent mapped ID change does not unbind the stream and does not rename the stream.

## Risks

Some existing stream UI logic assumes a stream designator and mapped ID are the same string. The implementation should keep the stream designator as the video key and introduce explicit helper methods for the paired drone state and display label, rather than spreading map lookups through UI code.
