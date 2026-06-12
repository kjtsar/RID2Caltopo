# Local Drone Display Preferences Design

## Summary

Add local MapPane display controls for drone track colors, bearing-to-edge overlays, and the compact drone status label. These controls are operator-facing display preferences only. They do not change CalTopo live tracks, CalTopo archived shapes, local KMZ archives, tracker uploads, or any shared/team publication behavior in this first pass.

## Goals

- Let an operator assign distinct local active and full-flight track colors for a pilot.
- Key persisted color preferences by Pilot Callsign, represented today by `CtDroneSpec.owner`.
- Let an operator toggle a local bearing-to-edge overlay for a drone/pilot.
- Make the compact drone status label explicit: `ATO:<val>' AGL:<val>' RNG:<val>' HDG:<val>°`.
- Preserve the existing default active/recent blue and full-flight/archive magenta colors.

## Non-Goals

- Do not propagate custom colors to CalTopo.
- Do not change archived CalTopo track styles or local KMZ track style.
- Do not add tracker protocol fields for display preferences.
- Do not auto-assign palettes in this first pass.

## User Flow

When an operator taps a drone marker, MapPane keeps the existing focus behavior and also exposes local display settings for that drone's pilot:

- Active track color, default `#1E88E5`; tapping the value opens a color picker.
- Archive/full-flight track color, default `#FF00FF`; tapping the value opens a color picker.
- Bearing toggle, default Off.
- Reset to defaults.

Preferences are saved locally once the drone has a non-blank Pilot Callsign. If the callsign is blank or the drone is still unknown, MapPane uses defaults and avoids creating a persisted preference. If the drone is later confirmed and gets a Pilot Callsign, the current defaults remain until the operator chooses a pilot preference.

Because preferences are keyed by Pilot Callsign, changing either color for a pilot updates every visible active and full-flight track associated with that pilot, not only the marker that opened the settings UI.

## Persistence Key

Use a normalized Pilot Callsign key:

- Source: `CtDroneSpec.getOwner()`.
- Trim whitespace.
- Normalize to uppercase with `Locale.US`.
- Treat blank as not persistable.

This intentionally makes the preference pilot-specific rather than aircraft-specific. The same pilot can swap aircraft and keep the same local display colors.

## Local Track Colors

MapPane currently maintains two local track buckets:

- Recent/active local points in `localTrackPointsByMappedId`.
- Confirmed current-flight/full-flight points in `currentFlightTrackPointsByMappedId`.

The implementation should resolve each mapped ID to the active drone point, then to that drone's normalized Pilot Callsign. If a persisted preference exists:

- Draw the recent/active line using the pilot's active color.
- Draw the confirmed full-flight line using the pilot's archive/full-flight color.

If no preference exists, keep the current default colors:

- Active/recent: `#1E88E5`.
- Full-flight/archive: `#FF00FF`.

When a pilot preference changes, MapPane should invalidate and redraw all local track overlays for that Pilot Callsign so older visible segments, current active segments, and full-flight segments all switch colors together.

The preference is local-only. CalTopo publication continues to use the existing live-track and archive styles.

## Bearing Overlay

When Bearing is On for a pilot and the drone has a finite heading:

- Draw a line from the rendered drone position forward along the heading until it reaches the visible MapPane viewport edge.
- Draw a compact heading label such as `HDG:273°` or `273°` near the drone-side portion of the line, whichever fits better with the final label layout.
- Keep the existing small icon heading stub. The new long line is the map-scale bearing cue.

When heading is missing, non-finite, or stale enough that the display state withholds heading, suppress the line and label while leaving the toggle On. The overlay should return automatically once valid heading data is available.

The bearing line is local-only and should not create CalTopo objects.

## Drone Status Label

Replace the current compact status label tokens with:

`ATO:<val>' AGL:<val>' RNG:<val>' HDG:<val>°`

Rules:

- `ATO` uses the existing ATO feet value.
- `AGL` uses the existing AGL feet value and preserves the stale marker behavior if applicable.
- `RNG` uses the existing distance-from-takeoff/range value.
- `HDG` uses the existing heading display value.
- Missing values render as `--`, for example `HDG:--°`.
- Keep the label compact enough for the existing decluttering logic; if the full string is too wide, prefer wrapping or a two-part label over truncating critical values.

Using `RNG` makes the range value explicit and avoids an unlabeled distance number.

## Data Flow

1. MapPane builds `DroneMapPoint` entries from the current drone display state.
2. Each point carries `droneSpec`, `remoteId`, `designator`, and heading data already available to the marker renderer.
3. MapPane resolves `droneSpec.getOwner()` to a normalized preference key.
4. Local display preferences are loaded from app-local persistence.
5. Track overlays, marker settings UI, bearing overlays, and status labels render from the combined drone state plus local preference state.

## Error Handling

- Invalid or unparsable color values fall back to defaults and should not crash MapPane.
- Blank callsigns are treated as non-persistable.
- Canceling the color picker leaves the previous color unchanged.
- Missing headings suppress bearing overlays rather than drawing stale or misleading lines.
- Preference storage failures should log a warning and leave the current in-memory choice active for the session if possible.

## Testing

Add focused tests for:

- Pilot Callsign normalization and blank fallback.
- Default active and full-flight colors when no preference exists.
- Custom active and full-flight colors applied to local MapPane track overlays.
- Changing a Pilot Callsign preference updates all visible tracks associated with that pilot.
- Color picker cancellation preserves the previous color.
- Preferences surviving an app restart through the chosen local persistence layer.
- Bearing line endpoint calculation for north, east, south, west, and diagonal headings.
- Missing heading suppressing bearing overlay output.
- Drone status label formatting for present and missing ATO, AGL, RNG, and HDG values.

## Acceptance Criteria

- A user can tap a drone marker and set local active/full-flight colors for that pilot.
- Color values are chosen through a color picker rather than raw text entry.
- The same Pilot Callsign reuses its saved local colors across app restarts.
- Changing a pilot's colors updates all local MapPane tracks associated with that pilot.
- Custom colors affect only local MapPane active and full-flight overlays.
- CalTopo live tracks, CalTopo archived shapes, KMZ archives, and tracker uploads remain unchanged.
- Bearing On draws a heading-to-edge local overlay when heading data is valid.
- The compact drone status label shows `ATO`, `AGL`, `RNG`, and `HDG` labels with units.
