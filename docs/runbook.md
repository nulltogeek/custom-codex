# Setup and recovery

For the currently deployed standalone server and `codex.geeksun.ir` WireGuard proxy, use [local deployment and recovery](local-wireguard-deployment.md). The desktop restart and Tailscale instructions below apply only to the optional desktop-owned runtime.

## Host

Requirements: Linux, Node.js 22.13+, the installed desktop and its real bundled Codex executable. JDK 17 and Android SDK are needed only to build the APK. The tested backend is `/usr/lib/chatgpt/resources/codex`, version 0.159.2. Override it only through `CODEX_REAL_CLI_PATH`; never point that variable back to the wrapper.

From this repository, install the bridge dependency and check its routing before launching:

```bash
npm ci
npm test
npm run check
```

The launcher exports CODEX_CLI_PATH for its desktop process only. It does not edit installed app files or global shell configuration. Finish existing desktop work and quit the app normally before launching through it; do not kill running tasks. Launching an already-open single-instance desktop does not replace its backend. To revert, quit normally and launch the usual desktop icon.

After quitting normally, start the prepared launcher:

```bash
./scripts/launch-desktop.sh
```

This makes the desktop start its ordinary bundled backend through the bridge. It delegates helper backends and unrelated CLI commands unchanged. Check the non-sensitive loopback health endpoint:

```bash
curl --fail http://127.0.0.1:8787/healthz
```

`{"ok":true}` confirms the listener, not authentication or model availability. The Android connection verifies authenticated host readiness. An already-open desktop must be quit normally first; a second launcher cannot attach to the old stdio connection.

## Private access

Install Tailscale from its official source on host and phone, sign both into the intended private network, and expose only the loopback bridge using Tailscale Serve (HTTPS). Do not use Funnel. The phone pairs using the HTTPS host URL and the generated bridge bearer token. Token entry is masked and encrypted on the phone. Keep the token file private and do not paste it into a chat or terminal command argument. Pairing/rotation instructions will identify its path without printing its value.

```bash
./scripts/serve-private.sh
```

This checks the bridge, enables private Tailscale Serve and displays its HTTPS address. Follow Tailscale's browser flow if the tailnet needs HTTPS enabled. Use that exact HTTPS host address in the APK; do not use the host's localhost address on the phone. The tailnet's access rules must permit your phone to reach the host on HTTPS.

Open the generated token locally, then enter it in the phone's masked pairing field:

```bash
vim ~/.local/share/custom-codex/pairing-token
```

The default token file is owner-only (0600). `CODEX_BRIDGE_TOKEN_FILE` can select another private file for a launch. Do not embed the token in a URL or shell argument. Android encrypts it with a Keystore key and excludes pairing/history/drafts from backup. Pairing is for personal devices; this version uses one host-wide credential.

The host must remain awake, online and running the bridged desktop session. Authentication failures require correcting pairing; do not disable TLS or bearer checks.

## Install the APK

Use `artifacts/codex-remote-release.apk` on an Android 11+ phone. Transfer it locally and allow installation from your chosen file manager, or install via a USB-authorized device:

```bash
adb install -r artifacts/codex-remote-release.apk
```

This release is minified and resource-shrunk, requires HTTPS and is signed with the local Android development key for personal sideloading. Keep the signing key to preserve update compatibility; publishing requires your own release-signing arrangement. The optional debug APK has a separate application ID and permits HTTP only for loopback/emulator test hosts.

For local USB testing with the debug APK:

```bash
adb install -r artifacts/codex-remote-debug.apk
adb reverse tcp:8787 tcp:8787
```

Pair the debug app with `http://127.0.0.1:8787`. This USB forwarding is a test path; the release app uses Tailscale HTTPS.

## Rebuild

Use JDK 17. With a preinstalled SDK, set `ANDROID_HOME` or set `sdk.dir` in `local.properties` using `vim`. Alternatively, install verified task-local SDK archives:

```bash
python3 scripts/install-android-sdk.py
./scripts/build-android.sh
```

The installer verifies Google archive checksums and writes repository-local SDK configuration. The Gradle wrapper verifies the official Gradle 8.11.1 SHA256. The build script runs JVM tests, lint, debug and release compilation, then copies APKs and generates SHA256 checksums. Nothing is committed or uploaded.

If Google's usual Maven hostname is unavailable, use its alternate official distribution CDN:

```bash
CODEX_GOOGLE_CDN=1 ./scripts/build-android.sh
```

## Downloads

If direct downloads fail, HTTP/HTTPS dependency downloads may use http://localhost:8080 on the Linux host. Configure it per download/build invocation. On Android, localhost refers to the phone; it is not the host or remote backend URL. Never redirect the app's backend traffic through the download proxy.

```bash
CODEX_DOWNLOAD_PROXY=http://localhost:8080 python3 scripts/install-android-sdk.py
JAVA_OPTS='-Dhttps.proxyHost=localhost -Dhttps.proxyPort=8080 -Dhttp.proxyHost=localhost -Dhttp.proxyPort=8080' \
GRADLE_OPTS='-Dhttps.proxyHost=localhost -Dhttps.proxyPort=8080 -Dhttp.proxyHost=localhost -Dhttp.proxyPort=8080' \
CODEX_GOOGLE_CDN=1 ./scripts/build-android.sh
```

These settings apply only to dependency downloads for this invocation. They do not change desktop authentication or the bridge's loopback binding.

## Recovery

- Not ready: ensure the desktop was fully quit, then launched through the wrapper and initialized.
- Disconnect: app retains drafts and recent cached history; reconnect refreshes authoritative state. Do not resend an uncertain prompt until checking history.
- Approval answered elsewhere: refresh the pending-request snapshot. A stale answer is rejected, never repeated upstream.
- Replay gap/backend restart: reload authoritative chat history and pending input. Old approvals cannot be answered in a new epoch.
- Desktop update: run bridge tests and check the launcher override before relying on the new version.
- Model-provider HTTP 401: the bridge pairing token and model-provider authentication are separate. Restore the account/provider through your normal desktop authentication flow; do not enter model credentials into Android. The integration generation probe encountered this host-side failure.
- Revoke phone access: remove the phone from your tailnet or its host-access rules. After finishing work and quitting the bridged desktop normally, replace the token contents locally with a fresh random credential of at least 32 characters using `vim`, preserve mode 0600, then relaunch. Remove the old pairing in Android and pair again. Never rotate by printing a token into chat or logs.

Unpairing in Android removes its saved credential, but does not revoke a copied host token. Tailscale Serve remains configured separately from the launcher. Inspect it with `tailscale serve status`; disable the specific forwarding in Tailscale if you no longer want remote access. Do not enable Funnel.

## Verification record

Planning inspection: repository was clean; desktop Codex 0.159.2 and standalone CLI 0.155.1; 15 saved local projects; incomplete legacy chat-assignment migration; JDK 17 and Node 22 available; Android SDK directory lacked platforms/build tools. No desktop configuration was changed. See [verification.md](verification.md) for measured results and outstanding host/device checks.
