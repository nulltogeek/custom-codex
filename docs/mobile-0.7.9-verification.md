# 0.7.9 verification — October 11, 2026

Version **0.7.9 / code 17** is published on both update channels.
Chat rows no longer show the permanent Restore/Delete action bar. Swipe right
for Archive on active chats or Restore on archived chats. Swipe left for Delete
on archived chats. Both actions open the existing confirmation dialogs.
Delete retains the connection, active-work, pending-input and queue guards.
Directions remain physical right/left in right-to-left layouts. Screen-reader
custom actions and the chat toolbar provide access to the same confirmations.
Archived favorites also use the same Delete confirmation.

The gesture change was committed and pushed separately as `2f30e99`.

## Executed verification

- **100 JVM tests pass**, zero failures, errors or skips. Existing tests verify
  archive/delete guards and deletion cleanup/pagination invalidation.
- Debug, release and instrumentation APK builds succeed. Android lint reports
  **No issues found**. Existing icon/clipboard deprecation warnings remain.
- Instrumentation tests compile for left Delete/right Restore in both layout
  directions, disabled Delete, screen-reader Restore, compact archived rows and
  project filtering. Existing tests cover active-chat right Archive, disabled
  swipes and confirmation cancellation. They were not executed on a device.
- APK signatures verify and signing certificates match published 0.7.8. The
  publisher checks actual package identities, version 0.7.9/code 17 and SDK 30.
- Both published APKs and manifests pass authenticated loopback transport,
  size, SHA-256 and delivery-file checks. Unauthenticated and browser-Origin
  requests are rejected with 401. Delivery checksums pass.
- `git diff --check` passes. Backend code was unchanged; backend tests were not
  repeated for this UI change.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
python3 /tmp/custom-codex-079-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.9.txt
node /tmp/custom-codex-079-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

The temporary artifact script runs `apksigner verify --print-certs`, compares
new and previous signing identities, preserves delivery APKs under
`artifacts/v0.7.8/` and computes checksums. The temporary transport script
creates an authenticated loopback host with an ephemeral token, verifies both
real APK channels and closes the test host. Production credentials are not used.
Evidence is saved in `artifacts/v0.7.9-android-build.txt`,
`v0.7.9-signing-verification.json` and `v0.7.9-local-update-verification.json`.

## Unverified

No Android device is connected. Physical swipe behavior, screen-reader menus,
confirmation interactions and update discovery/installation remain unverified
on a phone. No real chat was deleted, and the running host was not restarted.
Delete still requires the updated host bridge from 0.7.8; see its
[verification and host reload requirements](mobile-0.7.8-verification.md).
