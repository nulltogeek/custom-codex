# 0.7.17 verification — October 13, 2026

Version **0.7.17 / code 25** is published on both release and debug update
channels. Installed updater users can choose **Settings → App updates → Check
for updates**, then download and install the update. The higher build number
makes this release discoverable over 0.7.16. Application IDs and signing
certificates remain compatible with their respective previous channels.

This release includes automatic chat/project updates on entry, queue delivery
across project navigation, recoverable notice dismissal, chat/project renaming,
project creation, moving chats between projects, restored bottom navigation and
optional command-output downloads. See the [release notes](release-notes/0.7.17.txt).
Project management and command-output filtering require the updated host backend
to be loaded. This APK publication does not restart the active host or its tasks.

## Executed verification

- All **180 JVM tests pass**, with zero failures, errors or skips. Android lint
  reports **No issues found**. Debug, release and instrumentation APKs build.
- All **3 update-server tests pass**, covering manifest validation, authenticated
  delivery and monotonically increasing build numbers.
- Both APK signatures verify and signing certificates match published 0.7.16.
  The publisher checks actual package IDs, version 0.7.17/code 25 and SDK 30.
- An ephemeral authenticated loopback host serves both actual published APKs.
  Metadata, byte counts, SHA-256 digests, delivery copies and content types match.
  Unauthenticated and browser-Origin requests are rejected with 401.
- Delivery checksums and `git diff --check` pass. Previous delivery APKs are
  retained under `artifacts/v0.7.16/`; older immutable published APKs are retained.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
node --test bridge/test/app-updates.test.mjs
python3 /tmp/custom-codex-0717-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.17.txt
node /tmp/custom-codex-0717-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

Evidence: `artifacts/v0.7.17-android-build.txt`, `v0.7.17-update-tests.txt`,
`v0.7.17-signing-verification.json` and `v0.7.17-local-update-verification.json`.
Release APK size is 3,119,742 bytes; debug APK size is 60,712,639 bytes.

## Unverified

No Android device is connected. Device instrumentation, installation and updater
interaction on the user's phone remain unverified. Production authenticated HTTPS
downloads were not repeated; transport verification used the local host and actual
published files without production credentials. Loading the updated broker for
host-dependent features remains pending.
