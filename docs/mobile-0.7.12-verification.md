# 0.7.12 verification — October 12, 2026

Version **0.7.12 / code 20** places user message bubbles on the physical right
and Codex answers on the physical left. Opposite margins make the two sides
visible. `AbsoluteAlignment` preserves their positions in right-to-left layouts;
text retains its natural reading direction. The layout change was committed and
pushed separately as `a986132`.

## Executed verification

- **120 JVM tests pass**, with no failures, errors or skips.
- Debug/release APKs and the instrumentation APK build successfully.
  Android lint reports **No issues found**.
- Both APK signatures verify, and certificates match published 0.7.11.
  The publisher validates the real package IDs, version 0.7.12/code 20 and SDK 30.
- Authenticated loopback checks verify both update manifests and APK downloads,
  content lengths, SHA-256 digests and delivery copies. Unauthenticated requests
  and browser-Origin requests are rejected with 401. Delivery checksums pass.
- `git diff --check` passes. Backend tests were not repeated for this layout change.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
python3 /tmp/custom-codex-0712-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.12.txt
node /tmp/custom-codex-0712-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

Signing checks compare against 0.7.11 and preserve its delivery APKs under
`artifacts/v0.7.11/`. Transport checks use an ephemeral authenticated loopback
host, with no production credentials. Evidence is saved in
`artifacts/v0.7.12-android-build.txt`, `v0.7.12-signing-verification.json`
and `v0.7.12-local-update-verification.json`.

## Unverified

No Android device is connected. Visual alignment in both layout directions,
text/image wrapping, copy controls and update installation remain unverified
on a phone. No host restart is required for this layout change.
