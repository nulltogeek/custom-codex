# 0.7.22 verification — October 13, 2026

Version **0.7.22 / code 30** is published on release and debug update channels.
Install through **Settings → App updates → Check for updates**.

A live user item and its saved-history counterpart previously appeared twice
when their item IDs differed. The merge now reconciles equivalent user input
within the same turn, uses the saved ID, and retains aliases for subsequent
completion events. Matching reserves shared IDs and pairs occurrences one-to-one.
Different turns and attachments remain separate. Buffered events and visible-state
updates use the same aliases, as does the newer-item overlay during refresh.
Existing missing-command retention and command-output handling remain intact.
See [message identity](message-identity.md).

This fixes a reproducible live/history merge path; no physical-phone trace was
available. Sending and queue dispatch were not changed, and there are no new
RPCs or automatic retries. No host restart is needed.

## Executed verification

- All **223 JVM tests pass**, with zero failures, errors or skips. The 13 new
  identity regression checks reproduce changed live/saved IDs, repeated refresh
  and reopening, late completion, history-before-actor ordering, visible-state
  hydration races, changing saved IDs, stale duplicates, newer distinct input,
  repeated occurrences/turns, text metadata and differing attachments.
- Existing 19 live-history tests still pass, including command retention/output,
  terminal status, replay deduplication, cache limits and session/epoch isolation.
- Android lint reports **No issues found**. Debug, release and instrumentation
  APKs build successfully. Instrumentation checks compile but were not executed.
- Both signatures verify and certificates match published 0.7.21. Publication
  validates actual package IDs, version 0.7.22/code 30 and SDK 30.
- Authenticated loopback metadata and full downloads match actual published APKs
  by byte count, SHA-256, delivery copy and content type. Unauthenticated and
  browser-Origin requests return 401. Delivery checksums pass.
- Previous delivery APKs remain under `artifacts/v0.7.21/`; previous immutable
  published files are retained. `git diff --check` passes.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true :app:testDebugUnitTest
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
python3 /tmp/custom-codex-0722-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.22.txt
node /tmp/custom-codex-0722-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

Evidence: `artifacts/v0.7.22-unit-tests.txt`, `v0.7.22-android-build.txt`,
`v0.7.22-signing-verification.json` and `v0.7.22-local-update-verification.json`.
Release size is 3,152,506 bytes; debug size is 60,810,943 bytes.

## Unverified

No Android device is connected. Phone interaction and installation remain
unverified. Identity regressions use synthetic events/history; the deployed
backend and a real phone send were not used to reproduce the issue. Production
authenticated HTTPS downloads were not repeated; transfer checks used an
ephemeral loopback host with the actual published APKs.
