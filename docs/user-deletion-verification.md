# User deletion and question input (0.9.2)

Version 0.9.2/code 36 adds administrator deletion through Manage users. The
confirmation explains session revocation, stopping running work, retention of
saved server files, and reservation of deleted usernames. The owner is
protected in both the client and server. Deleted names remain reserved to
prevent older clients' username-scoped caches from being inherited.

The registry commits removal before ending sessions. Concurrent password/account
changes block deletion, and a password verification already in progress cannot
mint a session afterward. Runtime retirement prevents in-flight startup requests
from reopening a deleted user's runtime. No production accounts were deleted
for verification; deletion tests use disposable local accounts.

Typed Codex answers were already supported. The release verifies suggested
choices, custom text, questions without options, and both blocking and
asynchronous submission paths. See [answering questions](interactive-questions.md).

## Local verification

- `scripts/build-local.sh` passed backend syntax checks, all 76 backend tests,
  all 255 Android unit tests, debug lint, and signed debug/release builds.
- Five Android 11 emulator tests passed: confirmed user deletion with owner
  protection, cancellation and failed-request recovery; mixed choice/text
  answers; connection/failure answer retention; asynchronous question controls;
  and blocking-question text entry above a conversation.
- The first deletion UI test accidentally closed its Activity when dismissing
  a popup through the Activity dispatcher. It was corrected to send the Android
  Back key to the focused popup. An unavailable Espresso helper was replaced
  with the existing instrumentation API. The complete targeted run then passed.
- APK signatures and package/version/minimum-SDK metadata passed publication
  checks. The certificate matches the 0.9.1 feed, allowing an in-place upgrade.

Evidence: `artifacts/verification/user-deletion/`. Phone installation and a
real model-generated question answered on a physical phone remain unverified.

## Production deployment, November 23, 2026

An authenticated idle check found zero loaded chats and zero pending requests.
No user runtime containers existed. The backend helper staged and backed up
source, restarted the remote service, and passed health checks. The current
admin credentials stayed on the development machine and were used over SSH
forwarding; credentials were excluded from deployment and Git.

Both live update channels now advertise 0.9.2/code 36. Authenticated TLS checks
through `codex.geeksun.ir` verified administrator access, the deletion capability,
unauthenticated deletion rejection, exact discovery metadata, and complete APK
downloads with matching size/SHA-256. The TLS checks used SSH forwarding with
normal hostname and certificate verification because direct TLS from the local
machine failed. The backend restart invalidated old login sessions.

Open Settings → App updates → Check for updates, Download, then Install update.
Android requires installation confirmation. Sign in again after the service
restart, then open Manage users → user menu → Delete user.
