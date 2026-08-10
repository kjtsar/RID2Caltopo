# UAS4SAR rebrand migration plan

This checklist records the agreed migration from RID2Caltopo to **UAS4SAR**.
It is a planning document only: completing the checkpoint that introduced this
file does not rename, publish, redirect, or reconfigure any production system.

## Agreed public names

- Application and ecosystem name: `UAS4SAR`
- Primary application website: `https://uas4sar.app`
- Primary project/community website: `https://uas4sar.org`
- Existing tracker service name and domain remain `r2c-tracker` and
  `r2c-tracker.com` for now.
- The existing application icon remains unchanged for the initial migration.
  A later release may replace its text with `U4S` or remove the text.

## Compatibility constraints

- Treat this as a public-facing name and domain migration, not a new
  application. Preserve existing user data, upgrade paths, organization
  enrollment, tracker credentials, and store history.
- Do not change the Android application ID or Apple bundle ID merely to match
  the new public name. Either change requires a separate compatibility and
  store-distribution review.
- Keep the current tracker protocol and organization-scoped endpoints stable
  while clients and websites transition.
- Continue to identify CalTopo accurately as an independent product and retain
  the existing non-affiliation acknowledgement wherever its name appears.

## Migration order

1. **Checkpoint the pre-rebrand source.** Commit the current tracker, Android,
   Apple, test, security, and release-documentation work before changing names.
2. **Prepare the new websites.** Build and verify `uas4sar.app` and
   `uas4sar.org` using the current content, new name, corrected links, legal
   acknowledgements, privacy information, and support contacts. Keep the new
   sites private or otherwise non-authoritative until review is complete.
3. **Configure domain and identity dependencies.** Add DNS, TLS, Google Cloud
   authorized domains, OAuth consent-screen domains, redirect URIs, email
   sender/domain records, analytics/monitoring, and any Apple associated-domain
   configuration that the deployed features actually require.
4. **Rebrand both mobile clients together.** Update Android and Apple display
   names, operator-visible text, help/about links, privacy/support links,
   screenshots, store metadata, release notes, and automated assertions. Keep
   platform behavior and release numbers aligned.
5. **Run preproduction regression.** Exercise both mobile clients against the
   test tracker URL, including organization enrollment, track publication,
   NOTAM proxy status, managed video where available, archives, disclaimers,
   and upgrade behavior from the last RID2Caltopo release.
6. **Publish the new websites and mobile releases.** Use staged store channels,
   verify the distributed artifacts, and monitor failures before broadening
   distribution.
7. **Redirect the legacy websites.** After the new destinations are verified,
   configure permanent path-preserving redirects from `rid2caltopo.com` to
   `uas4sar.app` and from `rid2caltopo.org` to `uas4sar.org`. Retain redirects
   long enough to support old links and installed clients.
8. **Rename repository surfaces last.** Rename GitHub repositories,
   descriptions, topics, documentation links, and automation references only
   after release/deployment scripts and external integrations have been
   inventoried. Preserve redirects and update local remotes explicitly.
9. **Defer internal identifier cleanup.** Package names, bundle IDs, database
   names, protocol terms, and compatibility aliases may remain historical when
   users never see them. Change them only through separately reviewed
   migrations with rollback plans.

## External actions requiring an authorized account holder

- Approve Cloudflare DNS and redirect changes for all four domains.
- Update Google Cloud/OAuth domain verification and consent configuration.
- Approve Google Play and App Store Connect names, metadata, signing, and
  staged releases.
- Approve GitHub organization/repository renames and any dependent secrets or
  app installations.
- Confirm legal/board review of the public name, disclosures, privacy language,
  and CalTopo acknowledgement before public launch.

## Release gate

No production rebrand deployment should proceed unless the exact candidate
commit passes the tracker release/security checks, Android release check, Apple
release check, and preproduction URL regression. Go-live and rollback remain
explicit operator actions.
