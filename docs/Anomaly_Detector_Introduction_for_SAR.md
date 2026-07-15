# Anomaly Detector: An Introduction for SAR Users

## Purpose

The RID2Caltopo Anomaly Detector helps a searcher notice small or unusual
regions in drone video. When it finds something worth a closer look, it places
a box around that **region of interest (ROI)** on the video.

The detector is a search aid, not confirmation that a person has been found. A
box means **"inspect this area"**, not **"a person has been found."** Optional
Person Relevance can strengthen an existing region of interest, but it does
not originate a target or replace operator review. The operator remains
responsible for reviewing the image, changing the camera angle or zoom when
possible, and deciding whether the observation should be investigated or
shared with the search team.

## Capabilities at a Glance

The detector can use several kinds of visual evidence:

| Capability | What it looks for | Typical SAR use |
| --- | --- | --- |
| Infrared | A small area that is warmer or cooler than its surroundings | People, animals, recently operated equipment, or other temperature contrasts |
| Color Outlier | A compact color patch that is unusual in the current and recent scene | Clothing, packs, tarps, vehicles, or equipment that stands out from terrain |
| Target Colors | One or more color families selected by the operator | A subject known to be wearing or carrying a particular color |
| Motion | Local movement that remains after accounting for drone and camera motion | A moving subject, signaling searcher, or disturbed vegetation |
| Saliency | A location supported by a combination of appearance, motion, and persistence | Subtle targets that may not be convincing from one clue alone |
| Person Relevance | Whether an existing Color or Infrared ROI has person-like visual evidence | Prioritizing an already-detected region for closer review |

These capabilities can support one another. For example, a weak thermal
contrast may become more interesting when it remains in one location and also
shows independent motion.

## Infrared Detection

Infrared mode searches for compact temperature differences rather than a
specific temperature. It compares each part of the image with its nearby
surroundings and with recent frames.

Use **White Hot (WH)** when hotter objects appear brighter. Use **Black Hot
(BH)** when hotter objects appear darker. An incorrect palette setting can make
the detector search for the opposite contrast.

Infrared detection is most effective when:

- The subject has a useful temperature contrast with the background.
- The image is in focus and not dominated by sky, glare, or hot machinery.
- The subject occupies more than a few pixels but is still relatively small in
  the frame.

Common false alarms include sun-warmed rocks, reflective roofs, animals,
engines, exhausts, fires, and sharp thermal boundaries.

## Visible-Color Detection

Color Outlier mode looks for compact, coherent blobs whose predominant color
is unusual compared with the rest of the scene and recent frames. It is meant
to bring attention to objects such as a red jacket in green vegetation or a
bright pack in muted terrain.

It does not require the operator to know the subject's color in advance. This
makes it useful during broad searches, but naturally unusual objects in the
scene can also be boxed.

Color detection is affected by shadows, haze, camera exposure, distance,
compression, and white balance. A color that is obvious to the eye at close
range may become dull or shift into another color family from altitude.

## Target Colors

Target Colors provide a more directed visible-light search. The operator can
select color families such as red, blue, green, yellow, orange, pink, purple,
brown, black, white, or grey. The detector then looks for compact regions that
contain the selected colors.

This is useful when the mission has dependable subject information, for
example:

- Red jacket
- Blue backpack
- Orange hunting vest
- White vehicle

Multiple colors may be selected when the description includes more than one
item. A compact region containing more than one requested color can provide
especially useful evidence, such as a red shirt near blue pants.

Select only colors that are reasonably reliable. Very common scene colors,
such as green in a forest or white in snow, may produce more distractions. The
feature is available with **Color** appearance and is not used in
**Infrared** mode. With no target colors selected, the normal
uniqueness-based Color Outlier capability remains available.

## Person Relevance

Person Relevance examines only regions already found by the Color or Infrared
detector. It cannot find a person when the selected appearance detector did not
first produce a candidate.

- **Off** performs no person analysis.
- **Evaluate** records person evidence without changing ROI relevance.
- **Assist** may add a bounded positive relevance boost. It never rejects a
  target or confirms that the target is a person.

Person analysis may be skipped when the device is busy so video playback and
the primary detector retain priority. Assist remains experimental until it has
completed reviewed aerial and device qualification.

## Motion Detection and Camera Stabilization

The drone and camera are usually moving, so ordinary frame-to-frame change is
not enough to identify a moving subject. The detector first estimates the
overall movement of the scene and then looks for smaller areas whose movement
does not match it.

Motion evidence can help confirm a walking person or a signaling arm, but it
can also respond to moving branches, water, shadows, rotor vibration, parallax,
or imperfect stabilization. Smooth flight, a steady gimbal, and adequate scene
detail improve the result.

The **Movement Estimator** and **Registration** controls affect this camera
motion compensation. They are advanced controls. Operators should normally
leave them at the realtime defaults unless working from a tested mission
profile or following troubleshooting guidance.

