# Local development and remote deployment

Develop and build in this local checkout. The deployment host is
`nulltogeek@192.168.1.7`, and its source directory is
`/home/nulltogeek/Desktop/nulltogeek/custom-codex`.

Use `./scripts/build-local.sh` for local backend checks, Android unit tests,
lint, signed debug/release APKs, and local update staging. Use
`source scripts/local-env.sh` before direct Gradle or APK tooling commands.
The signing key in `.private/` was copied from the target's current Android
development key; preserve it and never commit it. The 0.8.0 release notes
report a key change, so verify the certificate against any older published APK
before claiming an in-place phone upgrade. Toolchains and caches live in `.downloads/`.

Production runs remotely as the `nulltogeek` user service
`codex-remote.service`, using the remote private Node/Codex runtime and remote
Codex configuration, account records, uploads, and history. Keep those there.
The phone endpoint remains `https://codex.geeksun.ir`.

Consult `docs/local-development.md` before deployment. Do not sync local
machine paths, caches, credentials, signing keys, or entire checkout contents
onto the deployment host. APK publication and backend restart are separate
operations. Check runtime idleness before restarting the production service.
