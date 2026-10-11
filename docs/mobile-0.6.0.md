# Chat loading and composer 0.6.0

Version 0.6.0 uses version code 7. It focuses on long conversations and draft attachments.

## Scroll loading

Scroll upward in a conversation to reveal earlier messages automatically. The client shows the latest 60 timeline items initially and reveals earlier saved items in batches of 60. For hosts supporting paginated history, it then fetches older turns in pages of 20. Legacy Codex history still requires a complete thread read; the client progressively displays that response locally instead of calling unsupported pagination APIs. This reduces rendered content, not the size of that legacy network response. Already-cached earlier items can also be revealed while offline.

The timeline is anchored at its newest end with stable message keys. Loading earlier messages preserves the current message position. Replies follow the newest message while you are at the bottom; reading older messages disables that following. Commands, tool results, reasoning and file-change cards start collapsed with their summary/status visible. Tap their header to expand or close them. Code blocks have separate expand/collapse controls and Copy code remains available while collapsed. Expanded tool bodies scroll within a 280 dp panel, and code text within a 320 dp panel, keeping long output from taking over the conversation. Large expanded output is shown in bounded chunks; copying a command output copies its complete text.

The chats list automatically requests the next page near the bottom, including filtered/project/archived views. Only one catalogue request is allowed at a time. Search revisions reject late results from older requests; page results deduplicate chat identities and encode cursors. Pagination shows a small loading indicator in place, rather than activating the screen-wide loading state. Errors stop automatic retries and offer a tap-to-retry control.

## Draft attachments and settings

Selected files appear as composer tiles during preparation/upload. Images get a private local thumbnail, decoded off the UI thread, with a filename and upload state. Tap X to cancel a pending upload or remove a ready attachment. Cancellation cancels the HTTP call and prevents a late result from reattaching the file. Removing a draft attachment does not delete previously uploaded server files. Thumbnail cache files are optional and may be reclaimed by Android; older drafts without a local thumbnail retain a file/image icon. Originals remain bounded to 20 MB and submitted images retain the existing 2048-pixel resizing limit. New previews are at most 256 pixels.

Pending uploads are canceled when opening another conversation. Ready attachments remain saved with their original chat. Send stays disabled while draft attachments upload; removal becomes disabled only during an already-started send.

The composer has one **Chat settings** control showing the current mode/model. It opens a single panel containing **Codex / Conversation**, model, reasoning and access. Conversation remains read-only. Mode/access changes remain disabled during active turns; their full descriptions stay in the panel.

## Update and verification

Use **Settings → App updates → Check for updates** to discover the release after publication. Install with the existing signing identity. The previous delivery is preserved in `artifacts/v0.5.0/`. Publication requires no backend or Nginx restart.

The command `./gradlew --offline --no-daemon -PcodexGoogleCdn=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest` passed all 39 JVM tests, clean Android lint and all three APK builds. The tests include upload cancellation through a real HTTP test socket, history window/refresh behavior and preview persistence compatibility. Evidence: `artifacts/v0.6.0-android-build.txt`. Both APK signing certificates match 0.5.0 (`artifacts/v0.6.0-signing-verification.json`). Physical-phone appearance, upload-provider behavior and hardware performance remain unverified until tested on your phone.

Eight focused Android 11 UI checks passed across two runs: command collapse/expansion and large diffs; image previews, upload-cancel/removal controls and send locking; dark/light chats/projects; code collapse/copy and attachment choices; scroll-triggered older history with stable message position while prepending and streaming; project folders with 1.3× text; catalogue prefetch with explicit retry after errors; and combined settings plus keyboard-visible composer at 320 dp. The first run passed its first two checks, then Android interrupted the next Activity with an input-dispatch timeout. After reducing the software emulator to 540×960 at 210 dpi, the six remaining checks passed in 171.238 seconds. Evidence: `artifacts/v0.6.0-android-instrumentation.txt` and `artifacts/v0.6.0-android-instrumentation-retry.txt`.

The emulator also encountered Android’s “Lost network stack” boot failure twice. Its `connectivity/always_ratelimit_networkstack_crash` setting was temporarily enabled from the original unset value to allow UI verification. This affects the emulator only and was restored to unset after checking the release. The temporary size override was reset and ADB root used only to retrieve named test captures was returned to non-root. No emulator timing establishes phone performance.

Publication verified actual package/version/minimum-SDK metadata and both signatures. Release is 2,774,990 bytes; debug is 60,598,851 bytes. `node scripts/publish-app-update.mjs docs/release-notes/0.6.0.txt` published both authenticated update channels without restarting the backend or Nginx.

`node scripts/verify-app-update.mjs https://codex.geeksun.ir --local` passed complete authenticated downloads of both 0.6.0 APKs, exact byte counts/SHA-256, matching published metadata, and unauthorized/browser-Origin rejection (`artifacts/v0.6.0-update-proxy.json`). Delivery checksums passed. Android accepted the release upgrade over the existing emulator package (`artifacts/v0.6.0-release-install.txt`).

Reviewed captures are in `artifacts/screenshots/v0.6.0/`, including image preview/cancel, collapsed command output, code/composer, chat settings and the visible keyboard. At 320 dp width, the empty composer measured 99.81 dp and the long-draft composer 182.10 dp (`artifacts/v0.6.0-layout-metrics.json`). These checks use fixtures and a software emulator; phone performance and real document/image-provider behavior still need hardware verification.
