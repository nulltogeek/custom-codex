# 0.7.7 verification — October 11, 2026

Version **0.7.7 / code 15** is published on the release and debug update channels.
This release places Stop beside Send, adds saved editable message queues with
reordering, shows commands and final changes in collapsible cards, adds copy
buttons to both message types, and provides a clickable working heartbeat.

Messages sent during active work wait for a new turn instead of steering the
current turn. Queues retain text, uploaded attachments and the chosen model and
access options. Stop, editing, failed turns and uncertain delivery pause queues
for review. Queue dispatch runs while this phone's app is open and connected;
saved queues require Resume after relaunch. See [the queue guide](message-queue.md).

## Separate feature commits

Each commit below was pushed separately to `origin/main`.

| Change | Commit |
| --- | --- |
| Stop beside Send | `cb373ae` |
| Collapsed running and finished commands | `562d615` |
| Expand/collapse final changes | `6787359` |
| Copy user and Codex messages | `a935c29` |
| Working heartbeat and command activity panel | `662a0f9` |
| Saved queues, editing, attachment removal and reordering | `c707d4c` |
| New-chat history compatibility and lightweight active-status checks | `365bd0c` |

## Executed verification

- 93 Android JVM tests pass, with zero failures, errors or skips.
- Queue tests cover ordering across chats, text and attachment edits, locked
  in-flight entries, stale readiness checks, account isolation, persistence,
  explicit retry/resume after uncertain delivery, failure retention, storage
  failures, runtime ownership rejection and archived chats. RPC tests verify
  queued input never steers running work, uses saved inputs/options, and blocks
  failed or interrupted turns before their notifications arrive. Additional
  tests cover new chats without rollouts, ephemeral and paginated chats, and
  metadata-only checks while work runs.
- Android lint reports **No issues found**. Debug, release and instrumentation
  APK builds succeed. Compiler deprecation warnings remain for existing icon
  and clipboard APIs; the new message copy control uses the same clipboard API
  as the existing code-block copy control.
- Native UI tests compile for Stop placement, collapsed commands and changes,
  full-message copying, the heartbeat panel, queue edits/reordering/removal,
  uncertain-delivery review and persistence isolation. They were not executed.
- All 3 update publication/transport tests pass. `npm run check` and
  `git diff --check` pass.
- Both APK signatures verify and signing certificates match published 0.7.6.
  The publisher verifies actual package IDs, version names/codes and minimum SDK.
- Published manifests advertise 0.7.7/code 15. A temporary authenticated loopback
  host serves both actual APKs with matching sizes, SHA-256 digests and delivery
  files. Metadata and downloads return 200 with authentication; unauthenticated
  requests and downloads with browser Origin headers return 401.
- Delivery checksum verification passes. Previous delivery APKs are preserved
  in `artifacts/v0.7.6/`, and immutable published APKs remain available.
- Direct deployed HTTPS `/healthz` returns `{"ok":true}`. The deployed update
  endpoint returns 401 without authentication. The user service is active.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
node --test bridge/test/app-updates.test.mjs
npm run check
node scripts/publish-app-update.mjs docs/release-notes/0.7.7.txt
sha256sum --check artifacts/SHA256SUMS
git diff --check
```

Build, signing and update evidence is saved in
`artifacts/v0.7.7-android-build.txt`, `v0.7.7-signing-verification.json` and
`v0.7.7-local-update-verification.json`. Release APK size is 2,922,442 bytes;
debug APK size is 61,499,495 bytes.

## Unverified

No Android device is connected. Phone UI behavior, background/foreground queue
transitions, update discovery and installation remain unverified on a device.
Authenticated downloads through deployed HTTPS were not repeated; no account
credentials were read or changed. The running backend was not restarted.
Sent uploaded-image previews still need a later backend restart to load the
image route from the earlier release. Existing update routes read new manifests
dynamically.
