# Unified release notes

`release-notes/<version>/whats_new.txt` is the authoritative operator-facing
release note for both Android and Apple builds.

Use these sections in this order:

1. `Latest changes:` for behavior shared by both platforms.
2. `Platform-specific changes:` for changes that apply to only Android or iOS.
3. `Known platform differences:` for intentional operating-system differences.

Keep the wording concise, user-facing, and limited to one visible change per
bullet. Technical Git history belongs in build diagnostics, not in this file.

Android packages the file selected by its `versionName`. The Apple Xcode
project must reference the same versioned file as its `MARKETING_VERSION`; the
release gate verifies both. When preparing a new version:

1. Create `release-notes/<version>/whats_new.txt`.
2. Update Android `versionName` and Apple `MARKETING_VERSION` to that version.
3. Update the Xcode `whats_new.txt` resource reference to the new canonical file.
4. Update the App Store metadata mirror with:

```sh
tools/sync_release_notes.sh <version>
```

The platform release gates reject missing canonical notes, missing sections,
version mismatches, and stale App Store metadata.

See the [mobile release runbook](../RELEASE.md) for the complete Android and
Apple build, qualification, tagging, store-staging, and recovery procedure.
