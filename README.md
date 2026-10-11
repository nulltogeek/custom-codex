# Codex Remote for Android

A native Android 11+ client for a Codex app-server on your Linux machine. Supports chats, projects, streaming, model controls, approvals, terminal output and diffs, with formatted code, file/image attachments, coding/conversation modes, access presets, five color accents, optional screen transitions and in-app APK updates.

## Host setup

The deployed server runs on your own Linux machine under `codex-remote.service`, using `/home/nulltogeek/.codex/config.toml` and the installed Codex 0.159.2 binary. The desktop can be closed. Your machine must stay awake with WireGuard connected.

The APK connects to **https://codex.geeksun.ir** over your private WireGuard network. Existing Nginx on `79.175.177.57` terminates HTTPS/WSS and forwards to `192.168.1.2:8787`. Sign in with username **nulltogeek** and the initial password in the owner-only `~/.local/share/custom-codex/initial-login.json` file; view it locally with `vim`. The app encrypts its session using Android Keystore and does not store the password. No pairing token entry is required. Install the 0.4.1 APK once to add the updater. Future releases can be installed through Settings → App updates.

Read [in-app updates](docs/in-app-updates.md), [the 0.3.0 feature guide](docs/mobile-0.3.0.md), [username/password login](docs/username-password-login.md) and [the deployed WireGuard setup and recovery guide](docs/local-wireguard-deployment.md) first. [Standalone runtime details](docs/standalone-server.md) cover installation on another host. Node.js 22.13+ is required. The historical desktop-sharing launcher still uses the earlier token protocol described in [the desktop runbook](docs/runbook.md).

Standalone and ordinary desktop processes share saved configuration/history, while each owns its running turns and approvals. Use the server's connected clients to control work started on the server.

## Android build

JDK 17 and Android SDK platform 35/build-tools 35 are required. With an existing SDK, configure `ANDROID_HOME` or a local `local.properties` file. To install a checksum-verified SDK into this repository's ignored download directory:

```bash
python3 scripts/install-android-sdk.py
./scripts/build-android.sh
```

Where Google's normal download host is unavailable, this build-local option uses Google's alternate distribution CDN:

```bash
CODEX_GOOGLE_CDN=1 ./scripts/build-android.sh
```

For SDK downloads, the optional proxy is configured only for that invocation:

```bash
CODEX_DOWNLOAD_PROXY=http://localhost:8080 python3 scripts/install-android-sdk.py
```

For Gradle downloads using the HTTP proxy, pass JVM proxy properties with `JAVA_OPTS` and `GRADLE_OPTS` for that build invocation; do not change your global Gradle configuration. Never use the download proxy as the phone's backend address.

The build script produces `artifacts/codex-remote-debug.apk`, `artifacts/codex-remote-release.apk` and checksums. Both are personal sideload builds signed with the local Android development key; use your own private signing key before publishing. Debug additionally permits local HTTP for USB/emulator testing; release requires HTTPS. Use `adb reverse tcp:8787 tcp:8787` with the debug APK and `http://127.0.0.1:8787` for USB testing.

## Documentation

- [Architecture and compatibility](docs/architecture.md)
- [Implementation checklist](docs/implementation-plan.md)
- [Setup and recovery](docs/runbook.md)
- [In-app updates and current verification](docs/in-app-updates.md)
- [Composer/rendering improvements](docs/mobile-0.3.0.md)
- [Earlier verification results](docs/verification.md)

The APK is built and signed for personal sideloading. The deployed HTTPS/WSS path, authentication, reconnect, model discovery and complete local inventory have been verified with two phone-protocol clients. The expired provider credential was replaced on October 11, 2026; real model streaming and a follow-up now pass through the deployed domain. See [provider recovery and verification](docs/local-wireguard-deployment.md#provider-authentication-repaired-on-october-11-2026). The physical-phone retry remains unverified, and the earlier software-emulator benchmark missed the 95% frame-budget target. See the verification report for exact evidence.

No cloud-chat integration or automatic prompt retries are included. Rerun protocol checks after Codex upgrades. Credentials, signing keys, build outputs and local runtime data are excluded from version control; retain the signing key and publish APKs separately as described in the update guide.
