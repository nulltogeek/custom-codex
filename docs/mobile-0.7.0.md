# Favorites, swipe archive and password changes 0.7.0

Version 0.7.0 uses version code 8.

## Chats and projects

Swipe a chat row to the right to open the archive confirmation. Confirm **Archive** to move it to archived history on the server. Right-swiping an archived row offers **Restore** instead. Cancel returns to the list without changing the chat. Left swipes have no action. The row returns to its normal position after the gesture. The toolbar archive/restore button remains available. Offline, running, sending and pending-input states disable the gesture; the client checks running/pending state again before sending the action.

Tap a chat or project's star to add/remove it from **Favorites**. Chats have a Favorites tab beside Recent; Projects has All projects and Favorites tabs. The open conversation also has a star in its toolbar. Favoriting never changes the desktop's pin state or project membership.

Favorites are stored on this phone, scoped to its server URL and username, and survive app restarts, updates and sign-out. Project favorites use the current cached project inventory. Chat favorites retain a last-known summary, so an older favorite still appears before its catalogue page is loaded, including offline. The Favorites tab includes archived and agent chats and respects chat search/project filters. Opening a favorite loads authoritative history. Summaries update when available from inventory/events; a remotely deleted favorite may remain until you remove its star, and opening it reports the host's error. Favorites do not synchronize between phones.

## Change password

Open **Settings → Change password** while connected. Enter the current password, a different new password of at least 12 characters and at most 1024 UTF-8 bytes, and the matching confirmation. Password spaces are preserved. Fields are masked, excluded from saved state/storage/history and cleared after success or dialog dismissal. Incorrect current-password errors leave the session signed in; expired-session errors return to sign-in.

The authenticated `POST /v1/password` endpoint verifies the current password using the same bounded scrypt checks and shared rate limit as login. It rejects browser Origins, oversized/non-JSON bodies, unavailable/expired sessions and concurrent password changes. Password requests do not follow redirects. The standalone server advertises `capabilities.passwordChange`; an older server disables the control with an update explanation.

Before replacing the account file, the server saves an owner-only backup of the previous hash. The new salted hash is atomically renamed into the owner-only account file. A persistence failure leaves the live password and sessions unchanged. After success, this phone's existing session stays valid and all other sessions and their sockets are revoked. Running Codex work continues. The new hash is loaded after a future server restart, without requiring a restart to apply the change.

The initial-login reference remains the original provisioning reference: it does not receive the new password and will no longer authenticate after a change. Save the new password privately. Local verifiers using that reference need a private current-login file via `CODEX_INITIAL_LOGIN_FILE`. For recovery from a forgotten password, use the local owner procedure in [the login guide](username-password-login.md); its offline reset requires an idle service restart.

## Verification

- `npm run check` and `npm test`: all 42 backend tests passed, zero failures/skips (`artifacts/v0.7.0-backend-tests.txt`). New checks cover password validation/throttling, persistence failure, concurrent changes/logins, session expiry during verification, other-session socket revocation, private hash backups and standalone restart persistence. Tests use disposable accounts and do not change the owner's password.
- `./gradlew --offline --no-daemon -PcodexGoogleCdn=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`: passed. All 43 JVM tests passed with zero failures/skips and Android lint reported no issues (`artifacts/v0.7.0-android-build.txt`). Client tests cover password JSON/whitespace, rejection without expiring a valid session, session expiry and redirect refusal, plus archive guards and password validation.
- One deployed user-service restart loaded the password endpoint after zero active turns and zero pending approvals were confirmed (`artifacts/v0.7.0-service-idle.json`). No Nginx changes were needed. `node scripts/verify-server.mjs https://codex.geeksun.ir --local-inventory` passed inventory, shared events, reconnect and revocation checks (`artifacts/v0.7.0-server-proxy.json`).
- The deployed HTTPS password route advertises its capability and returned 400 for an invalid new password, 403 for an incorrect current password and 401 for invalid sessions/browser Origins. The test session remained valid afterward. The deployed owner's password was unchanged (`artifacts/v0.7.0-password-proxy.json`).
- APK publication checked actual application ID, version/minimum SDK and signatures. Release is 2,807,758 bytes and debug is 60,813,555 bytes. Both signing certificates match 0.6.0 (`artifacts/v0.7.0-signing-verification.json`), and delivery checksums passed. Both update channels are published. Previous deliveries remain under `artifacts/v0.6.0/`.

Physical-phone gestures, keyboard behavior and hardware performance remain unverified. Actual password changes were tested only against disposable accounts.

`node scripts/verify-app-update.mjs https://codex.geeksun.ir --local` passed complete authenticated downloads of both 0.7.0 channels, exact size/SHA-256 and local-metadata checks, plus unauthorized/browser-Origin rejection (`artifacts/v0.7.0-update-proxy.json`).

Five Android 11 UI checks passed in 142.12 seconds (`artifacts/v0.7.0-android-instrumentation.txt`): right swipe versus left/disabled swipe; saved older/archived chat favorites and removal; persistence across store instances and server/account isolation; matching password confirmation and cleared fields after success; and project favorite filtering/removal at 320 dp. The software emulator initially suffered Android's “Lost network stack” framework restart and System UI ANRs, before the app could run. Android's `connectivity/always_ratelimit_networkstack_crash` DeviceConfig flag was temporarily enabled from its original unset value, and the display temporarily reduced to 540×960. Streamed APK installs were interrupted/timed out; file-based installs succeeded. Android accepted both debug and release upgrades to 0.7.0 (`artifacts/v0.7.0-debug-install.txt`, `artifacts/v0.7.0-release-install.txt`). The initial emulator attempts do not count as successful UI runs and establish no phone performance result.

The three isolated-screen captures in `artifacts/screenshots/v0.7.0/` were reviewed: chat Favorites with an unpaged archived chat, project Favorites at 320 dp, and the password form. Root ADB was used only to copy these named fixture captures after app-identity access to external storage was denied. Cleanup verified ADB UID 2000, DeviceConfig returned to unset/null, the unused temporary Global entry removed, display size returned to physical 1080×1920, and the original density override 210 retained. The owned headless emulator was closed.
