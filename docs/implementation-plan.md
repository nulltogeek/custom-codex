# Implementation checklist

## Updated deployment acceptance (2026-10-11)

The user selected a standalone server on this Linux machine using the existing Codex home, with the existing VPS Nginx proxy over WireGuard. This replaces the original mandatory desktop restart and Tailscale steps. Model generation and follow-ups now pass through the deployed domain. Physical-phone behavior, actual model-triggered approvals and hardware performance acceptance remain open.

- [x] Independent app-server initialized with the existing config, without stopping desktop work.
- [x] Explicit WireGuard binding and private provider environment prepared.
- [x] Existing VPS vhost backed up, updated, tested and gracefully reloaded.
- [x] HTTPS/WSS authentication, two-client events, reconnect and all saved inventory verified.
- [x] APK archive, signature and current checksum verified; setup/recovery documented.
- [x] Replace expired provider credential and verify successful model streaming and follow-ups through HTTPS/WSS.
- [x] Deliver username/password login, formatted code, attachments, access presets, coding/conversation modes and themes/animations.
- [x] Deliver APK 0.4.1 with authenticated, verified in-app updates; test emulator installer confirmation and complete proxy downloads.
- [x] Deliver compact 0.5.0 chat/project rows and a single-row composer toolbar; verify narrow screens, larger text, project paths, model/access/effort controls and keyboard visibility.
- [x] Deliver 0.6.0 scroll history/catalogue loading, collapsible code/tool outputs, image previews/cancel and unified chat settings; record UI and publication verification.
- [x] Deliver 0.7.0 right-swipe archive, chat/project favorites and in-app password changes; record final verification and publication.
- [ ] Verify a real model-triggered approval answered from the phone.
- [ ] Verify an actual phone through WireGuard and measure hardware performance.

The original checklist below records the earlier desktop-owned mode and Android implementation.

Goal: deliver a buildable APK with verified shared desktop state and documented private access.

1. [x] Inspect official app-server documentation, installed desktop protocol, launch override and local project metadata.
2. [x] Write architecture, implementation and setup/recovery documentation before code.
3. [x] Create the implementation goal.
4. [x] Implement the CLI wrapper, single-runtime broker, authentication, project adapter and replay.
5. [x] Verify ID collision handling, initialization preservation, competing approvals, desktop-only tools, disconnects, bounded replay and restart epochs.
6. [x] Prove a desktop-protocol client and phone test client share a real bundled backend. Also verify an isolated instance of the actual installed desktop owns the runtime controlled by the phone client.
7. [x] Implement native Android connection, inventory, conversation, approvals, model controls, tool output, diff review, themes, cache and drafts.
8. [x] Install missing SDK/build dependencies from official sources; use localhost:8080 for downloads only if necessary.
9. [x] Build and test the Android app, deliver APK and record exact verification. Build and additional transport verification: 16 JVM tests, clean lint and 4 focused Android 11 instrumentation checks passed. The expanded native networking test also passed on the current APK after the foreground re-pair fix. Release signature/checksums verified; see verification.md.
10. [ ] Verify the user's original restarted desktop, remote Tailscale connection and physical-device behavior when available. The original desktop is untouched; isolated actual-desktop testing passed.
11. [ ] Verify successful model generation and a real model-triggered approval in the user's authenticated desktop session. The generation probe accepted a phone steer with no duplicate turn, but the configured provider returned HTTP 401 (Invalid API key).
12. [ ] Meet the >=95% frame-budget target during release scrolling and streaming. The software-emulator release scrolling benchmark collected 643 frames with 0% within budget; no hardware or release-streaming measurement is available. The measured build precedes the draft-persistence fix.

## Acceptance

Both clients address one backend PID. Existing chats retain IDs/history and projects retain names, roots and associations. Progress and pending input propagate to both clients; one approval answer resolves both. Phone disconnect does not repeat prompts or stop desktop work. All local history is discoverable, including archived/projectless/child chats. Build artifacts include a debug APK and a release-build path. Performance target is >=95% frames within the display frame budget during scrolling/streaming; only actual benchmark output counts as measured performance.

## Out of scope for v1

Cloud/ChatGPT conversation import, plugin installation, automation management, standalone interactive terminal, arbitrary remote filesystem browsing, Play Store publishing and automatic destructive actions. Repository commits and pushes require explicit user authorization; they are separate from application behavior.
