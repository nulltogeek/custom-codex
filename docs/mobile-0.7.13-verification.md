# 0.7.13 verification — October 12, 2026

Version **0.7.13 / code 21** makes **Pause queue** expand the queue and open
its first waiting message for editing. Saving or cancelling leaves the queue
paused until explicit Resume. Pencil buttons still edit other waiting messages.
Sending and uncertain messages cannot be edited. The editor uses the current
queue entry rather than retaining a stale message snapshot.

Pause and edit callbacks report persistence failures: a failed pause does not
open an editor, and a failed save retains the draft and shows an error. The
feature and queue documentation were committed and pushed as `b2548c6`.

## Executed verification

- **120 JVM tests pass**, with no failures, errors or skips. Existing queue tests
  cover pause/claim ordering, edits, protected sending messages and persistence.
- Debug/release APKs and the instrumentation APK build successfully.
  Android lint reports **No issues found**.
- Three new Compose interaction tests compile: Pause opens editing and requires
  explicit Resume; failed pause/save preserves the appropriate UI state; Pause
  skips an already sending message. They were not executed on a device.
- Both APK signatures verify and certificates match published 0.7.12. The
  publisher validates package IDs, version 0.7.13/code 21 and minimum SDK 30.
- Authenticated loopback checks verify both update manifests and APK downloads,
  content lengths, SHA-256 digests and delivery copies. Unauthenticated requests
  and browser-Origin requests receive 401. Delivery checksums pass.
- `git diff --check` passes. Backend tests were not repeated for this Android change.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
python3 /tmp/custom-codex-0713-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.13.txt
node /tmp/custom-codex-0713-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

Previous delivery APKs are preserved under `artifacts/v0.7.12/`. Transport
checks use an ephemeral authenticated loopback host without production
credentials. Evidence is saved in `artifacts/v0.7.13-android-build.txt`,
`v0.7.13-signing-verification.json` and
`v0.7.13-local-update-verification.json`.

## Unverified

No Android device is connected. Pause/edit interactions, rotation behavior and
update installation remain unverified on a phone. No host restart was performed.
