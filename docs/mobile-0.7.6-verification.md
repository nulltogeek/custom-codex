# 0.7.6 verification — October 11, 2026

Version **0.7.6 / code 14** is published on both release and debug update channels.
The jump-to-latest control is now a 48dp arrow-only button with an accessible
label. Archived chats have a dedicated bottom navigation section so they are
easy to find, with search, project filtering, pagination and restore confirmation.
Both toolbar Back and Android Back return to the originating section. Clearing
a project filter in Archived keeps that section open.

## Executed verification

- 76 Android JVM tests pass with zero failures, errors or skipped tests.
- Android lint reports **No issues found**.
- Debug, release and Android instrumentation APK builds succeed. A subsequent
  instrumentation build and lint check include the final test fixture changes.
- UI tests compile for archived project filtering, archived agent visibility,
  restore requests, empty state, archived-only pagination with a pending input,
  bottom navigation, both Back actions and clearing the project filter.
  The existing history-scroll test also checks the arrow's accessible label
  and absence of visible button text.
- All 3 update publication/transport tests pass. `npm run check` and
  `git diff --check` pass.
- Both APK signatures verify and their certificates match published 0.7.5 APKs.
  The publisher checks actual package IDs, version names/codes and minimum SDK.
- Published manifests advertise 0.7.6/code 14. A temporary loopback host serves
  both actual APKs with matching sizes, SHA-256 digests and delivery files.
  Authenticated metadata/downloads return 200; unauthenticated requests and
  downloads with browser Origin headers return 401.
- Delivery checksums pass. Previous delivery APKs are preserved in
  `artifacts/v0.7.5/`; immutable published APKs are retained.
- Direct deployed HTTPS `/healthz` returns `{"ok":true}`. The deployed update
  endpoint returns 401 without authentication.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  :app:lintDebug :app:assembleDebugAndroidTest
node --test bridge/test/app-updates.test.mjs
npm run check
node scripts/publish-app-update.mjs docs/release-notes/0.7.6.txt
sha256sum --check artifacts/SHA256SUMS
git diff --check
```

Evidence is saved in `artifacts/v0.7.6-android-build.txt`,
`v0.7.6-ui-test-build.txt`, `v0.7.6-signing-verification.json` and
`v0.7.6-local-update-verification.json`.

## Unverified

No Android device is connected. Native UI tests compiled but were not executed;
phone discovery, download and installation remain unverified. Authenticated
downloads through deployed HTTPS were not repeated; no account credentials were
read or changed. This release does not restart the running backend. Sent uploaded
image previews still need a later backend restart to load the image route added
in the previous release; the existing updater reads new manifests dynamically.
