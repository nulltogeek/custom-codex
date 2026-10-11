# Verification and delivery

## Current standalone deployment — October 11, 2026

APK 0.4.1 includes username/password login, Markdown/code rendering, attachments, coding/conversation modes, access presets, themes/animations and verified in-app updates. The expired provider credential was replaced in the private service environment after confirming zero active turns and pending approvals. The service was restarted; phone sessions require fresh sign-in.

`node scripts/verify-server.mjs https://codex.geeksun.ir --local-inventory --model` passed through the deployed HTTPS/WSS domain: real streaming and a second-client follow-up completed without duplicate turns, login and HTTP/WebSocket rejection checks passed, complete inventory matched (eight models, 15 projects, 36 active catalogue entries and 96 archived entries), mirrored events/reconnect passed, and sign-out revoked the session. See [the credential repair and recovery procedure](local-wireguard-deployment.md#provider-authentication-repaired-on-october-11-2026).

Current build/update verification is recorded in [in-app updates](in-app-updates.md): 36 backend tests, 36 Android JVM tests, clean lint, debug/release/instrumentation builds, emulator installer confirmation, signing continuity, and complete authenticated downloads of both 0.4.1 APK channels passed. Physical-phone retry/installation, image understanding, actual model-triggered approvals, reboot/logout recovery and hardware performance remain unverified.

Build APKs, screenshots and raw verification logs under `artifacts/` are local delivery files excluded from Git. Historical links below refer to those local files; use the recorded commands to reproduce checks on another checkout.

## Historical standalone deployment — October 10, 2026

The user replaced the desktop-owned deployment requirement with a standalone server on the existing Linux machine and an existing VPS Nginx relay over WireGuard. The server is enabled and running on `192.168.1.2:8787`, using the unchanged `/home/nulltogeek/.codex/config.toml`. Android's endpoint is `https://codex.geeksun.ir`.

Fresh checks: **23 server tests passed**; real standalone initialization/effective config/history checks passed; authenticated HTTPS/WSS, unauthorized WebSocket/Origin rejection, two-client events and reconnect passed through Nginx; **15 projects and all 128 saved chats matched the local inventory**. Public non-VPN access returned 403. Existing APK archive/signature and manifest checksum passed. Generation returned HTTP 401 because the configured provider credential expired October 6. No physical phone, logout/reboot, real model approval or hardware performance check was performed in this deployment.

See [deployed setup and exact verification](local-wireguard-deployment.md), [proxied transport report](../artifacts/proxied-server-verification.json), [server tests](../artifacts/standalone-unit-tests.txt), [real standalone report](../artifacts/standalone-live-verification.txt) and [generation failure](../artifacts/local-server-verification.json). The earlier mandatory desktop restart/Tailscale follow-ups below apply only to optional desktop-sharing mode. Provider renewal was subsequently completed; hardware performance acceptance remains open.

The following records describe earlier Android and desktop-sharing checks.

Verified on Linux on 2026-10-08. The installable APK and prepared desktop launcher are delivered. Full deployment acceptance remains open: the original desktop has not been restarted through the launcher, Tailscale is not installed here, successful model generation was blocked by the configured provider, and hardware performance has not been measured.

## Delivered build

- [Release APK](../artifacts/codex-remote-release.apk): 2,548,990 bytes; Android 11+, minified and resource-shrunk, HTTPS required.
- [Debug APK](../artifacts/codex-remote-debug.apk): 59,862,200 bytes; separate application ID, local HTTP allowed only for validated emulator/loopback test hosts.
- [SHA256 manifest](../artifacts/SHA256SUMS): both packaged files passed `sha256sum -c artifacts/SHA256SUMS`; see [checksum verification](../artifacts/apk-checksums.txt).
- `apksigner verify --verbose artifacts/codex-remote-release.apk`: verified APK Signature Scheme v2, one signer; see [signature verification](../artifacts/apk-signature.txt). Personal sideload signing uses the local development key; retain that key for updates.

Release SHA256: `2fc00aede1a7b581a25d0da7da00018aee80dc8c855a1085bbc291f241ca6ce1`.

The native Kotlin/Compose client provides connection setup, chats, projects, conversation, changed-file review and settings. It uses the desktop-owned runtime through the reversible launcher, with encrypted pairing, cached history and unsent drafts. Dark/light/system theme selection is implemented. See [architecture](architecture.md) for compatibility adapters and reconciliation rules, and [setup/recovery](runbook.md) for launch, private access and installation.

## Bridge and shared runtime

| Check | Result | Evidence |
| --- | --- | --- |
| `npm test` | 17 passed, 0 failed, 0 skipped | [Bridge report](../artifacts/bridge-tests.txt) |
| `npm run check` | JavaScript syntax checks passed | `bridge/cli.mjs`, `bridge/broker.mjs`, `bridge/host.mjs` |
| `npm audit --omit=dev` | 0 vulnerabilities; `ws` 8.22.0 | Installed dependency lockfile |
| `npm run test:live` | Passed against bundled Codex 0.159.2; desktop-protocol and phone clients see one backend PID, the same ephemeral chat and mirrored events; stored history and turn pagination read successfully | [Live report](../artifacts/live-integration.txt) |
| `npm run test:desktop` | Passed with the actual installed Electron desktop in an isolated profile; backend is its descendant and the phone controls that desktop-owned runtime | [Desktop report](../artifacts/desktop-integration.txt) |
| `CODEX_VERIFY_MODEL=1 npm run test:live` | Failed at the successful-generation assertion: configured host provider returned HTTP 401, “Invalid API key” | [Generation probe](../artifacts/shared-runtime-check.txt) |

Broker tests cover preserved initialization, readiness without the desktop's absent `initialized` notification, colliding client IDs, response/event routing, first-valid competing approval answers, desktop-only requests, disconnects, bounded replay gaps, restart epochs, phone policy restrictions, host authentication and read-only history enumeration. Approval and restart cases use controlled protocol fixtures. They establish routing behavior; a real model-triggered approval is still unverified.

The optional generation probe started one ephemeral desktop turn and accepted a phone steer. Both clients received the same start/completion events without a duplicated turn, but completion was a provider error. Host authentication/configuration was preserved; no model-provider credentials were read or changed to work around this failure. The original user desktop and its running work were never stopped or relaunched.

## Complete local inventory

- Desktop and bridge both expose **15 saved projects**. Names, ordering and primary roots match; IDs match directly or through the observed migration mapping. All **44 legacy assignment entries** are preserved. See [project comparison](../artifacts/inventory-comparison.json).
- Public runtime enumeration returns **22 active + 96 archived** root-visible chats. The read-only persisted catalogue returns **32 active + 96 archived = 128 unique chats**, including children omitted from that public enumeration. Android merges this catalogue with live state and includes archived and projectless history.
- **132 local rollout files** were examined. Four noncanonical paths referred to IDs already present in the catalogue; **0 unique sessions were missing**. See [rollout comparison](../artifacts/rollout-inventory.json).
- Project snapshots preserve projects without chats. Metadata adapters read selected project/history fields and do not rewrite the desktop database or migration state.

## Android build and behavior

The final build used JDK 17, Gradle 8.11.1, AGP 8.9.2, Kotlin/Compose 2.1.20 and Android SDK 35. The checksum-pinned Gradle wrapper was executed successfully. Dependencies were downloaded through the permitted `localhost:8080` proxy and Google's alternate distribution CDN.

The build script ran `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`; **BUILD SUCCESSFUL**. See [build output](../artifacts/android-build.txt).

- **16 JVM tests passed**, with 0 failures/errors/skips: 12 model/reconciliation checks plus 4 production-transport checks through real loopback WebSocket frames. The transport checks cover out-of-order RPC replies, duplicate/older events, reconnect cursors and backend epochs, and connection loss after prompt acceptance with exactly one send after reconnect. See [model JUnit XML](../artifacts/android-unit-tests.xml), [transport JUnit XML](../artifacts/android-transport-tests.xml) and [additional build output](../artifacts/android-transport-checks.txt).
- **Lint: 0 warnings/errors**. See [lint XML](../artifacts/android-lint.xml).
- **4 focused instrumentation tests passed**, 0 skips/failures, on Android 11 x86_64 in **185.482 seconds** after the draft-persistence fix. See [instrumentation output](../artifacts/android-ui-tests.txt).
- Those four tests cover dark/light projects/settings; progressively exposed 1,000-line output and 1,200-line diff; Keystore encryption and draft persistence across store recreation; and the production pairing screen, project/chat navigation, Activity recreation and native RPC identity/events against the real bridge.
- After the foreground re-pair fix, the expanded native networking test was rerun separately on the current APK: **1 passed**, 0 failures/skips, in **94.783 seconds**. It also confirms removal of pairing and re-pairing in the same foreground Activity. See [re-pair instrumentation output](../artifacts/android-repair-test.txt).
- Final debug/release APK installation succeeded on the emulator; see [current release installation](../artifacts/release-install.txt). `bash -n` passed for the launcher, private-access and build scripts; `git diff --check` passed. No commit or push was performed.

The focused instrumentation command was:

```bash
adb -s emulator-5554 shell am instrument -w -r -e class \
'dev.nulltogeek.codexremote.RemoteUiTest#darkAndLightScreensAndProjects,dev.nulltogeek.codexremote.RemoteUiTest#longConversationAndIncrementalDiffAreInspectable,dev.nulltogeek.codexremote.RemoteUiTest#keystorePairingAndDraftsSurviveStoreRecreation,dev.nulltogeek.codexremote.RemoteUiTest#nativeNetworkingJoinsTheRealBridge' \
dev.nulltogeek.codexremote.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The test host used the bundled backend, port 18787, an ignored private token file and `adb reverse tcp:8787 tcp:18787`. The token was transferred through stdin into the disposable emulator without printing its contents. The native test observed 15 projects and the same ephemeral chat identity/events. These tests use USB/loopback forwarding, not Tailscale.

The owned test host was closed after verification; its desktop-protocol connection received mirrored notifications, recorded in [host completion](../artifacts/android-host-verification.json). The disposable emulator was also closed. The original desktop's default bridge port 8787 still refused connections at the final check, so original-session deployment remains unverified.

A further draft-persistence fix makes send acknowledgement clear only the saved text that was actually sent. If the user edits the next prompt while acknowledgement is in flight, the newer saved draft remains intact. Draft read/write/conditional-clear operations share a preferences lock across store instances. The additional transport-test dependency was downloaded directly from Maven Central after the optional localhost proxy became unavailable; production dependencies and TLS behavior were unchanged.

Unpairing now closes the connection without incorrectly marking the visible Activity as backgrounded. The native networking test also pairs again from the connection screen without leaving the app, verifying that its real bridge connection resumes immediately.

Visually inspected captures: [dark chats](../artifacts/screenshots/debug/dark-chats.png), [light projects](../artifacts/screenshots/debug/light-projects.png), [dark conversation](../artifacts/screenshots/debug/dark-conversation.png), [dark diff](../artifacts/screenshots/debug/dark-diff.png), and [light diff](../artifacts/screenshots/debug/light-diff.png). The light diff uses darker green additions for readable contrast.

## Performance

Release scrolling measurements use Android platform instrumentation with `Window.OnFrameMetricsAvailableListener` on the actual minified release APK. The fixture has 100 chats, a 100-item cached conversation and a 1,200-line diff. The harness records metrics rather than asserting that the 95% target passed; successful harness completion alone is not performance acceptance.

The available Android 11 emulator runs with **software CPU emulation, no KVM and SwiftShader**. Rendering was set to 540×960 at 210 dpi, preserving the logical size of 1080×1920 at 420 dpi. The disposable emulator had repeated System UI ANRs; its System UI process was restarted once and `hide_error_dialogs=1` was set to prevent dialogs from obstructing measurements. These adjustments were confined to the emulator. Such timings cannot establish physical-phone performance.

The release benchmark collected **643 frames**, with **0% within the 16,666,665 ns budget** at approximately 60 Hz and 0 dropped metric reports. Chats, the cached conversation and the large diff were all opened and scrolled. The measured APK SHA256 was `55b9e5db3ebaa4251c2cb15f72fc5f424d2cd434b32c20f74d477531e943703d`; this preceded the draft-persistence and foreground re-pair fixes. Rendering code was unchanged by those fixes, but the current APK's timing was not separately measured. The **95% target was not met**. Measurements and captures are in [frame metrics](../artifacts/release-frame-metrics.json), [instrumentation output](../artifacts/release-benchmark-test.txt) and [release captures](../artifacts/screenshots/release/). Streaming was not measured by this release harness.

To repeat only on a disposable emulator:

```bash
./gradlew :benchmark:assembleRelease
adb install -r artifacts/codex-remote-release.apk
adb install -r benchmark/build/outputs/apk/release/benchmark-release.apk
adb shell am instrument -w -r -e fixture true \
dev.nulltogeek.codexremote.benchmark/.NativeFrameRunner
```

The fixture writes synthetic private app data and is guarded against physical-device execution. Do not use this fixture to test a paired personal phone; measure that phone using its real data and production connection instead.

## Remaining acceptance checks

1. Finish desktop work, quit normally, and relaunch through `scripts/launch-desktop.sh`. Verify that original-session chats/work and the phone converge on that desktop runtime.
2. Install/sign in to Tailscale on the host and phone, enable private Serve HTTPS and pair using the generated application token. No tailnet HTTPS/WSS or real remote reconnect was verified here; `command -v tailscale` found no installed executable.
3. Restore the model provider through the normal desktop authentication flow, then verify successful generation, phone follow-up/steering and a real approval answered from either device without duplicate turns.
4. Test physical Android 11+ lifecycle/offline/reconnect behavior and benchmark scrolling plus streaming on hardware. `adb devices -l` showed only the emulator. The 95% frame-budget criterion remains unmet in software emulation and unverified on hardware.

These items remain open in the [implementation checklist](implementation-plan.md). The APK is delivered, but full acceptance and the implementation goal are not marked complete.
