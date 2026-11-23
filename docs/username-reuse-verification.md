# Deleted username recreation (0.9.4)

Production diagnosis on 2026-11-23 found no active `amirali` record and found
`amirali` in `deletedUsernames`. The 0.9.2 deletion policy reserved that name;
0.9.3’s creation feedback fixes did not remove the restriction.

Version 0.9.4 permits identity-aware clients to recreate a deleted name as a
fresh UUID. Existing passwords, sessions, uploads, workspace and history belong
to the deleted UUID and do not become the new account’s data. Older clients
cannot recreate names or log in to replacement accounts. The Android client
clears the prior identity’s cache before saving a replacement login, while
ordinary logins preserve their data.

After updating, sign in as administrator and use **Manage users → Create user**
to create `amirali` with the desired password. This release does not create the
production account or reset any password.

## Local verification

- `./scripts/build-local.sh`: 77 backend tests, 255 Android JVM tests, Android
  lint with no issues, signed debug/release APKs and local update staging passed.
- Eight targeted emulator tests passed: three user-management UI tests, four
  account-storage tests and the account-session isolation test. They cover
  rejected creation/retry, immediate list visibility, stale-list protection,
  confirmed deletion, and clearing old history/drafts/attachments/options/
  favorites/queues/notification records on identity replacement.
- The backend tests cover delete/recreate, persistence across restart, fresh
  identity, legacy rejection, revoked old sessions, admin-list visibility,
  empty replacement history, and rejection of the former account’s upload.

## Deployment

The backend was deployed through `scripts/deploy-backend.sh` after an
authenticated idle check and a remote check for tenant runtime containers.
The helper retained a rollback archive. Authenticated production checks passed
for administrator access, user listing, the deletion capability, and rejection
of an unauthenticated deletion attempt. Private credentials stayed local.

Both live updater channels now serve **0.9.4 / code 38**. Full release and debug
APK downloads through the real production TLS endpoint matched the local
manifests and SHA-256 checksums. APK package/version/signature validation passed;
the certificate matches 0.9.3, so that version can upgrade in place.

- Release: 3,309,250 bytes, SHA-256
  `d144806abd475772e357606bb8859eb7a30e9765a58a64be8917eb1579b24c15`.
- Debug: 61,318,851 bytes, SHA-256
  `24921e206d9c27d5c0c26a7a09823f5d9abb896da6eefbed54aba2ad01024ef0`.

Local logs are retained under `artifacts/verification/username-reuse/` (ignored).
The remote release archive is `artifacts/releases/0.9.4/`; deployment and
updater-publication helpers retain separate rollback backups.

Physical-phone installation and account recreation must be completed in the
updated app; neither has been claimed as verified.
