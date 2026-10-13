# 0.7.21 verification — October 13, 2026

Version **0.7.21 / code 29** is published on release and debug update channels.
Install through **Settings → App updates → Check for updates**.

Archived selection mode now offers Select all and Deselect all. Select all
captures every loaded chat in the current search/project view; its scope is
labelled Loaded chats. Deselect all exits selection mode. Newly loaded or
refreshed chats are added only when Select all is tapped again, so an existing
delete selection never silently grows. The count, row checkboxes, confirmation
and existing deletion guards use the resulting explicit selection. A second
control row keeps these actions readable at narrow widths. See
[the archive selection guide](archive-selection.md).

## Executed verification

- All **210 JVM tests pass**, with no failures, errors or skips. Android lint
  reports **No issues found**. Debug, release and instrumentation APKs build.
- Three new native UI checks compile for select/deselect all, exact confirmed
  deletion targets, project-filter exclusion, and selecting newly loaded chats
  only after another explicit tap. Existing six archive-selection UI checks also
  compile. No device was connected, so these checks were not executed.
- Both APK signatures verify and certificates match published 0.7.20. Publication
  validates actual application IDs, version 0.7.21/code 29 and SDK 30.
- Both delivery APKs contain the compiled Select all, Deselect all, Loaded chats
  and `archive-select-all` strings.
- Authenticated loopback metadata and complete downloads match actual published
  APKs by size, SHA-256, delivery copy and content type. Unauthenticated and
  browser-Origin requests return 401. Delivery checksums pass.
- Previous delivery APKs remain under `artifacts/v0.7.20/`; previous immutable
  published APKs are retained. `git diff --check` passes. No host restart is needed.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
python3 /tmp/custom-codex-0721-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.21.txt
node /tmp/custom-codex-0721-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

Evidence: `artifacts/v0.7.21-android-build.txt`,
`v0.7.21-signing-verification.json` and
`v0.7.21-local-update-verification.json` and
`v0.7.21-ui-symbol-verification.json`. Release size is 3,152,510 bytes;
debug size is 60,794,559 bytes.

## Unverified

Phone interaction and installation remain unverified. No real user chats were
deleted for validation. Production authenticated HTTPS downloads were not
repeated; transfer checks used an ephemeral loopback host with published APKs.
