# In-app APK updates

APK 0.4.0 introduced in-app updates; 0.4.1 extended the download deadline for slow VPN links. Version 0.5.0 adds [compact chat, project and composer layouts](mobile-0.5.0.md). Install `artifacts/codex-remote-release.apk` manually once if you have 0.3.0 or earlier. Existing updater users can install 0.7.6 from **Settings → App updates**.

## On the phone

1. Connect WireGuard and sign in to `https://codex.geeksun.ir` as `nulltogeek`. The app checks for an update when it connects, at most once every six hours during the current app session. You can also tap **Check for updates** in Settings.
2. Review the version, release notes and download size, then tap **Download**. Keep the app open while downloading. A failed download can be retried; an incomplete file is never offered to the installer.
3. Tap **Install update** after verification. Android may ask you to allow Codex Remote to install apps; enable that permission and return. Then confirm the update in Android's installer. The APK uses the same application ID and signing key, so a normal update retains your app data.

The app checks SHA-256, size, package name, version, minimum Android version and the installed signing certificates. It verifies the cached file again before opening the installer. The non-exported FileProvider shares only the update-cache directory through a temporary read grant. Installation stays under Android's normal user confirmation.

Downloads use the existing authenticated HTTPS/WireGuard connection. The current client allows 15 minutes per download and times out a stalled read after 30 seconds. Session credentials stay in request headers, and APK downloads reject redirects. Release and debug APKs use separate update channels and application IDs. Check/download errors are shown in the update section without interrupting a chat. APK availability is independent of model-provider authentication.

## Publish the next release

Increase `versionCode` and `versionName` in `app/build.gradle.kts` using `vim`, retain the signing key, and add `docs/release-notes/<version>.txt`. Then run:

```bash
CODEX_GOOGLE_CDN=1 ./scripts/build-android.sh
```

The build script runs Android tests/lint, builds debug/release APKs, copies the delivery artifacts and publishes updates when the matching release-notes file exists. If notes are missing it builds the APKs but reports that publishing is still required. An explicit notes file can also be supplied as the build script's first argument.

To publish already-built APKs:

```bash
node scripts/publish-app-update.mjs docs/release-notes/0.7.6.txt
```

Publishing verifies both APK signatures and actual package/version/minimum-SDK metadata using Android build-tools. It writes immutable APKs named by their SHA-256 and replaces each channel's manifest atomically. Future APK publications need no backend restart. The first updater deployment restarts the idle service to add these routes; that restart invalidates existing app login sessions.

Installed apps discover an update only when its `versionCode` is greater than
their own build number. Rebuilding an APK with the same number does not make it
an update. The publisher rejects changed APKs with the same or lower build
number and permits an identical release to be republished.

The default publication directory is the repository's ignored `artifacts/updates/`. `CODEX_APP_UPDATE_DIRECTORY` can select another directory, but it must be configured identically for the server and publisher. `ANDROID_HOME`/`ANDROID_SDK_ROOT` select the publishing tools; the fallback is `.downloads/android-sdk`, build-tools 35.0.0. `CODEX_ANDROID_BUILD_TOOLS` can point directly to another compatible build-tools directory.

The authenticated routes are `GET /v1/app-update?channel=release|debug` and `GET /v1/app-update/apk/<sha256>.apk`. Nginx forwards both through the existing Codex vhost. There is no public unauthenticated APK endpoint or token in a download URL.

Verify the published proxy path from this Linux machine:

```bash
node scripts/verify-app-update.mjs https://codex.geeksun.ir --local
```

This reads the existing private login locally and downloads both channels, checking authentication, byte counts, SHA-256 and agreement with published metadata. It does not print passwords or sessions.

## Initial 0.4.0 verification on October 11, 2026

