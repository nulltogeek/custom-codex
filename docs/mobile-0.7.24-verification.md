# 0.7.24 verification — October 13, 2026

Version **0.7.24 / code 32** is published on release and debug update channels.
Install through **Settings → App updates → Check for updates**.

App connection deadlines previously threw coroutine cancellation exceptions,
which stopped foreground and notification reconnect jobs. Timed-out requests
could leave the screen reporting “Connected”; cancelled connection attempts
could leave their sockets open. These deadlines now produce recoverable IO
failures, discard the affected socket, release pending requests and allow the
existing retry loop to continue. Successful subscriptions reset both retry
delays to one second. Genuine caller cancellation still propagates, and uncertain
prompts are not automatically resent. See [connection recovery](connection-recovery.md).

## Executed verification

- Before the fix, eight real loopback socket regression tests ran with injected
  short deadlines: **six failed**. They reproduced upgrade/initialization/
  subscription/preference deadlines, accepted-prompt timeout and cancelled
  connection cleanup. Evidence: `artifacts/v0.7.24-connection-before.txt`.
- After the fix, all **241 JVM tests pass**, with zero failures, errors or skips.
  Nine new socket checks cover those paths, reconnection, pending-request
  release, ordinary read cancellation and HTTP 401 sign-in handling. Two new
  backoff checks cover bounded outages and resetting after recovery. Existing
  18 transport and eight queue-dispatch checks also pass, including replay,
  out-of-order replies and uncertain-delivery safeguards.
- Android lint reports **No issues found**. Debug, release and instrumentation
  APKs build successfully. Instrumentation tests compile but were not executed.
- Both APK signatures verify and certificates match published 0.7.23. Publication
  validates actual package IDs, version 0.7.24/code 32 and SDK 30.
- Authenticated loopback metadata and full APK downloads match actual published
  files by byte count, SHA-256, delivery copy and content type. Unauthenticated
  and browser-Origin requests return 401. Delivery checksums pass.
- Previous delivery APKs remain under `artifacts/v0.7.23/`; previous immutable
  published files are retained. `git diff --check` passes.

Linux deployment inspection was read-only:

- `systemctl --user show codex-remote.service -p ActiveState -p SubState -p MainPID -p ActiveEnterTimestamp`
  reported active/running, PID 1866533, started October 13 at 04:49:07 +0330.
- Direct `http://192.168.1.2:8787/healthz` and proxied
  `https://codex.geeksun.ir/healthz` probes both returned `{"ok":true}` with normal
  TLS verification for HTTPS. An unauthenticated HTTPS `/v1/rpc` upgrade request
  returned the expected HTTP 401.
- No service, backend, proxy or WireGuard restart/configuration change occurred.
  No production login or model request was made.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true :app:testDebugUnitTest
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
python3 /tmp/custom-codex-0724-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.24.txt
node /tmp/custom-codex-0724-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

Evidence: `artifacts/v0.7.24-unit-tests.txt`, `v0.7.24-android-build.txt`,
`v0.7.24-unit-test-summary.json`, `v0.7.24-signing-verification.json` and
`v0.7.24-local-update-verification.json`. Release size is 3,168,894 bytes; debug
size is 60,860,099 bytes.

## Unverified

No Android device is connected. The user's exact phone failure, installed
version, authenticated phone-to-proxy connection, WireGuard transitions,
notification service lifecycle and update installation remain unverified.
Linux health probes confirm listener reachability rather than authenticated
provider readiness. Production authenticated HTTPS downloads and model
generation were not repeated; transfer tests used an ephemeral loopback host
with the actual published APKs.
