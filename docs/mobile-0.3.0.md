# Mobile update 0.3.0

The Android client now renders Markdown, accepts attachments, and offers coding/conversation and access controls. Install `artifacts/codex-remote-release.apk` as an update. It uses version code 3 and the same signing identity as 0.2.0. Previous APKs are preserved in `artifacts/v0.2.0/`.

Connect through WireGuard to `https://codex.geeksun.ir` and sign in as `nulltogeek`. View the initial password locally with `vim /home/nulltogeek/.local/share/custom-codex/initial-login.json`. A server restart invalidates existing sessions, so sign in again after this deployment.

## Composer and appearance

- Code fences preserve indentation and line breaks, including incomplete fences while streaming. Code blocks scroll horizontally and include a language label, basic syntax colors and **Copy code**. Headings, lists, quotes, tables, inline code, emphasis and HTTP(S) links render separately from code.
- Tap the paperclip for **Photo or image** or **File**. Attach up to eight files per message, each up to 20 MB. Remove an unsent attachment using its chip. Images are downsampled and normalized to JPEG; uploads remain in a private directory on the Linux host. Text files up to 256 KiB are included in model input; larger and binary documents supply a local file reference. Conversation mode permits reading explicitly attached files when needed. Document interpretation depends on the selected model and available host tools.
- **Codex** works on files and projects. **Conversation** answers questions using the same configured model provider with read-only access and instructions to avoid commands, edits and external actions. This does not connect to ChatGPT account history. Switch modes before the next turn.
- **Host policy** preserves the current chat's host permissions. **Ask for approvals** allows workspace changes and requests approval for extra access. **Read only** permits inspection without writes. **Full access** enables commands/files without approval prompts. Conversation always applies read-only access. These named presets are expanded on the host; arbitrary native policy/configuration overrides remain blocked.
- **Settings** offers Exchange, Ocean, Mint, Rose and Violet accents, dark/light/system appearance, and an animation toggle for short screen/color transitions.

## Fresh-chat compatibility

New chats use legacy history. The client reads metadata before choosing a history API, avoids unsupported experimental pagination, and uses live events when saved turns are unavailable. It checks loaded chats before resuming. A history-refresh failure after the server accepts a message does not label that message undelivered or lock sending. A genuinely uncertain transport failure before acknowledgement still locks that chat to avoid duplicate prompts.

## Checks

Backend syntax and all 34 Node tests passed. All 31 Android JVM tests passed; debug/release/instrumentation builds succeeded and Android lint reported no issues. Release signature verification passed and the signing certificate matched 0.2.0. Build logs are in `artifacts/v0.3-android-build.txt` and `artifacts/v0.3-backend-tests.txt`.

The temporary real Codex 0.159.2 backend accepted authenticated text/image uploads, all four access profiles, metadata reads and loaded-chat checks. See `artifacts/v0.3-mobile-protocol-local.json`.

Four focused Android 11 emulator tests passed in 191.684 seconds: dark/light screens and projects, code blocks/composer menus/attachment choices, Android Keystore and draft persistence, and native login/networking/reconnect/sign-out against the real temporary backend. See `artifacts/v0.3-android-instrumentation.txt`. Generated screenshots in `artifacts/screenshots/v0.3.0/` were visually reviewed. This checks the attachment menu and upload transport; it does not exercise every device's document picker.

The service was restarted after an authenticated inspection confirmed zero active chats and zero pending approvals (`artifacts/v0.3-service-idle.json`). HTTPS/WSS verification passed against `https://codex.geeksun.ir`: login rejection/acceptance, complete local inventory (8 models, 15 projects, 35 active catalogue entries and 96 archived entries), mirrored events, reconnect and session revocation. File/image uploads and all access profiles also passed through the deployed proxy. See `artifacts/v0.3-proxy-verification.json` and `artifacts/v0.3-mobile-proxy.json`. The existing Nginx vhost forwards to `192.168.1.2:8787` and allows 21 MB bodies; no proxy change was needed in this verification.

`sha256sum -c artifacts/SHA256SUMS` passed for the delivered APKs. The 0.3.0 release APK installed successfully over the existing release in the emulator (`artifacts/v0.3-release-install.txt`). No physical phone was available.

Actual conversation requests containing text and a valid PNG were acknowledged by both the temporary backend and the deployed HTTPS/WSS backend, then failed with provider HTTP 401 (`artifacts/v0.3-model-local.json` and `artifacts/v0.3-model-proxy.json`). Successful model replies and image understanding were unverified in that run. The credential was repaired later on October 11; text streaming and a follow-up now pass through the deployed domain, while image understanding remains unverified. See [the provider repair](local-wireguard-deployment.md#provider-authentication-repaired-on-october-11-2026). App username/password authentication does not renew model-provider credentials. The temporary backend was closed after checks; the deployed user service remains active.

Physical-phone behavior, file-provider compatibility across devices, actual approval-driven commands, reboot recovery and hardware animation performance require separate verification. The previous software-emulator benchmark did not meet the frame-budget target.
