# Regular-user login recovery (2026-11-23)

`amirali` was present and enabled, but regular-user sign-in failed with HTTP
503 when the app requested its private workspace. The production host was
missing the configured `python:3.12.14-slim-bookworm` container image. Owner
access worked because it uses the existing host runtime.

The image was installed on `nulltogeek@192.168.1.7`. Direct Docker Hub pulling
was rejected by that host's registry access policy. Its existing private
registry supplied `reg.lmngtechteam.com/library/python:3.12.14-slim-bookworm`,
which was tagged locally as `python:3.12.14-slim-bookworm`.

The mirror and an independent local Docker Hub pull reported the same manifest
digest: `sha256:392307d22300de8b5986851a12d9176dfc0fc073e65bf6523ebd7dcbeb23564e`.
The installed amd64 image ID is
`sha256:febd0be41adb897a0ab8f1f1c693d8912669ea60c4940e076e9946b60e210ef0`.
A restricted container confirmed Python 3.12.14 and glibc 2.36.

No APK, application source, production passwords, account records or service
configuration changed. The production service was not restarted. Failed
workspace startup is retried on a subsequent request, so signing in again uses
the installed image. The image must be retained during Docker cleanup.

## Deployment verification

Both existing verification scripts ran on the deployment host with its actual
Codex binary and Docker boundary, using disposable directories and accounts:

- `scripts/verify-multiuser-isolation.mjs`: two initialized real runtimes,
  model discovery, command execution, filesystem and network isolation,
  authenticated provider relay, and separate threads/projects passed.
- `scripts/verify-multiuser-http.mjs`: signup/login, workspace initialization,
  administrator controls, separate HTTP history/projects, isolated events,
  cross-user rejection and two synthetic chat responses passed. No paid model
  requests or production account changes were made by these fixtures.
- Production HTTPS checks passed for health, administrator login and user
  listing. There were two registered accounts. `amirali`'s password was neither
  requested nor reset; phone sign-in with that password remains a user check.

Logs are retained locally in `artifacts/verification/user-runtime-login/`.
