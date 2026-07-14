# Stream Picture-in-Picture Awareness Design

## Goal

Add an optional Picture-in-Picture mode for the Streams screen so operators can keep awareness of a focused drone's map context while the focused video stream remains the primary work surface.

## Scope

This design covers the first implementation of PiP inside `StreamsScreen`. It does not replace Android system Picture-in-Picture and does not change the ffmpeg video decode path.

## User-Facing Behavior

Add a `PiP: On/Off` toggle in the Streams top bar near the existing `Split` button. When PiP is off, the current `Streams`, `Map`, and `Split` modes behave as they do today.

When PiP is on and a single focused video stream is dominant, show a small map inset in the bottom-right corner of the video area. The inset map is an awareness instrument, not a full MapPane:

- It is view-only for normal use.
- Single tap promotes the MapPane to the dominant view.
- Long press enters PiP editor mode.
- In editor mode, a small top-left handle can be dragged to resize the inset.
- The saved inset size applies regardless of whether the inset currently contains the map or video.

When PiP is on and the MapPane is dominant, show the focused video stream as a bottom-right inset. The dominant MapPane keeps normal full MapPane interactions. The video inset is passive except for single tap to return to the stream-dominant view and long press to enter editor mode.

## Map Inset Follow Mode

When the focused stream is associated with a drone telemetry feed, the map inset should follow that drone automatically:

- Keep the focused drone centered in the inset map.
- Recenter at a throttled cadence, initially about 2 Hz.
- Coalesce faster telemetry updates and use the latest known position.
- If drone telemetry updates slower than 2 Hz, move only when a fresh position is available.
- If no usable focused-drone position exists, fall back to the last full MapPane viewport.

The video stream remains rendered by the existing focused ffmpeg path. The map inset follow cadence must not be tied to video frame rate.

## Compact Map Presentation

The map inset should use a compact presentation mode so map symbols do not dominate the small panel:

- Smaller drone icons.
- Smaller clue/photo markers.
- Thinner tracks and bearing lines.
- Reduced or hidden labels where needed.
- No popup panels, dialogs, offline prep controls, or heavyweight MapPane tool surfaces.

This should reuse MapPane rendering where practical, with an explicit presentation mode such as `Full` versus `Inset`, rather than creating a separate map renderer.

## Interaction Model

PiP normal mode:

- Inset single tap switches dominant content.
- Inset long press enters editor mode.
- Inset does not consume full map gestures.

PiP editor mode:

- Show a visible top-left resize handle.
- Dragging the handle changes inset size.
- Size is clamped to practical minimum and maximum bounds.
- Exiting editor mode should be obvious and low-risk, for example by tapping outside the handle or toggling PiP off.

The first implementation should avoid free map panning in normal inset mode. If inset panning is added, it should be limited to editor mode or a distinct affordance so it does not conflict with tap-to-promote.

## Persistence

Persist at least:

- PiP enabled/disabled.
- Inset size fraction.

Editor mode itself should not persist across screen recreation.

## Practical Risks

Rendering a live osmdroid MapPane inside an inset may be expensive, especially while a focused video stream is rendering at about 30 fps. The implementation should keep the inset map recenter loop throttled and should be tested on-device with a focused stream plus active drone telemetry.

If full MapPane reuse is too heavy, the fallback design is a lighter map preview that shows only drone position, track, and relevant clue/search-segment context.

## Acceptance Criteria

- PiP can be toggled on and off from the Streams top bar.
- In stream-dominant mode with one focused stream, the map inset appears at bottom right.
- In map-dominant mode, the focused video inset appears at bottom right.
- Single tapping the inset switches dominant content.
- Long pressing the inset enters editor mode.
- Dragging the top-left handle resizes the inset and the size persists.
- The map inset follows the focused drone at a throttled cadence when telemetry is available.
- The map inset uses compact symbol sizing.
- Full MapPane interactions remain available when the MapPane is dominant.
- Existing Split mode behavior is unchanged when PiP is off.