- `npm run check` and `npm test`: all 36 backend tests passed, including update authentication, metadata validation, binary streaming, channel separation, invalid paths and APK symlink rejection.
- `./gradlew --offline --no-daemon -PcodexGoogleCdn=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`: succeeded; all 36 JVM tests passed and lint reported no issues. Tests cover bounded downloads, incomplete/altered bytes, checksum validation, incompatible metadata, redirects and expired sessions. Logs: `artifacts/v0.4-backend-tests.txt` and `artifacts/v0.4-android-build.txt`.
- Both APK signatures and actual package/version/minimum-SDK metadata passed publication checks. The release signing certificate matched 0.3.0. Delivered APK checksums passed. Debug/release 0.4.0 APKs installed over the existing emulator packages. The previous delivery APKs are preserved in `artifacts/v0.3.0/`.
- Four Android 11 emulator checks passed in 227.111 seconds: dark/light/settings screens, update controls and progress, native authenticated APK download/signing identity/FileProvider validation, and native login/reconnect/sign-out. `artifacts/v0.4-android-instrumentation.txt` records the run.
- The installer check was strengthened after its first capture showed staging. Its separate rerun passed in 76.178 seconds: Android finished parsing the APK and displayed an enabled **Install** confirmation button. The test cancelled there. See `artifacts/v0.4-installer-confirmation.txt` and `artifacts/screenshots/v0.4.0/android-update-installer.png`. The update-settings capture uses a future-version fixture; it does not announce a real 0.5.0 release. Temporary emulator install permission was restored to its original default afterward.
- Both complete APK downloads passed on the temporary real backend, including size and SHA-256 (`artifacts/v0.4-update-local.json`). The deployed service was restarted only after confirming zero active chats and zero pending approvals; one `systemError` chat was correctly classified separately from running work (`artifacts/v0.4-service-idle.json`).
- Post-deployment HTTPS/WSS verification passed for authentication, complete local inventory (8 models, 15 projects, 35 active catalogue entries and 96 archived entries), shared events, reconnect and session revocation (`artifacts/v0.4-proxy-verification.json`). The release APK also passed a complete authenticated HTTPS download/checksum check through `codex.geeksun.ir`.

The temporary test backend was closed. The deployed user service remains active. Physical-phone source-permission prompts and completed interactive installation remain unverified; the emulator installer check intentionally stops before confirmation. Model-provider HTTP 401 from the earlier task is independent of APK updates and was not re-tested here.

## Historical 0.4.1 verification

The 60 MB debug transfer exposed an overly short download deadline: the proxy check timed out after three minutes, and the five-minute retry reached 56,341,592 of 60,386,031 bytes before timing out. This was a progressing transfer, so 0.4.1 extends the client/verifier deadline to 15 minutes while retaining the client's 30-second stalled-read timeout. The release APK passed both earlier checks.

The 0.4.1 rebuild passed all 36 JVM tests, Android lint with no issues, and debug/release/instrumentation compilation (`artifacts/v0.4.1-android-build.txt`). Publication verified both signed APKs and their actual package/version/minimum-SDK metadata. Delivered checksums passed; the release signing identity matched 0.4.0. The release installed successfully over the existing emulator package (`artifacts/v0.4.1-release-install.txt`). Version code is 5; the 0.4.0 delivery APKs are preserved in `artifacts/v0.4.0/`.

The newer 0.4.1 debug APK was downloaded and verified against the emulator's installed 0.4.0 signing identity. Android accepted it as an update and displayed an enabled Install confirmation button; the test cancelled there. The targeted check passed in 76.663 seconds (`artifacts/v0.4.1-installer-confirmation.txt`). Its capture is `artifacts/screenshots/v0.4.0/android-update-installer-0.4.1.png`. The temporary install permission was restored afterward.

`node scripts/verify-app-update.mjs https://codex.geeksun.ir --local` passed for both channels: complete authenticated downloads, actual sizes (release 2,680,590 bytes; debug 59,614,133 bytes), SHA-256, matching published metadata, and rejection of unauthorized/browser-Origin requests. See `artifacts/v0.4.1-update-proxy.json`. This publication required no second service restart. The temporary test backend was closed and the deployed service remained active.

