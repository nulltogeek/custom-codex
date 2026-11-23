# Codex Remote for Android

Local development and builds now run from this checkout; production remains
on `nulltogeek@192.168.1.7`. See [local build and deployment](docs/local-development.md).

Version **0.9.2** adds confirmed administrator user deletion. Codex questions support both suggested choices and typed answers; the blocking and asynchronous flows are verified in the emulator. See [account controls](docs/multi-user-android.md) and [answering questions](docs/interactive-questions.md).

Version **0.9.1** groups chats by project, keeps orphaned chats separate, and fixes the resting Delete background. These changes are included in 0.9.2, available through **Settings → App updates → Check for updates**. See [project-based navigation](docs/project-chat-navigation.md) and [updater publication](docs/in-app-updates.md).

Version **0.9.0** adds the phase 1 desktop-style workspace: adaptive sidebar, pinned chats, project search, conversation actions, and a redesigned composer. See [phase 1 features, verification and installation](docs/phase1-desktop-interface.md).

Version 0.8.0 source adds password signup, private per-user chats/projects/workspaces, administrator controls for `nulltogeek`, and a ChatGPT Android-inspired drawer and conversation layout. See the [Android account guide](docs/multi-user-android.md), [multi-user host setup and isolation](docs/multi-user-server.md), and [UI reference notes](docs/android-drawer-ui.md). Source changes require a bridge restart to activate; the existing published update assets are separate from the new build.

A native Android 11+ client for a Codex app-server on your Linux machine. Supports chats, projects, streaming, model controls, approvals, terminal output and diffs, with formatted code, file/image attachments, coding/conversation modes, access presets, message queues with editing and reordering, message copy controls, a command activity heartbeat, favorites, archive/restore menu actions, drawer navigation, archived chat restore and confirmed deletion, task completion notifications, password changes, five color accents, optional screen transitions and in-app APK updates.

## Host setup

The deployed server runs inside the systemd desktop container at **192.168.1.7** on `emz-ops-work1`, under the `nulltogeek` user service `codex-remote.service`. It uses the container's existing `/home/nulltogeek/.codex` configuration and history, private Node.js **22.23.2**, and private Codex **0.167.0-alpha.3.2**. GNOME and the desktop app can be closed. The container and its WireGuard connection must remain available; the original local service is disabled.

The APK keeps using **https://codex.geeksun.ir** over your private WireGuard network. Existing Nginx on `79.175.177.57` terminates HTTPS/WSS and forwards to `192.168.1.7:8787`. Sign in again as **nulltogeek** with your current app password: the existing account hash and uploads were preserved, while sessions were renewed by the move. The old initial-login reference is obsolete. The app encrypts its session using Android Keystore and does not store the password. Current in-app update assets are **0.9.2/code 36**, compatible with the 0.8.0/0.9.0/0.9.1 signing key; 0.7.24 needs a one-time reinstall because its key differs. The target retains its own saved chats; this deployment does not import the prepared filmapp queue or enable recurring sync.

Read [task completion notifications](docs/task-notifications.md), [the message queue guide](docs/message-queue.md), [the chat display and Archived section guide](docs/chat-display.md), [favorites, swipe archive and password changes](docs/mobile-0.7.0.md), [the 0.6.0 chat loading guide](docs/mobile-0.6.0.md), [the compact 0.5.0 layout guide](docs/mobile-0.5.0.md), [in-app updates](docs/in-app-updates.md), [the 0.3.0 feature guide](docs/mobile-0.3.0.md), [username/password login](docs/username-password-login.md) and [the deployed WireGuard setup and recovery guide](docs/local-wireguard-deployment.md) first. [Standalone runtime details](docs/standalone-server.md) cover installation on another host. Node.js 22.13+ is required. The historical desktop-sharing launcher still uses the earlier token protocol described in [the desktop runbook](docs/runbook.md).

The administrator's standalone runtime and ordinary desktop processes share saved configuration/history, while each owns its running turns and approvals. Regular user accounts have separate container runtimes and saved data. Use the server's connected clients to control work started on the server.

For **already has an active writer** errors, 0.7.1 offers **Continue in a new chat**
to copy saved history and your draft for review before sending. See
[desktop-owned chat recovery](docs/mobile-0.7.1.md).

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
- [Final replies, final changes and tappable image previews](docs/chat-display.md)
- [Scroll loading, collapsible output and attachment previews](docs/mobile-0.6.0.md)
- [Chats list pagination and refresh recovery](docs/mobile-0.7.3.md)
- [Pull down to refresh Chats and Projects](docs/mobile-0.7.4.md)
- [Compact chat, projects and composer](docs/mobile-0.5.0.md)
- [Earlier verification results](docs/verification.md)

The APK is built and signed for personal sideloading. The deployed HTTPS/WSS path, authentication, reconnect, model discovery and complete local inventory have been verified with two phone-protocol clients. The expired provider credential was replaced on October 11, 2026; real model streaming and a follow-up now pass through the deployed domain. See [provider recovery and verification](docs/local-wireguard-deployment.md#provider-authentication-repaired-on-october-11-2026). The physical-phone retry remains unverified, and the earlier software-emulator benchmark missed the 95% frame-budget target. See the verification report for exact evidence.

No cloud-chat integration or automatic prompt retries are included. Rerun protocol checks after Codex upgrades. Credentials, signing keys, build outputs and local runtime data are excluded from version control; retain the signing key and publish APKs separately as described in the update guide.

For selected files/images missing after the Android picker, update to 0.7.2.
See [picker recovery and visible upload status](docs/mobile-0.7.2.md).

The navigation drawer contains Chats, Archived, Projects and Settings, plus Manage users for the administrator. Archived chats offer
Restore and confirmed Delete; update and restart the host bridge to allow deletion.
Use a chat's three-dot menu to rename it or [move it into a project](docs/moving-chats.md).
Enable completion alerts in Settings; see [task notifications](docs/task-notifications.md)
for background monitoring and Android limits.

Chat rows are compact: swipe right to archive/restore and swipe left to delete an archived chat, with confirmation.

Interactive questions support choices and typed answers during work; see [answering questions](docs/interactive-questions.md).

Code highlighting, response typography and Enter-to-send are described in [response formatting](docs/response-formatting.md).
