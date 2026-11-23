# Phase 1: desktop-style Android workspace

Version 0.9.0, Android version code 34. Development and builds run locally;
deployment files live on `nulltogeek@192.168.1.7`.

## Behavior

- At 840dp and wider, a persistent 272dp sidebar can collapse and reopen.
  Phones use a slide-out drawer. Navigation includes search, projects,
  pinned chats, recent chats, archives and account settings.
- Saved pins reconcile with live chat names and archive status. Pins do not
  duplicate recent rows. Project search matches names and folder paths;
  favorite projects appear first. Pins remain local to each account/device.
- The conversation header shows its title and project or mode. Conversation
  content is centered with a maximum width of 860dp, in light and dark themes.
- Chat menus expose pin/unpin, rename, move and archive. The current idle chat
  can continue in a new chat using the existing server fork operation. History
  and draft are copied for review; nothing sends automatically. Running,
  offline, uncertain-delivery and busy states disable this action.
- The rounded multiline composer groups attachments, Chat/Codex mode, model
  settings, Send/Queue and Stop. Existing attachment, delivery, queue and access
  controls remain connected. Enter sends; Shift+Enter inserts a line break.
- New-chat starter cards prepare editable drafts and never send on selection.

This phase covers the shell, navigation, conversations and composer. Dedicated
terminal, Git, browser and integration panels remain future work.
Version 0.10.0 adds the [project file browser and editor](project-files.md).

## Local verification

- 252 Android unit tests passed; debug lint reported no issues.
- 73 backend tests and backend syntax checks passed.
- Signed debug and release APKs built with the project-local JDK 17 / SDK 35.
- 12 targeted Android 11 emulator UI tests passed: phone navigation and chat
  actions, light/dark conversations, settings, starter cards, narrow composer,
  visible keyboard controls, long drafts, active Stop, Enter/Shift+Enter, IME
  submission and multiline paste; plus tablet sidebar collapse/reopen.
- The 320dp fixture measured an empty composer at 112dp and a long draft at
  208dp. Phone tests used 384dp and tablet tests used 1280dp window widths.
- Screenshots were visually inspected in `artifacts/screenshots/phase1/`.
  Build and test logs are retained locally in `artifacts/verification/phase1/`.

UI screenshots use fixture conversations. They verify rendering and controls;
no physical phone installation or real model turn is claimed by these tests.

## APK installation and deployment

Release APK: `artifacts/codex-remote-release.apk`.
Debug APK: `artifacts/codex-remote-debug.apk` (separate Android application ID).

Both APKs use the target's current development certificate, SHA-256
`be39a12735de524caac88a05507203c677b549ac0a35aa04291bff6c7088aab9`.
They retain the 0.8.0 identity. The published 0.7.24 APK uses certificate
`6bf20e016d0d7ab6d275122193f6e6e07b7798f2c1b054e3b177395bd0f7aad7`;
Android cannot upgrade that installation in place. Users of 0.7.24 must remove
the old app and install this APK, then sign in again. Removing the app deletes
its device-local drafts, pins and queued messages; save those first. Server
chat history remains on the deployment host.

Android source changes and manual APKs were delivered into the existing target
project, with a backup of overwritten source. Versioned APKs live at
`artifacts/releases/0.9.0/`. The initial delivery left the updater advertising
0.7.24; that publication gap was subsequently fixed by publishing 0.9.1 to
both live feeds. See [in-app updates](in-app-updates.md). No phase 1 backend
source change is required.
