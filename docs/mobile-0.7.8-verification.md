# 0.7.8 verification — October 11, 2026

Version **0.7.8 / code 16** is published on the release and debug update channels.
Archived chats offer Restore and confirmed Delete. Library destinations now use
a sidebar. Settings has opt-in task completion alerts with a separate read-only
background monitor, duplicate prevention and links to the completed chat.
See [chat controls](chat-display.md) and [task notifications](task-notifications.md).

## Separate commits

Each change was committed and pushed separately to `origin/main`:

| Change | Commit |
| --- | --- |
| Confirmed deletion for idle archived chats | `4caa903` |
| Sidebar navigation | `9f40ba0` |
| Completion notifications and background monitoring | `67e88f6` |
| Isolate standalone test interfaces from host environment | `8d73117` |
| Invalidate pagination and refresh after deletion | `f0e2e47` |

## Executed verification

- **100 Android JVM tests pass**, zero failures, errors or skips. New tests cover
  archived-only deletion, queues and running-work guards, selected chat/favorite
  cleanup, pagination invalidation, successful completion filtering, duplicate
  events, persisted identities, storage failure, bounded history and account
  isolation for notification links.
- **49 backend tests pass**, including deletion routing, parameter validation,
  disconnect/no-retry behavior, host authentication, update transport, standalone
  runtime and attachment previews. The first full run failed five standalone
  cases because fixtures inherited `CODEX_BRIDGE_BIND=192.168.1.2` while requests
  expected localhost; failed fixtures left backend processes running. Only those
  test fixture processes were stopped. Explicit loopback binding fixed the test
  environment and the complete rerun passes.
- Android lint reports **No issues found**. Debug, release and instrumentation
  APK builds succeed. Existing icon/clipboard deprecation warnings remain.
  A narrowly scoped lint suppression documents that Android `stopService`
  resolves an Intent by component, rather than listener object identity.
- Instrumentation tests compile for archive Delete/Restore and confirmation
  cancellation, sidebar destinations and Back, the notification toggle,
  permission feedback and persisted notification account isolation. They were
  not executed.
- Both APK signatures verify; signing certificates match published 0.7.7.
  The publisher verifies package IDs, version 0.7.8/code 16 and minimum SDK 30.
- A temporary authenticated loopback host serves the actual published metadata
  and APKs. Both manifests/downloads return 200, match the delivered APK sizes
  and SHA-256 digests, and reject unauthenticated or browser-Origin requests
  with 401. Delivery checksum verification passes.
- The deployed HTTPS health endpoint returns `{"ok":true}`; its update endpoint
  returns 401 without authentication. No production login credentials were read.
- `npm run check` and `git diff --check` pass.

Commands executed:

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
npm test
npm run check
python3 /tmp/custom-codex-078-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.8.txt
node /tmp/custom-codex-078-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

The temporary artifact script used `apksigner verify --print-certs` on the new
and immutable previous APKs, compared signing identities, preserved 0.7.7 delivery
APKs and calculated checksums. The temporary transport script generated an
ephemeral bearer token, created a loopback `createHost`, verified both channels,
and closed that test host. It did not use production credentials.

Evidence: `artifacts/v0.7.8-android-build.txt`, `v0.7.8-backend-tests.txt`,
`v0.7.8-signing-verification.json` and `v0.7.8-local-update-verification.json`.
Previous delivery APKs are preserved under `artifacts/v0.7.7/`.

| Channel | Bytes | SHA-256 |
| --- | ---: | --- |
| Release | 2981574 | `f8fbbb2aa6a3e6fc810665b0c81dfbaaec6b332d0dc8ee0453769548223aaf66` |
| Debug | 61680121 | `5815e5ad6d892fe337f6dfcf4fda5e187205ea977efd9ff5d9dacf2801800fb8` |

## Unverified and pending host reload

No Android device is connected. UI behavior, notification permission prompts,
background monitoring, notification taps and update discovery/installation
remain unverified on a device. Android can stop the monitor, including its
Android 15 data-sync background time limit; reopening the app resumes it.

The running backend was not restarted, to avoid interrupting this active chat.
**Delete requires a later host bridge restart** to load its new allowed RPC
method. The installed CLI protocol schema includes `thread/delete`; routing
was tested with controlled fixtures, and no real conversation was deleted.
Actual upstream deletion remains unverified. Sent uploaded-image previews from
the earlier release also require that restart. Notification monitoring uses
existing event routes and requires no backend change. Update manifests are read
dynamically. Authenticated production downloads were not repeated.
