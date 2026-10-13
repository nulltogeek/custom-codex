# 0.7.20 verification — October 13, 2026

Version **0.7.20 / code 28** is published on release and debug update channels.
Install through **Settings → App updates → Check for updates**.

Archived chats now support long-press selection and tapping additional rows to
select or deselect. A fixed count/Delete bar opens confirmation for those chats.
Cancel retains selection; Back or the close button exits selection. Checkbox
states, row highlights and accessible long-press expose the interaction. Swipe,
favorite and options actions are suppressed during selection. Filter, account
and destination changes clear selection; restored/deleted rows are pruned, and
stale confirmation targets disable Delete.

Deletion retains the existing running-task, pending-request and queue guards.
The client checks lightweight host metadata and eligibility before each delete,
uses the shared input mutex, and claims loading immediately to prevent duplicate
batches. Successful acknowledgements trigger existing per-chat cleanup. Failure
stops the batch and reports its completed count; uncertain requests are never
automatically retried. The library refreshes once after the batch. Single-chat
swipe and toolbar deletion share this implementation. No host restart is needed.
See [the feature guide](archive-selection.md).

## Executed verification

- All **210 JVM tests pass**, with zero failures, errors or skips. The 11 new
  batch-deletion tests cover unique sequential targets, request shape and
  lightweight reads, task/queue/context changes, partial failures, uncertain
  acknowledgements, session expiry and cancellation.
- Android lint reports **No issues found**. Debug, release and instrumentation
  APKs build successfully. Six new UI checks compile for selection, deselection,
  confirmation/cancel, exact delete targets, swipe suppression, accessible
  long-press, Back, disabled Delete, stale dialogs and filter changes.
- Both APK signatures verify and certificates match published 0.7.19. Publication
  validates actual application IDs, version 0.7.20/code 28 and SDK 30.
- Authenticated loopback metadata and full downloads match actual published APKs
  by byte count, SHA-256, delivery copy and content type. Unauthenticated and
  browser-Origin requests return 401. Delivery checksums pass.
- Previous delivery APKs remain under `artifacts/v0.7.19/`; previous immutable
  published APKs are retained. `git diff --check` passes.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
python3 /tmp/custom-codex-0720-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.20.txt
node /tmp/custom-codex-0720-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

Evidence: `artifacts/v0.7.20-android-build.txt`,
`v0.7.20-signing-verification.json` and
`v0.7.20-local-update-verification.json`. Release size is 3,152,510 bytes;
debug size is 60,794,563 bytes.

## Unverified

No Android device is connected. The native selection checks compiled but did
not run, and phone installation remains unverified. Deletion behavior was tested
with fake RPC responses; no real user chats were deleted for verification.
Production authenticated HTTPS downloads were not repeated; transfer validation
used an ephemeral loopback host with the actual published APK files.
