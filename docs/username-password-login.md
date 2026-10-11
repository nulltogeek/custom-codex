# Username and password login

The deployed standalone backend and Android APK 0.4.1 use username/password login at **https://codex.geeksun.ir**. The phone still needs WireGuard and VPN DNS. No pairing token needs to be entered.

## Sign in

1. Install the new `artifacts/codex-remote-release.apk`. It upgrades the existing personal release package (version code 5) with the same signing identity. Previous APKs are preserved under `artifacts/v0.1.0/`, `artifacts/v0.2.0/` `artifacts/v0.3.0/` and `artifacts/v0.4.0/`. See [in-app updates](in-app-updates.md) for current checks and [the composer update](mobile-0.3.0.md) for its features.
2. View the initial login privately on this Linux machine:

   ```bash
   vim /home/nulltogeek/.local/share/custom-codex/initial-login.json
   ```

   The username is `nulltogeek`. Enter the file's password in the app's masked **Password** field, using server URL `https://codex.geeksun.ir`, then tap **Sign in**. Do not send the password in chat. This owner-only file is a local reference; editing it alone does not change the active login.
3. Use **Settings → Sign out** to revoke this phone's session. A successful sign-out also closes its authenticated WebSockets. If offline, the phone clears its local session and reports that server revocation could not be completed.

The older APK's saved pairing token is ignored by the new client and rejected by the deployed backend. An upgrade therefore requires a fresh sign-in.

## Password changes

To choose another password, edit the private JSON file using `vim`, retaining the `username` and `password` fields. Passwords must be at least 12 characters and no more than 1024 bytes; spaces are significant. Then apply the file from this repository:

```bash
node scripts/set-login.mjs
```

This saves a newly salted password hash and preserves a private backup of the previous account hash. It does not print the password or affect model-provider credentials. After server-owned turns and approvals are idle, restart **only** the user service to load the change:

```bash
systemctl --user restart codex-remote.service
```

Restarting ends this backend's live runtime and invalidates all login sessions. The desktop runtime is separate. A password change and subsequent recovery were tested against a disposable account, not the deployed owner's account.

## Authentication behavior

- `POST /v1/login` accepts JSON `{ "username": "…", "password": "…" }` over the existing HTTPS proxy. The backend stores a salted scrypt hash in owner-only `~/.local/share/custom-codex/account.json`. The initial password reference also has mode 0600.
- Successful login returns an opaque session for authenticated HTTP and WebSocket requests. The app manages this internally and encrypts it using Android Keystore. It never persists the password or saves it in Activity state.
- Sessions expire after 30 days or a backend restart. The client returns to the sign-in screen when its session expires or is rejected. Up to 32 sessions may be active; issuing another revokes the oldest.
- Ten rejected password attempts within ten minutes block login for that source until the window expires. The proxy's source address intentionally shares this limit, and forwarded headers cannot bypass it. At most two password checks run concurrently.
- Browser Origins, oversized login bodies, missing/invalid sessions and the former pairing token are rejected. Protected routes remain behind the existing VPN-only Nginx policy.
- Username/password login is implemented for the deployed standalone runtime. The optional historical desktop-sharing launcher still uses its legacy token protocol and is not compatible with the 0.2.0 app's sign-in screen.

## Historical 0.2.0 verification on October 11, 2026

- `npm run check` and `npm test`: 28 backend tests passed, including password rejection and throttling, private provisioning, password changes with hash backups, session expiry and revocation, body limits, legacy-token rejection and standalone RPC behavior.
- `npm run test:standalone`: the installed Codex 0.159.2 backend initialized with username/password sessions; both clients shared inventory/events and existing config remained unchanged.
- `./gradlew --offline --no-daemon -PcodexGoogleCdn=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`: passed. All 19 JVM tests passed; Android lint and release signing completed; instrumentation tests compiled but were not run on a phone.
- `node scripts/verify-server.mjs https://codex.geeksun.ir --local-inventory`: passed against the deployed service. Correct login accepted; incorrect password and browser Origin rejected; 8 models, 15 projects, 33 active chats and 96 archived chats matched local inventory; shared WebSocket events, reconnects and sign-out revocation passed. Evidence: `artifacts/username-login-proxy-verification.json`.
- A separate request using the previous pairing token returned HTTP 401. `apksigner verify --verbose artifacts/codex-remote-release.apk` and `sha256sum -c artifacts/SHA256SUMS` passed; package metadata reports version 0.2.0/code 2.
- The existing service was restarted only after confirming zero active chats and zero pending approvals. Nginx and WireGuard configuration were unchanged.

The model-provider HTTP 401 observed during this historical verification was repaired later on October 11 by updating the private service credential and restarting the idle service. Real streaming and a follow-up now pass through the deployed domain; see [provider recovery and verification](local-wireguard-deployment.md#provider-authentication-repaired-on-october-11-2026). App login does not replace `EMZANO_AI_KEY` or renew provider authentication. Sign in again after a service restart. The physical-phone retry, phone lifecycle, reboot recovery, real model approvals and hardware performance remain unverified.
