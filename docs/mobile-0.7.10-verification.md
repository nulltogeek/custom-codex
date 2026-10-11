# 0.7.10 verification — October 11, 2026

Version **0.7.10 / code 18** adds interactive question cards, option selection,
custom text and explicit submission. Async questions remain visible while ordinary
progress commentary stays hidden. Answers steer the same running task immediately
or start the next turn after it completes; they bypass the normal message queue.
Blocking questions use the same controls and remain reachable above chat history.
The library exposes every pending request independently. Failures retain answers
in the open dialog, and ambiguous message delivery requires a history check.

Protocol fields were inspected in generated experimental Codex 0.159.2 schemas:
`agentMessage.delivery`, `agentMessage.questions`, `AsyncUserInputQuestion`,
`ToolRequestUserInputParams`, and `TurnSteerParams`. The bridge already forwards
these notifications and allows `turn/steer`; no new host endpoint is needed.

An existing reconnect test failed during repeat verification: an event could
reach its consumer before the reconnect cursor was updated. The client now
publishes the cursor before channel delivery, with volatile visibility, and rolls
back when the channel rejects delivery. A deterministic regression test resumes
an unconfined consumer inside the channel send and asserts the current cursor.
The question feature is committed as `38b657f`; this correction is committed
separately as `1e67751`. Both commits are pushed to `origin/main`.

## Executed verification

- **111 JVM tests pass**, zero failures, errors or skips. New tests verify live
  and reloaded async question visibility, synchronous IDs/secret flags, multiple
  answers, free text, immediate steering with the live turn ID, new-turn settings,
  stale/already-answered/archived guards, and no automatic retry or fallback after
  a rejected steer. Existing queue, history and reconnect coverage also passes.
- **50 bridge tests pass**, including real async-question notification forwarding
  and matching `turn/steer` request/response routing without an approval request.
- Debug/release APKs and the instrumentation APK build successfully. Android lint
  reports **No issues found**. Existing icon/clipboard deprecation warnings remain.
- New instrumentation tests compile for options plus custom text, multi-question
  validation, answer retention after failure/disconnection, disabled submissions
  while sending and live async cards. They were not run on a device.
- Both APK signatures verify, and certificates match published 0.7.9. Publication
  validates actual package IDs, version 0.7.10/code 18 and minimum SDK 30.
- Authenticated loopback checks verify both real update manifests and APKs,
  lengths, SHA-256 digests and delivery copies. Unauthenticated requests and
  browser-Origin requests return 401. Delivery checksums pass.
- `npm run check` and `git diff --check` pass.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
npm test
npm run check
python3 /tmp/custom-codex-0710-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.10.txt
node /tmp/custom-codex-0710-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

The temporary artifact script verifies signing identities, preserves 0.7.9
APKs under `artifacts/v0.7.9/` and computes delivery checksums. The transport
script creates an ephemeral authenticated loopback host, checks actual published
files and closes it. Production login credentials are not used. Evidence is saved
in `artifacts/v0.7.10-android-build.txt`, `v0.7.10-backend-tests.txt`,
`v0.7.10-signing-verification.json` and `v0.7.10-local-update-verification.json`.

## Unverified and pending

No Android device is connected. Touch, keyboard, screen-reader and rotation
behavior, actual Codex question responses on a phone, and in-app update discovery
and installation remain unverified. No answer was injected into a live task.

The running host was not restarted because it owns this active task. Its process
still predates the uploaded-image endpoint and native deletion changes. The
uploaded image exists, but image retrieval on that process remains pending a safe
reload after active work finishes. These question changes do not require a reload.
See [0.7.9 verification](mobile-0.7.9-verification.md) for the prior host requirements.
