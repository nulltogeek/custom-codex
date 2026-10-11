# 0.7.1 verification — October 11, 2026

The active-writer recovery described in [the feature guide](mobile-0.7.1.md) is
published as version **0.7.1 / code 9** for both APK channels.

## Executed checks

- `npm run check`: all listed JavaScript modules pass syntax checks.
- `npm test`: **44 tests pass**, zero failures.
- `npm run test:writer`: two isolated real Codex **0.159.2** processes reproduce
  the writer rejection and successfully fork saved question/answer history.
  Conversation access stays read-only, the source rollout remains unchanged and
  the original owner stays connected. No model turn is started.
- Android command below: **49 JVM tests pass**, zero failures/errors/skips;
  Android lint reports **No issues found**; debug, release and instrumentation
  APK assembly succeeds.
- `apksigner verify --print-certs`: both new APKs verify and have the same signing
  identity as their 0.7.0 counterparts.
- `node scripts/verify-server.mjs https://codex.geeksun.ir --local-inventory`:
  username/password login, HTTP/WebSocket authorization, browser-Origin
  rejection, inventory, model discovery, two-client events, reconnect and session
  revocation pass. At verification, the inventory had 15 projects, 36 active chats
  and 96 archived chats, matching the local database.
- A deployed `thread/fork` contract probe with a nonexistent source accepts named
  conversation/access controls, reaches the expected missing-rollout rejection
  and creates no chats or turns. This checks that the deployed backend loaded the
  new fork handling.
- `node scripts/verify-app-update.mjs https://codex.geeksun.ir --local`: complete
  authenticated downloads of both APKs pass exact size, SHA-256 and local
  manifest checks; unauthorized and browser-Origin requests are rejected.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
```

The published release APK is **2,824,142 bytes**, and debug is **60,881,587 bytes**.
Local evidence is in `artifacts/v0.7.1-android-build.txt`,
`v0.7.1-backend-tests.txt`, `v0.7.1-writer-live.txt`,
`v0.7.1-signing-verification.json`, `v0.7.1-server-proxy.json`,
`v0.7.1-fork-proxy.json` and `v0.7.1-update-proxy.json`.

## Deployment

Immediately before reloading the standalone backend,
`node scripts/verify-runtime-idle.mjs` confirmed **one loaded idle chat and zero
pending approvals**. One `codex-remote.service` restart loaded the fork policy
mapping and invalidated in-memory phone sessions; sign in again with your current
password. Passwords, desktop processes and Nginx configuration were unchanged.
The restart was followed by successful authenticated HTTPS/WSS checks.

## Device limits

The new instrumentation APK compiles, but the three new native UI checks could
not run. The software-only Android 11 emulator restarted its framework during
installation, first returning `Can't find service: package`, then
`Failure calling service package: Broken pipe (32)` after services recovered.
These installation attempts are not passing UI tests or confirmed APK upgrades.
No physical phone is attached, so the complete recovery flow and APK upgrade on
the user's phone remain unverified. No new provider generation check was performed
for this patch.
