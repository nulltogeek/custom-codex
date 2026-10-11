# 0.7.2 verification — October 11, 2026

The picker reconnect and attachment feedback fix is published as **0.7.2 / code
10** for both APK channels. See [the feature guide](mobile-0.7.2.md).

## Executed checks

- **53 Android JVM tests pass**, zero failures/errors/skips. New regressions check
  accepting picker results during Offline/Connecting states, authenticated HTTP
  uploads after closing the WebSocket, exact bytes and intermediate progress,
  acknowledgment/readiness, and persistence of failure thumbnails/error state.
- Android lint reports **No issues found**.
- Debug, release and Android instrumentation APK assembly succeeds.
- `node --test bridge/test/uploads.test.mjs`: **3 tests pass**, covering private
  storage, text/image native input, login/Origin enforcement and size limits.
- `npm run check` and a final `node --check scripts/verify-attachments.mjs` pass.
- Earlier in this turn, an authenticated HTTPS upload probe
  received **201** for both **64-byte** and **2,097,152-byte** binary fixtures
  through `https://codex.geeksun.ir`.
- Read-only inspection of the existing Codex Nginx vhost confirms
  `client_max_body_size 21m`, above the app/backend's 20 MB limit. The proxy limit
  did not cause these dropped picker results.
- Both new APKs pass signature verification and match their corresponding 0.7.1
  signing identities. Publishing validates package IDs, version/name/code,
  minimum SDK and SHA-256 metadata.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
```

Local evidence includes `artifacts/v0.7.2-android-build.txt`,
`v0.7.2-backend-upload-tests.txt`, `v0.7.2-signing-verification.json`,
`v0.7.2-attachments-proxy.json` and the local published update manifests.

## Limits and deployment

The later `node scripts/verify-attachments.mjs https://codex.geeksun.ir` run
stopped at login with **401**, before uploading any fixtures. A private local
comparison confirmed that the initial password reference no longer matches the
current account hash. This task did not print, change or reset passwords. Therefore the
script's final PNG/proxy check and a complete authenticated download of the new
APKs remain unverified using current credentials. This is an authentication
limitation of the verification, not a failed upload request.

The local manifests are published for the running server to read dynamically.
No backend or Nginx reload was needed, and existing phone sessions were preserved.

The release APK is **2,824,142 bytes**, and debug is **59,876,277 bytes**. Direct
inspection of both APKs also confirms their compiled code contains the new
attachment strip, upload-confirmation and failure labels.

The preview/cancel/progress instrumentation test and the new persistent-failure
UI test compile, but native UI execution was not repeated after the software
emulator's framework/package-service failures recorded in 0.7.1. A physical phone
is not attached. Android picker behavior, image compression and the complete
physical-phone upgrade/attachment flow remain unverified on-device.
