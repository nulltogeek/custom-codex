# 0.7.5 verification — October 11, 2026

The chat display changes are published as **0.7.5 / code 13** on both release
and debug update channels. The previous development APK retained code 12 and
was not published, so installed 0.7.4 apps could not discover it. The updater
requires a strictly higher build number.

The publisher now rejects changed APKs with the same or lower build number.
An identical published APK can be republished. Both existing signing identities
are retained, allowing upgrades over the previous packages.

## Executed checks

- 76 Android JVM tests pass with zero failures, errors or skipped tests.
- Android lint reports **No issues found**.
- Debug, release and Android instrumentation APK builds succeed.
- All 3 update publication/transport tests pass, including rejection of changed
  APKs with reused build numbers, downgrades and application ID changes.
- `npm run check` and `git diff --check` pass.
- Both APK signatures verify; their certificates match the published 0.7.4 APKs.
  Actual package IDs, version names/codes and minimum SDK pass publisher checks.
- Both published manifests advertise 0.7.5/code 13. An isolated loopback host
  serves the actual published APKs with exact sizes and matching SHA-256 digests.
  Authenticated metadata/downloads return 200; unauthenticated and browser-Origin
  requests return 401. Release is 2,873,294 bytes; debug is 59,974,581 bytes.
- Delivery APK checksums pass. Published 0.7.4 delivery files are preserved in
  `artifacts/v0.7.4/`; immutable update APKs are retained.
- Direct HTTPS `/healthz` returns 200, and the deployed update metadata endpoint
  returns 401 without login. The existing user service reports **active**.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
node --test bridge/test/app-updates.test.mjs
npm run check
node scripts/publish-app-update.mjs docs/release-notes/0.7.5.txt
sha256sum --check artifacts/SHA256SUMS
```

Evidence is saved in `artifacts/v0.7.5-android-build.txt`,
`v0.7.5-signing-verification.json` and
`v0.7.5-local-update-verification.json`.

## Limits

Phone discovery, downloading and Android installation remain unverified. Native
UI tests compiled but were not executed. The authenticated deployed HTTPS
download was not repeated; no account password was read or changed. The first
default-proxy HTTPS probe failed during TLS; a direct probe using
`curl --noproxy '*'` succeeded.

This publication does not restart the running backend. Sent uploaded-image
previews require a separate backend restart to load the new image route; update
metadata and APK downloads already exist and read the new manifests dynamically.
