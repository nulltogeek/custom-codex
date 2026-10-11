# 0.7.4 verification — October 11, 2026

Pull-to-refresh for Chats and Projects is published as **0.7.4 / code 12** on
both APK channels. See [the feature guide](mobile-0.7.4.md).

## Executed checks

- **62 Android JVM tests pass**, zero failures/errors/skips. Refresh eligibility
  covers disconnected/signed-out states, overlapping actions/refreshes, and
  allowing refresh during pagination. The existing stale-page test now also
  verifies that an ongoing refresh prevents a page request from starting.
  Catalogue reconciliation tests continue to verify retained loaded pages and
  authoritative metadata updates.
- Android lint reports **No issues found**.
- Debug, release and Android instrumentation APK assembly succeeds.
- Both APKs pass signature verification and retain their corresponding 0.7.3
  signing identities. Their compiled DEX contains the refresh spinner, gesture
  and accessibility action labels.
- The publisher validates package IDs, version/name/code, minimum SDK, APK sizes
  and SHA-256 metadata before updating manifests atomically.
- An isolated loopback host serves both **actual published APKs** with matching
  manifests and SHA-256 digests. Authenticated metadata/download requests return
  **200**; unauthenticated and browser-Origin metadata/download requests return
  **401**. Release is **2,840,526 bytes**, debug is **59,892,661 bytes**. The probe
  uses an ephemeral test credential without accessing the deployed account.
- `systemctl --user is-active codex-remote.service` reports **active**.
- `git diff --check` passes.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
node scripts/publish-app-update.mjs docs/release-notes/0.7.4.txt
systemctl --user is-active codex-remote.service
git diff --check
```

Local evidence is saved in `artifacts/v0.7.4-android-build.txt`,
`v0.7.4-signing-verification.json` and `v0.7.4-local-update-verification.json`.
Previous APK copies are retained in `artifacts/v0.7.3/`.

## Limits

The new native tests exercise downward swipes, spinner display, retained chat
filters/loaded rows, duplicate-pull blocking, empty project lists and offline
gesture blocking. They compile into the instrumentation APK but **were not
executed**. `adb devices` returned no attached devices; the previously failing
software emulator was not restarted. Physical gesture behavior, accessibility
interaction and installation on the user's phone remain unverified.

Authenticated downloads through `https://codex.geeksun.ir` were not repeated:
the initial password reference was already confirmed outdated in 0.7.2. The
current password was not read, changed or requested. Local transport checks do
not verify the deployed WireGuard/Nginx path or the user's current login.

This release changes only Android code and documentation. The running backend
reads the new manifests dynamically; no backend restart or Nginx reload was
needed. Install through **Settings → App updates** with the current login.
