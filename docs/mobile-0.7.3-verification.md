# 0.7.3 verification — October 11, 2026

The Chats list pagination fix is published as **0.7.3 / code 11** for release and
debug channels. See [the feature guide](mobile-0.7.3.md).

## Executed checks

- **61 Android JVM tests pass**, zero failures/errors/skips. Eight new tests cover
  catalogue selection, paging archived agents, duplicate/stale request rejection,
  explicit retry/error scoping, bottom prefetch, retention of scrolled pages,
  authoritative complete refresh/archive moves, and overlapping page deduplication.
- Android lint reports **No issues found**.
- Debug, release and Android instrumentation APK builds succeed.
- `node --test bridge/test/host.test.mjs`: **5 tests pass**, including the
  65-record, two-page read-only history/search regression and host authentication.
- `npm run check` and `git diff --check` pass.
- Both new APKs pass signature verification and match their corresponding 0.7.2
  signing identities. The publisher validates package IDs, version/name/code,
  minimum SDK, sizes and SHA-256 metadata.
- An isolated loopback host serves the **actual published APKs** with matching
  manifests and checksums. Authenticated downloads return **200** with exact
  lengths: release **2,824,142 bytes**, debug **59,876,281 bytes**. Unauthenticated
  requests and browser Origins return **401**. The probe uses an ephemeral test
  credential and does not access or change the deployed account.
- Direct inspection of both APKs' compiled DEX confirms the new searching label
  and catalogue error scoping are present; the debug APK also contains the new
  pagination helper class (release class names are optimized).
- `systemctl --user is-active codex-remote.service` reports **active**.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
node --test bridge/test/host.test.mjs
npm run check
git diff --check
node scripts/publish-app-update.mjs docs/release-notes/0.7.3.txt
```

Local evidence includes `artifacts/v0.7.3-android-build.txt`,
`v0.7.3-backend-history-tests.txt`, `v0.7.3-javascript-check.txt`,
`v0.7.3-signing-verification.json` and `v0.7.3-local-update-verification.json`.
The previous APK copies are retained in `artifacts/v0.7.2/`.

## Limits and deployment

The existing end-of-list/retry UI check and two new checks for stable append,
avoiding eager all-page fetching, sparse project filters and archived agents
compile into the instrumentation APK. They were **not executed on-device**:
no physical phone is attached and the available software emulator previously
suffered Android framework/package-service failures. Real touch scrolling,
refresh scroll anchoring and phone installation remain unverified.

Authenticated downloads through `https://codex.geeksun.ir` were not repeated.
The initial local password reference was already confirmed outdated during
0.7.2; the current password was not read, reset or requested. The loopback checks
verify the published files and HTTP transport, but do not validate the deployed
WireGuard/Nginx path or the user's current login.

The publisher updates manifests atomically for the running server to read
dynamically. No backend restart or Nginx reload was needed. Update on the phone
through **Settings → App updates** using the current login.
