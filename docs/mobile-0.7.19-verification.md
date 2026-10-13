# 0.7.19 verification — October 13, 2026

Version **0.7.19 / code 27** is published on the release and debug update
channels. Update through **Settings → App updates → Check for updates**.

Commands used to disappear after leaving a running chat and returning because
saved history could omit already-observed command items. A per-chat live cache
now receives timeline events for every chat, including output and completion
while another chat, project or the main menu is visible. Opening, refreshing
and loading earlier messages merge saved history with observed cards. Duplicate
IDs and replayed deltas are rejected; missing commands stay before the final
answer. Saved prefixes hydrate subsequent streamed output, and incomplete saved
output cannot erase fuller observed output or discard pending actor events.

Saved terminal status settles stale running commands; completion while away
clears the cached active turn. The existing command-output setting is respected,
including restoring hydrated output after enabling downloads. The cache uses
session memory for eight recent chats and 256 items per chat, clears for an
account/backend epoch change or sign-out, and forgets deleted chats. Process
restarts and eviction still depend on server-saved history. There are no extra
RPCs, command downloads or periodic history reads. No backend restart is needed.
See [the implementation guide](live-command-history.md).

## Executed verification

- All **199 JVM tests pass**, with no failures, errors or skips, including 19
  live-history regression tests. Android lint reports **No issues found**.
  Debug, release and instrumentation APKs build.
- Regression checks cover repeated reopening with a newer saved watermark,
  missing command cards, commands finishing while another chat is selected,
  replay deduplication, final-answer ordering, per-turn status versions,
  output hydration/preferences, pending actor deltas, account/epoch isolation,
  deletion, cache limits and expanded history windows.
- Both APK signatures verify and certificates match published 0.7.18. The
  publisher validates actual application IDs, version 0.7.19/code 27 and SDK 30.
- Authenticated loopback metadata and full downloads match the actual published
  APKs, including size, SHA-256, delivery copies and content type. Unauthenticated
  and browser-Origin requests return 401. Delivery checksums pass.
- Previous delivery APKs remain under `artifacts/v0.7.18/`; previous immutable
  published APKs are retained. `git diff --check` passes.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
python3 /tmp/custom-codex-0719-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.19.txt
node /tmp/custom-codex-0719-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

Evidence: `artifacts/v0.7.19-android-build.txt`,
`v0.7.19-signing-verification.json` and
`v0.7.19-local-update-verification.json`. Release APK size is 3,136,126 bytes;
debug APK size is 62,647,457 bytes.

## Unverified

No Android device is connected. Device navigation and phone installation remain
unverified; instrumentation checks compiled but did not run. Production
authenticated HTTPS downloads were not repeated; download verification used an
ephemeral loopback host and the actual published files.
