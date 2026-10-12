# 0.7.14 verification — October 12, 2026

Version **0.7.14 / code 22** adds a pencil beside Copy for sent user messages.
**Save & resend** forks the conversation before the containing exchange and
resends the revision in the copied chat. The original conversation and its
composer draft remain available. Original non-text inputs are retained unless
removed. The editor provides Stop work for running tasks and displays stop errors.
See [the feature guide](sent-message-editing.md) for the same-turn follow-up
behavior and persistence limits. The feature was committed and pushed as `06ad8e1`.

Before sending, the app reads authoritative history, including older pages, and
verifies the copied chat contains exactly the earlier turns. Acknowledged copies
are reused on preparation retries. An unconfirmed copy blocks resend. The copied
draft is saved atomically before the existing guarded send flow runs. Editing
never rewrites source history or reverts existing files and external actions.

## Executed verification

- **129 JVM tests pass**, zero failures/errors/skips. Nine new tests cover fork
  options and boundaries, preserving/removing attachment inputs, image-only and
  empty revisions, same-turn earlier user input, older pagination, running/missing/
  unavailable history, ignored fork boundaries, active copies and read failures.
- **51 bridge tests pass**, including a new mobile fork-boundary test.
  `npm run check` and the new runtime script's JavaScript syntax check pass.
- `node bridge/test/sent-edit-live-check.mjs`: two isolated real app-server
  processes verify forks before the first and a later message, reject a missing
  boundary and keep the original rollout unchanged and its owner connected.
  Conversation access remains read-only. No model turn is started.
- Android debug/release APKs and the instrumentation APK build successfully.
  Android lint reports **No issues found** for the final unchanged source tree.
- Three new Compose tests compile for the pencil/copy controls at 320 dp,
  revision/attachment/cancel controls, and running/disconnected/busy/unconfirmed
  resend guards. They were not executed on a device.
- Both APK signatures verify and certificates match published 0.7.13. The
  publisher checks package IDs, version 0.7.14/code 22 and minimum SDK 30.
- Authenticated loopback update metadata and APK downloads pass size, SHA-256
  and delivery-copy checks for both channels. Unauthenticated and browser-Origin
  requests receive 401. Delivery checksums and `git diff --check` pass.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
npm test
npm run check
node --check bridge/test/sent-edit-live-check.mjs
node bridge/test/sent-edit-live-check.mjs
python3 /tmp/custom-codex-0714-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.14.txt
node /tmp/custom-codex-0714-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

Previous delivery APKs are preserved under `artifacts/v0.7.13/`. Evidence is in
`artifacts/v0.7.14-android-build.txt`, `v0.7.14-backend-tests.txt`,
`v0.7.14-backend-check.txt`, `v0.7.14-sent-edit-live.txt`,
`v0.7.14-signing-verification.json` and
`v0.7.14-local-update-verification.json`. Runtime tests use isolated fixture
homes and update transport uses an ephemeral loopback host, without production
credentials or conversations.

## Unverified

No Android device is connected. Phone interaction, process/activity lifecycle,
Stop-to-edit behavior and installing the update remain unverified on a phone.
Actual provider reply generation after a revision was not exercised. No backend
restart was performed or required; this feature uses the existing fork interface.
