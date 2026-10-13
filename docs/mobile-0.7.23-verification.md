# 0.7.23 verification — October 13, 2026

Version **0.7.23 / code 31** is published on release and debug update channels.
Install through **Settings → App updates → Check for updates**.

Chats now have a **Your questions** button above the timeline. The picker lists
sent messages newest first, supports search over loaded previews, and scrolls
directly to the selected message with a highlighted border. Repeated messages
retain separate identities. Image-only messages use attachment names. Earlier
history can be loaded from the picker with loading, connection and retry states.
Tall questions reveal their beginning; the latest-arrow clears the highlight.
See [question navigation](question-navigation.md).

The target index is calculated from the current presented reverse-layout list,
including commands and final changes. The existing history loader and command
output preference remain in use. No backend API, message identity, sending or
queue behavior changes. No host restart is needed.

## Executed verification

- All **230 JVM tests pass**, with zero failures, errors or skips. Seven new
  navigation regressions cover repeated messages, user-only entries, rendered
  indices with commands/final changes, new messages and older pages, absent
  targets, attachment previews, search normalization and tall-message offsets.
- Android lint reports **No issues found**. Debug, release and instrumentation
  APKs build successfully. Three new native UI checks compile for repeated-message
  selection during updates, earlier-history retry and tall-question positioning.
  They were not executed because no Android device is connected.
- Both APK signatures verify and certificates match published 0.7.22. Publication
  validates actual package IDs, version 0.7.23/code 31 and SDK 30.
- Authenticated loopback metadata and full APK downloads match actual published
  files by byte count, SHA-256, delivery copy and content type. Unauthenticated
  and browser-Origin requests return 401. Delivery checksums pass.
- Previous delivery APKs remain under `artifacts/v0.7.22/`; previous immutable
  published files are retained. `git diff --check` passes.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
python3 /tmp/custom-codex-0723-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.23.txt
node /tmp/custom-codex-0723-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

Evidence: `artifacts/v0.7.23-android-build.txt`,
`v0.7.23-unit-test-summary.json`, `v0.7.23-signing-verification.json` and
`v0.7.23-local-update-verification.json`. Release size is 3,168,894 bytes; debug
size is 62,879,657 bytes.

## Unverified

Physical-phone interaction, installation and native instrumentation execution
remain unverified. Production authenticated HTTPS downloads were not repeated;
transfer checks used an ephemeral loopback host with the actual published APKs.
