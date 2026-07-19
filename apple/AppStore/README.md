# App Store Connect metadata

These files are the reviewed English (U.S.) drafts for App Store Connect. They
are deliberately kept beside the source so release wording and application
behavior can be checked together.

- Bundle ID: `org.ncssar.RID2CaltopoApple`
- SKU suggestion: `RID2CALTOPO-APPLE-001`
- Primary category suggestion: Navigation
- Secondary category suggestion: Utilities
- Support URL: <https://github.com/kjtsar/RID2Caltopo>
- Privacy Policy URL:
  <https://github.com/kjtsar/RID2Caltopo/blob/main/PrivacyPolicy.md>
- Copyright suggestion: `2026 Kenneth Taylor`
- App Store Connect Apple ID: `6792518823`

The updated root `PrivacyPolicy.md` was committed and pushed to the public
repository on July 19, 2026. The public URL is saved in App Store Connect.

`verify-metadata.sh` checks local field presence and Apple's text limits. It
also checks that the privacy manifest matches the conservative linked-data
worksheet in `APP_STORE_CONNECT_CHECKLIST.md`. It does not contact or modify
App Store Connect.

The subtitle, Navigation/Utilities categories, Apple's calculated 4+ age
rating, and all six iPhone/iPad screenshots were saved in App Store Connect on
July 19, 2026. Content Rights is saved as No. The DSA non-trader declaration is
Active, and the reviewer phone, contact information, no-sign-in selection,
review notes, and public Privacy Policy URL are saved. The four App Privacy
data types are configured consistently with the privacy manifest and were
published July 19, 2026 after explicit owner acceptance of Apple's final legal
accuracy/compliance attestation. Version 1.0 build `202607191735` is uploaded,
processed, and Ready to Submit in TestFlight. Its beta metadata and test
instructions are saved. Build `202607191720` advertised version 0.1 and is
superseded; the archive/export verifier now rejects a marketing-version mismatch.

Generate clean Release-build Simulator screenshots at Apple's required 6.9-inch
iPhone and 13-inch iPad sizes with:

```sh
apple/AppStore/capture-screenshots.sh
```

The script launches the real application with deterministic Remote ID demo data
and captures Nearby Aircraft, Live Map, and Status views. It suppresses radio
and location permission prompts only for screenshot capture; production startup
behavior is unchanged.

The dimensions are taken from Apple's current
[screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/).
