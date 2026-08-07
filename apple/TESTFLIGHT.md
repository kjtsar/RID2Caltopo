# RID2Caltopo Apple field-test distribution

TestFlight is the intended path for users who do not have Xcode. Testers install
Apple's free TestFlight app, accept an email invitation or public link, and then
install RID2Caltopo like any other beta application.

## One-time account setup

1. Enroll the distributing individual or legal entity in the Apple Developer
   Program. A paid membership is required for TestFlight distribution.
2. In Xcode, add that Apple Account under **Xcode > Settings > Accounts**.
3. The project is configured for Kenneth Taylor's individual team
   (`94UV79S6LR`). If ownership changes, select the replacement distributing
   team under **Signing & Capabilities**.
4. Xcode automatic signing has registered `org.ncssar.RID2CaltopoApple` and
   created the managed Apple Distribution certificate and App Store profile.
   No manual certificate installation or registered iPhone/iPad is required to
   package a TestFlight build on this Mac.
5. The matching App Store Connect record exists as Apple ID `6792518823`.
6. The root `PrivacyPolicy.md` is published and its public GitHub page is saved
   as the Privacy Policy URL. Make the App Privacy answers match
   `App/PrivacyInfo.xcprivacy`: precise location, the app-install/device zone
   identifier, confirmed drone/operator content, and optional diagnostics are
   used for app functionality; none is used for advertising or cross-app
   tracking.

## Reproducible build and upload

Create `../release-notes/<version>/whats_new.txt` for every release. It must
contain `Latest changes:`, `Platform-specific changes:`, and
`Known platform differences:` sections. After review, run
`tools/sync_release_notes.sh <version>`; the release gate rejects missing,
stale, mismatched, or incorrectly bundled notes.

Review the checked-in App Store wording and verify Apple's text limits first:

```sh
apple/AppStore/verify-metadata.sh --marketing-version 1.1
```

After the one-time account setup, first run a read-only preflight. Replace the
example team and bundle values with those shown by the Apple Developer portal:

```sh
apple/archive-for-testflight.sh \
  --team 94UV79S6LR \
  --bundle-id org.ncssar.RID2CaltopoApple \
  --preflight
```

Create an unsigned archive, export and verify a distribution-signed IPA, but do
not upload it:

```sh
apple/archive-for-testflight.sh \
  --team 94UV79S6LR \
  --bundle-id org.ncssar.RID2CaltopoApple
```

The default build number is a unique UTC timestamp. The command first runs the
full `apple/release-check.sh` gate, produces a verified unsigned archive, then
uses Xcode automatic distribution signing during local export. It rejects the
IPA if
its team, bundle ID, build number, arm64 binary, iPhone/iPad device families,
QR URL schemes, privacy declarations, signature, or release entitlements do
not match expectations. It does not store Apple credentials.

Release preparation also verifies the shared protected-land source catalog,
but performs the network check no more than once every seven days. Repeated
packaging runs reuse that result. Use `--force-land-catalog-refresh` on either
release command when a known agency endpoint change requires an immediate
recheck.

Only after the App Store Connect record and TestFlight metadata are ready, add
the explicit upload flag:

```sh
apple/archive-for-testflight.sh \
  --team 94UV79S6LR \
  --bundle-id org.ncssar.RID2CaltopoApple \
  --upload
```

Use `--internal-only` with `--upload` for a build that must never be released to
external testers or the App Store. Normal uploads use the local App Store
Connect key in `~/.appstoreconnect/private_keys` plus its non-secret issuer ID,
so an expired Xcode account token does not block routine releases. The private
key is never copied into the repository or command output. Internal-only upload
continues to use Xcode's configured Apple Account.

The equivalent manual Organizer workflow remains available:

1. Increment `CURRENT_PROJECT_VERSION` for every upload.
2. Select **Any iOS Device (arm64)** as the run destination.
3. Choose **Product > Archive**.
4. In Organizer, choose **Distribute App > App Store Connect > Upload**.
5. Complete export-compliance questions. RID2Caltopo uses HTTPS/HMAC and Apple
   Keychain APIs but does not implement a proprietary encryption algorithm.

## External field testers

1. In App Store Connect, open the app's **TestFlight** tab.
2. Supply the beta description, feedback email, contact information, and concise
   test instructions.
3. Create an internal group, then an external group and attach the build.
4. Submit the first external build for TestFlight App Review.
5. After approval, invite testers by email or create a public link. Testers do
   not need access to the developer account.

Suggested first-round instructions:

