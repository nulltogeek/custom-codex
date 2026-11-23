# Local development, remote deployment

The working source is now on this machine. It was imported over SSH on
2026-11-23 from `nulltogeek@192.168.1.7` at
`/home/nulltogeek/Desktop/nulltogeek/custom-codex`. The source directory had no
Git repository; this local copy has a newly initialized Git repository.

## Build here

```bash
npm ci
./scripts/build-local.sh
```

The project-local JDK 17, Android SDK 35, and Gradle cache are under
`.downloads/`. The target's current development signing key is stored with
private permissions in ignored `.private/android/debug.keystore`. The build
uses that key without changing this machine's global Android key or Java.
The local wrapper selects Google's alternate CDN because direct Maven access
failed here; set `CODEX_GOOGLE_CDN=0` to select the normal endpoint.
For direct commands, first run `source scripts/local-env.sh` in Bash and pass
`-PcodexGoogleCdn=true` to Gradle when using the alternate CDN.

Build output is `artifacts/codex-remote-release.apk`, the corresponding debug
APK, checksums, and locally prepared update files under `artifacts/updates/`.
The build script's publication step writes local files only. It does not
upload or restart the production server.

## Deploy the backend there

```bash
./scripts/deploy-backend.sh --preview
./scripts/deploy-backend.sh --apply
```

The apply command checks the backend locally, stages source and installed
JavaScript dependencies on the target, uses the target's existing idle check,
backs up the previous backend, and restarts `codex-remote.service` there.
It restores the previous files if activation fails. The idle check requires
valid private login credentials on the target and refuses a restart if it
cannot establish idleness. A restart requires phone users to sign in again.
The health probe confirms the HTTP listener; authenticated/model checks are
still required after a substantive backend change.

This deploy helper transfers only `bridge/`, `node_modules/`, and the two
package manifests. It does not install services, replace the private remote
Node/Codex runtime, or change accounts, uploads, Codex history,
provider credentials, WireGuard, Docker, or Nginx configuration. New host
configuration requirements need their own reviewed deployment steps.

## APK publication

Production continues to use `https://codex.geeksun.ir`, backed by
`192.168.1.7:8787`. The production updater serves the target's
`artifacts/updates/`. Publishing APK updates is a separate operation from a
backend restart. Keep old content-addressed APK files and publish the channel
manifests only after uploading and validating the new APKs. Never sync this
whole checkout or use `--delete` against the remote update directory.

After a local build has staged the update files, publish the live feed with:

```bash
./scripts/deploy-app-updates.sh --preview
./scripts/deploy-app-updates.sh --apply
```

This validates APK signatures and metadata locally, checks uploaded bytes,
backs up the prior manifests, and switches the live feeds without restarting
the backend. It preserves old APKs and refuses changed or older versions and
subsequent signing-key changes. The live feeds now advertise 0.9.1/code 35;
0.9.0 installations can update in place. See [in-app updates](in-app-updates.md).

At import time, source was 0.8.0/code 33 but production update manifests were
0.7.24/code 32. The 0.8.0 release notes explicitly report a signing-key change
and say that the prior phone app must be removed first. Verify certificates
before publishing. Certificate inspection confirmed the published release uses
SHA-256 `6bf20e016d0d7ab6d275122193f6e6e07b7798f2c1b054e3b177395bd0f7aad7`,
while the current target key uses
`be39a12735de524caac88a05507203c677b549ac0a35aa04291bff6c7088aab9`.
Version 0.8.0 also requires the updated bridge and
Docker for non-admin accounts. Import and local build do not activate it.

## Validation at import

Backend syntax checks and all 73 Node tests passed locally. See
`local-build-verification.md` for the final Android build result.
