# App Store Connect entry worksheet

This is a reviewable worksheet, not an automated submission. It reflects the
current application behavior and Apple's July 19, 2026 definitions. Confirm the
answers against the live questionnaire before saving them.

## New App record

- Platforms: iOS
- Name: `RID2Caltopo`
- Primary language: English (U.S.)
- Bundle ID: `org.ncssar.RID2CaltopoApple`
- SKU: `RID2CALTOPO-APPLE-001`
- User access: Full Access

The App Store Connect record was created on July 19, 2026:

- Apple ID: `6792518823`
- Version: `1.0` (`Prepare for Submission`)
- Record URL: <https://appstoreconnect.apple.com/apps/6792518823/distribution>

Version 1.0 build `202607191735` was uploaded July 19, 2026 and is **Ready to
Submit** in TestFlight. Its test instructions are saved. The earlier accidental
0.1 build `202607191720` is superseded and must not be assigned to testers or an
App Store version. The Free Apps Agreement is active. The Paid Apps Agreement
is not required while the app remains free with no in-app purchases.

## App information

- Subtitle: `Remote ID for SAR Operations` — saved July 19, 2026
- Primary category: Navigation — saved July 19, 2026
- Secondary category: Utilities — saved July 19, 2026
- Support URL: <https://github.com/kjtsar/RID2Caltopo>
- Privacy Policy URL:
  <https://github.com/kjtsar/RID2Caltopo/blob/main/PrivacyPolicy.md>
- Content rights: **No** — saved July 19, 2026. The app does not contain, show,
  or access third-party content.
- Encryption: `ITSAppUsesNonExemptEncryption` is `false`; the app uses standard
  platform HTTPS, HMAC, and Keychain facilities and no proprietary encryption.

The updated root privacy policy was committed and pushed July 19, 2026. Its
public GitHub URL is saved in App Store Connect.

## App Privacy draft

Answer **Yes** to data collection. Disclose every opt-in network configuration,
because Apple requires disclosure when collection varies by user choice. None
of the data is used for tracking, advertising, analytics, or marketing.

The four data types below were configured in App Store Connect on July 19,
2026 as linked to the user, used for App Functionality, and not used for
tracking. Kenneth Taylor explicitly confirmed Apple's accuracy/compliance
attestation, and the disclosure was published July 19, 2026.

| Data type | Linked to user | Tracking | Purpose | Why |
| --- | --- | --- | --- | --- |
| Precise Location | Yes | No | App Functionality | A configured tracker can receive operator coordinates with the app-install zone identity. |
| Device ID | Yes | No | App Functionality | Tracker coordination uses a random stable app-install zone identifier and device zone name. |
| Other User Content | Yes | No | App Functionality | Confirmed organization, pilot callsign, drone description, Remote ID, and aircraft telemetry can be sent to the configured tracker or CalTopo map. |
| Other Diagnostic Data | Yes | No | App Functionality | An operator may explicitly share logs containing the stable coordination identifier, Remote IDs, positions, network addresses, and device/runtime state. |

Apple defines collection as off-device transmission retained beyond servicing a
real-time request. On-device-only observations, archives, and MapKit's own data
collection are not developer collection. The checked-in privacy manifest makes
the conservative disclosures above so optional tracker, CalTopo, and diagnostic
paths are all covered.

## Age Rating draft

The questionnaire below was saved July 19, 2026. Apple calculated a global
rating of 4+ (with its standard regional equivalents).

- Parental Controls: No
- Age Assurance: No
- Unrestricted Web Access: No
- User-Generated Content: No — incident data is exchanged among configured
  operational peers, not broadly distributed as an intended content experience.
- Social Media: No
- Social Media Disabled for Users Under 13: Not applicable
- Messaging and Chat: No
- Advertising: No
- Every objectionable-content frequency: None
- Override to Higher Age Rating: No

## Version metadata and review

- Use `metadata/en-US` for name, subtitle, description, promotional text,
  keywords, what's new, and review notes.
- Use the six visually reviewed images from
  `apple/Build/AppStoreScreenshots`: three 1320 x 2868 iPhone screenshots and
  three 2064 x 2752 iPad screenshots.
- Uploaded July 19, 2026: three 1320 x 2868 screenshots in the iPhone 6.9-inch
  slot and three 2064 x 2752 screenshots in the iPad 13-inch slot. App Store
  Connect is using each set for compatible smaller display sizes.
- App Store Connect's live iPhone 6.9-inch slot was verified to accept
  1320 x 2868 on July 19, 2026. The iPad images use Apple's published
  13-inch 2064 x 2752 size.
- Price: Free, unless the distribution plan changes.
- Availability: decide whether the first build is TestFlight-only before
  selecting App Store territories.
- TestFlight feedback email: `kjtsar@kjt.us`
- TestFlight beta description, privacy URL, reviewer contact, no-sign-in
  selection, review notes, and build-specific test instructions — saved July
  19, 2026.
- App Review contact saved July 19, 2026: Kenneth Taylor,
  `+15305594250`, `kjtsar@kjt.us`.
- Sign-in required: No — saved July 19, 2026.
- Review notes from `metadata/en-US/review_notes.txt` — saved July 19, 2026.
- Release mode: manual — saved July 19, 2026.

## Account compliance

- Digital Services Act status: non-trader — saved July 19, 2026. App Store
  Connect reports the DSA requirement Active and all current regulatory
  requirements complete.
- Confirm tax/banking agreements only if paid distribution or in-app purchases
  are ever introduced.
- App Privacy publication: published July 19, 2026 by Kenneth Taylor. The live
  product-page preview shows all four data types under Data Linked to You.
- Review territories and the build's export-compliance result before any App
  Store submission. TestFlight remains the initial distribution path.

## Apple references

- [Add a new app](https://developer.apple.com/help/app-store-connect/create-an-app-record/add-a-new-app)
- [App privacy details](https://developer.apple.com/app-store/app-privacy-details/)
- [Set an app age rating](https://developer.apple.com/help/app-store-connect/manage-app-information/set-an-app-age-rating/)
- [Age-rating definitions](https://developer.apple.com/help/app-store-connect/reference/app-information/age-ratings-values-and-definitions/)
- [Screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/)
