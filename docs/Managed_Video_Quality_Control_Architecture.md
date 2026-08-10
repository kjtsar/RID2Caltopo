# Managed Video Quality Control

## Current boundary

RID2Caltopo currently sends the browser's WebRTC offer to the tablet and applies it to
the local MediaMTX WHEP endpoint. That proves consent, signaling, ICE/TURN routing, and
video delivery, but MediaMTX relays the controller's encoded H.264 stream unchanged.
The selected frame rate and bitrate are therefore descriptive today; they are not yet
sender-enforced limits.

The Apple decoder already produces hardware-decoded `CVPixelBuffer` frames through
FFmpeg and VideoToolbox. The local display and anomaly detector consume those frames.
Remote-video adaptation should fork from that decoded-frame boundary without changing
either existing consumer.

## Recommended sender path

1. Keep FFmpeg/VideoToolbox as the single source decoder.
2. Feed approved decoded frames into an app-owned WebRTC video source.
3. Apply the selected output size and cadence with WebRTC's video-source adaptation.
   It preserves aspect ratio, scales down, and drops frames before encoding.
4. Set the WebRTC sender's maximum frame rate and bitrate as additional safeguards.
5. Let WebRTC use the platform hardware H.264 encoder and the existing ICE/TURN route.
6. Verify the effective output from outbound RTP statistics, rather than reporting only
   the requested constraints.

This avoids enabling FFmpeg's filter, scaler, encoder, and muxer stack in the mobile
bundle merely to hand the result back to WebRTC. The shipped Apple FFmpeg framework is
intentionally decode-only. A full FFmpeg alternative remains possible, using `fps` and
`scale` filters followed by a VideoToolbox encoder, but it duplicates facilities already
present in the WebRTC SDK and complicates timestamping and congestion control.

## Resolution and cadence ladder

Generate only choices that do not upscale the source. Preserve its aspect ratio and
round both dimensions down to even values required by common YUV encoders. Start with:

- Original size at 15, 10, or 5 frames per second.
- At most 1280 pixels on the long edge at 15, 10, or 5 frames per second.
- At most 960 pixels on the long edge at 15, 10, or 5 frames per second.
- At most 640 pixels on the long edge at 15, 10, or 5 frames per second.

Remove duplicate sizes and frame rates above the measured source cadence. Keep the
original-size choice even when the source is smaller than every tier.

Estimate each choice from source bitrate when available:

`estimated bitrate = source bitrate * pixel ratio * frame-rate ratio`

When source bitrate is unavailable, use a conservative H.264 bits-per-pixel-per-frame
estimate and label the result as an estimate. Recommend only choices below 75 percent
of measured usable uplink, leaving headroom for variability and WebRTC overhead. The
pilot or visual observer retains the final choice and explicit Start action.

## Enforcement and evidence

The sender must enforce all three approved constraints:

- Output width and height through video-source adaptation.
- Cadence through frame admission plus the RTP sender's maximum frame rate.
- Bitrate through the RTP sender's maximum bitrate.

Both the tablet and browser should show selected versus effective width, height, frame
rate, bitrate, bytes sent, connection count, and Direct or Routed. Effective values must
come from outbound RTP statistics such as frames sent/encoded, frame dimensions, bytes
sent, and quality-limitation reason. A physical-device qualification should confirm the
5 fps and reduced-resolution cases over both direct and TURN-routed connections.

## Android parity

Android already has the WebRTC dependency for link preflight but not an app-owned media
sender. Its implementation should use the same quality-policy inputs and RTP evidence.
The remaining platform-specific task is to expose decoded frames from the native video
path as WebRTC `VideoFrame` buffers without disturbing local rendering or anomaly work.
