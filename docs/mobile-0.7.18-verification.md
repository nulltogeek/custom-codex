# 0.7.18 verification — October 13, 2026

Version **0.7.18 / code 26** is published on the release and debug update
channels. Update through **Settings → App updates → Check for updates**.

Archive and Restore now appear in the three-dot menu beside Rename chat and
Move to project, in chat lists and the open conversation's toolbar. Chats in the
active library offer Archive; archived chats offer Restore. Selecting either
opens the existing confirmation dialog. The existing connection, running-task,
sending and pending-input guards determine whether the action is enabled.

Swipe-right archive/restore is removed. Archived chats retain confirmed
swipe-left Delete, with the same physical direction in either layout direction.
The accessible row gesture action now exposes only Delete; Archive and Restore
are available through the accessible options button and menu. This Android-only
change requires no host restart.

## Executed verification

- All **180 JVM tests pass**, with no failures, errors or skips. Android lint
  reports **No issues found**. Debug, release and instrumentation APKs build.
- Existing native UI checks were updated for menu Archive/Restore, disabled
  archive while offline, right-swipe inactivity, left-swipe deletion in both
  layout directions, accessible Delete, and toolbar restore confirmation.
  These checks compile but were not run on a device.
- Both APK signatures verify and certificates match published 0.7.17. The
  publisher validates actual application IDs, version 0.7.18/code 26 and SDK 30.
- Compiled DEX in both delivery APKs contains the new delete-only swipe tag and
  excludes the removed archive swipe tag. The debug APK also contains the new
  `SwipeDeleteRow` function and excludes `SwipeArchiveRow`.
- Authenticated loopback metadata and full downloads match the actual published
  APKs, including size, SHA-256, delivery copies and content type. Unauthenticated
  and browser-Origin requests return 401. Delivery checksums pass.
- Previous delivery APKs remain under `artifacts/v0.7.17/`, and previous immutable
  published APKs are retained. `git diff --check` passes.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
python3 /tmp/custom-codex-0718-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.18.txt
node /tmp/custom-codex-0718-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

Evidence: `artifacts/v0.7.18-android-build.txt`,
`v0.7.18-signing-verification.json`, `v0.7.18-ui-symbol-verification.json` and
`v0.7.18-local-update-verification.json`. Release APK size is 3,119,742 bytes;
debug APK size is 60,712,643 bytes.

## Unverified

No Android device is connected. Device interaction and phone installation remain
unverified. Production authenticated HTTPS downloads were not repeated; download
verification used an ephemeral loopback host and the actual published files.