## Saliency and Persistence

Saliency combines several weaker clues and asks whether a location remains
interesting over time. It can use appearance, motion, scene stability, and
repeat observations to promote a target that might not pass a single detector
by itself.

This can improve sensitivity to subtle subjects, but it may also retain
attention on persistent background features. Treat it as supporting evidence,
not a separate confirmation of identity.

## Understanding ROI Boxes

An ROI box identifies an area for human review. Boxes are deliberately
stabilized so they do not appear and disappear with every individual frame.
The box may therefore remain briefly after the evidence weakens, and its edges
may be wider than the visible object.

When a box appears:

1. Keep the possible subject in view.
2. Inspect the original video inside and immediately around the box.
3. Pause, replay, zoom, or change camera angle when mission conditions permit.
4. Look for independent clues such as shape, movement, tracks, gear, or thermal
   persistence.
5. Record or communicate the observation according to team procedure.

Do not assume that repeated boxes are multiple subjects. One object may be
reacquired, tracked, or boxed by more than one capability.

## Suggested Starting Setups

### Thermal Search

- Select **Infrared**.
- Confirm that WH or BH matches the displayed thermal palette.
- Leave Motion on when the video is stable enough to support it.
- Start with **Reset to Realtime Defaults**.

### General Daylight Search

- Select **Color**.
- Leave Target Colors clear when there is no dependable color description.
- Use Color Outlier to look for colors that differ from the surrounding scene.
- Leave Motion on to provide an additional clue.

### Known Clothing or Equipment Color

- Select **Color**.
- Select only the known Target Colors.
- Use two colors when both are dependable and likely to appear close together.
- Clear obsolete color selections when moving to a different subject or
  assignment.

### Reviewing Captured Video

- Use the same appearance mode that matches the recorded camera.
- Pause and step through frames around an ROI.
- Add review annotations where supported by the playback view.
- Recheck detections in the unannotated image before making a field decision.

## Controls Most Operators Need

**AD Mode** is the first control in Anomaly Detector Settings. It selects Off,
Color Uniqueness, Target Colors, or Infrared. The stream legend shows the
current mode and opens the same settings panel when tapped.

Only settings that apply to the selected mode are shown. Target Colors opens a
color-family picker; Infrared exposes thermal controls; and Off hides detector
tuning controls.

**Sensitivity** controls how strong the evidence must be before a box is
shown. Increasing sensitivity can find weaker candidates but also increases
false alarms.

**Motion Evidence** controls how strongly motion contributes to detection.

**Scan Zone** controls how much of the center of the frame is searched. A
smaller zone reduces edge distractions but ignores more of the image.

**Min Hits** controls how many analyzed observations are needed in roughly the
same stabilized location before a candidate is promoted. More hits improve
stability but can delay brief detections.

**Reset to Realtime Defaults** restores a tested starting point when tuning has
made the detector slow, noisy, or difficult to interpret.

AD mode and tuning changes last only for the current app session. Every app
start resets AD to Off with the tested realtime defaults ready for the next
selected mode.

The detail, stride, adaptive timing, registration, movement estimator, thermal
minimum delta, and debug-overlay controls are intended for advanced tuning and
troubleshooting. Changing them can trade detection speed, target size, and
false-alarm behavior against one another.

## Practical Limitations

No video detector can recover information that the camera did not capture.
Detection may be reduced by:

- A subject occupying too few pixels
- Motion blur, poor focus, or heavy video compression
- Trees, terrain, buildings, or smoke blocking the subject
- Low thermal contrast or a recently heated background
- A target color altered by shade, haze, exposure, or distance
- Rapid camera motion, abrupt zoom, gimbal movement, or scene changes
- Large areas that resemble a selected target color
- Subjects that remain visually similar to their surroundings

The absence of a box does not mean an area is clear. Continue normal visual
search patterns and apply established SAR procedures.

## Field Checklist

Before searching:

- Confirm the correct video stream and camera type.
- Reset to realtime defaults unless using a tested profile.
- Confirm that Color or Infrared matches the camera feed.
- Verify WH/BH for thermal video.
- Select Target Colors only when the description is reliable.
- Confirm the scan zone covers the intended search area.

During searching:

- Keep flight and gimbal movement as smooth as conditions allow.
- Treat every ROI as a prompt to inspect, not as a confirmed find.
- Use independent visual, motion, thermal, map, and team information.
- Revisit promising locations from another angle when practical.

After searching:

- Review captured video around important detections.
- Preserve and communicate observations according to team procedure.
- Clear mission-specific Target Colors before the next assignment.

## Final Reminder

The Anomaly Detector is best used as a second set of eyes. Its value is in
directing limited human attention toward places that might otherwise be
overlooked. It complements, but does not replace, a trained searcher and a
disciplined search plan.
