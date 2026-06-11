# New Stream Shows Grid Design

## Context

During a two-stream field test, the tablet accepted and recorded both MediaMTX
streams, but the operators only saw the currently focused stream. The new-stream
toast was not noticed. Operators expected the one-up focused stream view to split
into the multi-stream grid as soon as another stream connected.

## Goal

Any newly connected live stream must make all currently visible live streams
visible in the streams grid. The layout change is the operator-facing alert.

## Behavior

- When any stream transitions into the live stream set, `StreamsViewModel` clears
  the current focused stream before render routing and layout decisions.
- With no focused stream, `StreamsScreen` shows every visible live stream using
  the existing grid rules.
- The existing stream admission cap remains four simultaneous streams. The first
  through fourth live streams should be visible together in the grid when no
  stream is focused.
- A fifth concurrent stream should be rejected by the existing admission path
  without disrupting the four visible streams.
- Operators can focus an individual stream again after seeing the multi-stream
  grid.
- The existing "New stream attached: ..." toast is removed for this path.

## Non-Goals

- Do not change MediaMTX ingest, recording, RTMP/RTSP routing, or stream-to-drone
  mapping behavior.
- Do not add a persistent notification, banner, or extra stream count indicator.
- Do not change the current grid geometry beyond allowing it to become visible
  when new streams arrive.
- Do not raise the existing four-stream admission limit.

## Implementation Shape

Add a small focus-clearing step in the stream sync path that detects newly live
streams. If at least one newly live stream exists and `_focusedPath` is set, clear
focus before computing `shouldUseFfmpegRender(...)` and `displayedTileCountForCurrentLayout()`.

Remove the toast emitted when a stream becomes newly attached. The visible grid
transition replaces it.

## Testing

Use focused unit tests around the stream view-model/router seam:

- A focused single live stream receiving a second newly live stream clears focus.
- After focus clears, the displayed tile count follows the live stream count
  rather than remaining at one.
- Two, three, and four concurrent live streams remain visible together when a new
  stream arrival clears focus.
- A fifth concurrent stream fails cleanly through the existing admission
  rejection path and leaves the four admitted streams visible.
- A user can focus a stream again after the new-stream grid is visible.
- The new-stream attached toast path is removed or no longer invoked.

## Acceptance Criteria

- If stream A is focused and stream B becomes live, the UI returns to the grid
  with both A and B visible.
- If additional streams later become live while a stream is focused, the UI again
  returns to the grid with all admitted visible live streams, up to the existing
  four-stream limit.
- If a fifth concurrent stream attempts to connect, it is rejected cleanly and
  the four admitted streams remain visible.
- No "New stream attached: ..." toast is shown for this connection event.
- Existing single-stream focus behavior still works when the operator manually
  selects a stream after the grid is visible.
