# Visible account creation (0.9.3)

The Create user form previously closed as soon as the request started. A
failed creation appeared only as a message on the underlying list, which could
then be cleared by a refresh. The toolbar refresh also loaded chat history
instead of users. An older in-flight user-list response could overwrite a
newer account list.

The form now stays open while creating an account and on failure. It preserves
the entered fields for correction, displays errors inside the form, and blocks
duplicate submissions/dismissal while waiting. Passwords remain in memory only.
The form closes after the server confirms the mutation. Its returned account
is added immediately; failure to refresh the complete list afterward reports
that creation succeeded and keeps the new row visible.

List reads carry a revision checked inside the state update. Starting an
account change invalidates earlier reads and their errors. Both refresh
buttons on Manage users now load users, and the unrelated toolbar New chat
action is hidden there. Unavailable usernames explain that an existing or
deleted account may already own the name.

The screenshot's single-account list matched the live HTTPS API and registry
at investigation time: one active account and five reserved deleted usernames.
The original attempted username/status was unavailable, so the exact rejection
for that attempt cannot be established. Production accounts and credentials
were not changed during the investigation.

## Verification

Evidence is retained under `artifacts/verification/user-creation/`. Tests use
disposable HTTP fixtures rather than creating production accounts. The target
cases are creation rejection with retained form data, successful creation
followed by list-refresh failure, duplicate-submit protection, toolbar user
refresh, a delayed old list arriving after creation, deletion regression, and
expired-session/account-switch isolation.

`scripts/build-local.sh` passed all 76 backend tests, all 255 Android unit
tests, lint, and signed APK builds. Four Android 11 emulator tests passed
against the final app, including the creation/race cases and existing deletion
and account-isolation regressions. The creation UI check also ran directly
for fixture screenshot capture. No physical phone installation is claimed.

This is an Android-only change. Publish the signed 0.9.3/code 37 APKs with
`scripts/deploy-app-updates.sh`; no backend restart is required. Open Settings
→ App updates → Check for updates, Download, then Install update. On Manage
users, choose Create user and use a new username when a deleted name is reserved.

Both live channels advertise 0.9.3/code 37. Authenticated HTTPS metadata and
complete release/debug downloads passed size and SHA-256 checks through SSH
forwarding with TLS hostname/certificate verification. The production service
PID/start time stayed unchanged, and the live account list stayed at one user.