The separate model-provider HTTP 401 was subsequently repaired on October 11. Real text replies and a follow-up pass through the deployed endpoint; see [the authentication repair](local-wireguard-deployment.md#provider-authentication-repaired-on-october-11-2026). That service restart requires fresh phone login. It does not require another APK update.

## Historical 0.5.0 layout update

Version 0.5.0/code 6 adds compact chat/project rows and an integrated composer with mode/model/access in one toolbar. Full access descriptions and reasoning effort open in Chat settings. See [the layout guide](mobile-0.5.0.md) for the 36 JVM tests, clean lint, five emulator layout checks, explicit keyboard-visibility check and reviewed captures.

Both APKs passed signature and actual package/version/minimum-SDK checks; their signing identities match 0.4.1. Release is 2,742,222 bytes and debug is 60,486,055 bytes. `node scripts/publish-app-update.mjs docs/release-notes/0.5.0.txt` published both channels without a backend restart. Existing updater users can tap **Check for updates** in Settings to discover this release immediately instead of waiting for the automatic check interval. Previous delivery files remain under `artifacts/v0.4.1/`.

`node scripts/verify-app-update.mjs https://codex.geeksun.ir --local` passed complete authenticated downloads of both 0.5.0 channels, exact sizes/SHA-256, matching local metadata and unauthorized/browser-Origin rejection (`artifacts/v0.5.0-update-proxy.json`). Delivery checksums passed, and the release APK installed over the existing emulator release. Physical-phone update installation remains unverified.

## Current 0.6.0 chat update

Version 0.6.0/code 7 adds scroll-loaded history and chats, collapsible code/tool outputs, image previews with cancel/removal, and one settings panel for mode, model, reasoning and access. See [the feature guide](mobile-0.6.0.md) for legacy-history compatibility, offline cached batches and exact validation. All 39 JVM tests, clean lint, debug/release/instrumentation builds and eight individual UI checks passed. The emulator’s interrupted initial run and successful remaining six-test run are recorded there.

Both APKs preserve the 0.5.0 signing identity. Publication verified actual package/version/minimum-SDK metadata. Release is 2,774,990 bytes and debug is 60,598,851 bytes. Publishing required no backend/Nginx restart. Existing updater users can open **Settings → App updates → Check for updates** to discover 0.6.0; the previous delivery remains in `artifacts/v0.5.0/`.

Complete authenticated HTTPS downloads of both 0.6.0 channels passed size/SHA-256/local-metadata checks, along with unauthorized/browser-Origin rejection (`artifacts/v0.6.0-update-proxy.json`). Delivery checksums passed; Android accepted the release upgrade over the emulator’s existing release package. Physical-phone update installation remains unverified.

## 0.7.0 library and account update

Version 0.7.0/code 8 adds right-swipe archive/restore with confirmation, local per-account Favorites tabs for chats and projects, and **Settings → Change password**. See [the feature guide](mobile-0.7.0.md) for persistence, session revocation and validation. The backend endpoint was loaded with one user-service restart after confirming zero active turns and zero pending approvals; sign in again after that restart. Future password changes apply immediately without restarting Codex. Nginx was unchanged.

Both signed APKs passed actual package/version/minimum-SDK publication checks. Release is 2,807,758 bytes and debug is 60,813,555 bytes. Previous delivery APKs are preserved in `artifacts/v0.6.0/`. Open **Settings → App updates → Check for updates** to discover this release.

`node scripts/verify-app-update.mjs https://codex.geeksun.ir --local` passed complete authenticated downloads of both 0.7.0 channels, exact size/SHA-256 and local-metadata checks, plus unauthorized/browser-Origin rejection (`artifacts/v0.7.0-update-proxy.json`).

All 42 backend tests, 43 Android JVM tests and five focused emulator UI checks passed; lint is clean. Both debug and release 0.7.0 APK upgrades installed successfully on the emulator after its boot/streamed-install recovery. Phone installation remains unverified. See the feature guide for the exact evidence and temporary emulator settings.

## 0.7.1 desktop-owned chat recovery

Version 0.7.1/code 9 explains active-writer conflicts and offers a confirmed copy
of saved history and the unsent draft, including attachment previews and chat
settings. Copying defers inherited goal work and never sends the draft
automatically. See [the recovery guide](mobile-0.7.1.md) and
[release verification](mobile-0.7.1-verification.md).

## 0.7.2 picker and attachment feedback

Version 0.7.2/code 10 fixes dropped file-picker results during chat reconnection
and displays image previews, upload progress, confirmed Uploaded state and
persistent failure tiles. See [the feature guide](mobile-0.7.2.md) and
[release verification](mobile-0.7.2-verification.md). No backend restart is needed.

## 0.7.3 Chats list pagination

Version 0.7.3/code 11 preserves scrolled pages on refresh, pages both active and
archived agent history, continues through sparse project filters, and rejects
outdated/duplicate pagination callbacks. See [the feature guide](mobile-0.7.3.md)
and [release verification](mobile-0.7.3-verification.md). No backend restart is
needed.

## 0.7.4 pull down to refresh

Version 0.7.4/code 12 adds pull-to-refresh with a visible spinner on Chats and
Projects, including empty lists. It preserves loaded pages and filters, rejects
duplicate refreshes, and pauses pagination during refresh. See
[the feature guide](mobile-0.7.4.md) and
[release verification](mobile-0.7.4-verification.md). No backend restart is needed.

## 0.7.5 conversation display and update discovery

Version 0.7.5/code 13 releases the jump-to-latest button, final-reply display,
one changes summary per finished turn and tappable full-screen image previews.
See [the display guide](chat-display.md). The earlier development APK retained
code 12 and was not published, so installed 0.7.4 apps could not discover it.
This release increments the build number and publishes both channels.

## 0.7.6 Archived section and arrow button

Version 0.7.6/code 14 makes jump-to-latest an arrow-only button and adds Archived
to bottom navigation. Archived history retains search, project filters, pagination
and restore actions; Back returns to its originating section. See
[release verification](mobile-0.7.6-verification.md).