Give each tester a copy of `DEVICE_QUALIFICATION_REPORT.md`. Require the exact
TestFlight build number and retained evidence for every applicable row so a
later build cannot be credited with results from an earlier binary.

- Approve Bluetooth, Local Network, and Location access.
- On Android, generate the normal organization QR. On Apple choose **Import
  Config**, scan it, and verify the displayed organization, incident,
  operational period, and team-drone count. FAA and mutual-aid QR codes use the
  same importer when those features are configured.
- Open **Status** and confirm the automatically started Bluetooth scanner beside
  a known Remote ID drone.
- For Wi-Fi Remote ID qualification, enable the DS110 wireless relay and verify
  the bridged aircraft appears through Apple's Bluetooth scanner with the same
  ID and position shown on Android.
- Tap **Copy** in Status, paste the report into Notes, and verify it contains no
  QR token, tracker API key, or CalTopo credential secret.
- Capture a screenshot if an aircraft is missing or mapped incorrectly.
- Verify an aircraft covered by the imported team configuration receives the
  same mapped label as Android. For an unmapped aircraft, choose **Confirm
  Drone**, enter organization, pilot callsign, and drone description, and verify
  the label updates for the current app session.
- With two devices on the same tracker map, verify the incident header reports
  the peer, Save the drone on one device, and confirm the other device does not
  publish that drone independently. Repeat after briefly disconnecting one
  device from the network.
- During a controlled spacing test, verify only the locally confirmed owner
  shows and speaks the proximity warning. Exercise **Map**, **Suspend**, and
  **Resume Proximity Alert**, then record whether the alert clears after the
  aircraft remain safely separated.
- Repeat once with **Settings > Predictive Head** on and once off. With it on,
  confirm approaching traffic can warn before the reported positions cross the
  configured spacing threshold, without warnings for traffic moving apart.
- For streaming, start MediaMTX and publish the controller to the displayed
  `rtmp://<device-address>:1935/demo` target.
- In Live View, select **Off** and verify video continues while the analyzed
  counter stops. Select **Color** for a visible-light source and **Infrared**
  for a thermal source; verify analyzed frames resume, drops remain bounded,
  and yellow/red boxes respectively align with the video when detections occur.
- Reproduce the issue, then choose **More > Send app log to Ken…**, leave today
  selected, choose **Package Logs**, and use **Send Logs via…**.
- During a dedicated background test, lock the screen for two minutes and then
  return to RID2Caltopo. Record whether the aircraft continued updating; iOS
  intentionally slows background Bluetooth scans and coalesces duplicates.

TestFlight builds remain testable for 90 days. The first build for an external
group requires review; later builds may receive a shorter review.

## Current local readiness

The universal iPhone/iPad Release target builds successfully, including the app
icon, privacy manifest, in-app privacy policy, MediaMTX bridge, anomaly core,
Remote ID receivers, tracker peer coordination, and daily log-sharing workflow.
The project is configured for team `94UV79S6LR`. On July 19, 2026, Xcode's
automatic distribution export created and used an Apple Distribution identity
and device-free App Store profile for `org.ncssar.RID2CaltopoApple`; the
resulting arm64 IPA passed signature, provisioning, entitlement, bundle, device
family, QR scheme, and privacy checks. App Store metadata drafts and visually
reviewed 6.9-inch iPhone and 13-inch iPad screenshots are available under
`AppStore` and `Build/AppStoreScreenshots`; all six screenshots are uploaded.
The four App Privacy data types were published July 19, 2026 after explicit
owner confirmation of Apple's accuracy/compliance attestation. Content Rights
is saved as No, DSA non-trader status is Active, and the complete App Review
contact and notes are saved. Version 1.0 build `202607191735` is uploaded,
processed, and Ready to Submit with its beta description and build-specific
test instructions saved. The superseded 0.1 build `202607191720` must not be
assigned to testers. Archive and signed-IPA verification now require the
expected marketing version as well as the build number. App Store Connect
version 1.0 remains in `Prepare for Submission`. Release remains gated by `RELEASE_ACCEPTANCE.md`, including
physical-device qualification. A July 19, 2026 preflight revalidated team
`94UV79S6LR`, bundle `org.ncssar.RID2CaltopoApple`, the current Xcode project,
and the installed Kenneth Taylor development identity without changing App
Store Connect; distribution signing remains automatically managed at export.

Unsigned local archives can be checked before signing with:

```sh
apple/verify-unsigned-archive.sh apple/Build/RID2CaltopoApple-unsigned-15.xcarchive
```
