#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
# Use Google's alternate distribution endpoint only when the caller selects it.
task_args=()
if [[ "${CODEX_GOOGLE_CDN:-0}" == "1" ]]; then task_args+=(-PcodexGoogleCdn=true); fi
./gradlew --no-daemon "${task_args[@]}" :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
mkdir -p artifacts
cp app/build/outputs/apk/debug/app-debug.apk artifacts/codex-remote-debug.apk
cp app/build/outputs/apk/release/app-release.apk artifacts/codex-remote-release.apk
sha256sum artifacts/*.apk > artifacts/SHA256SUMS
task_version="$(node -e 'const fs = require("node:fs"); process.stdout.write(JSON.parse(fs.readFileSync("app/build/outputs/apk/release/output-metadata.json", "utf8")).elements[0].versionName)')"
task_notes="${1:-docs/release-notes/$task_version.txt}"
if [[ -f "$task_notes" ]]; then
  node scripts/publish-app-update.mjs "$task_notes"
else
  echo "APKs built. Add release notes and run scripts/publish-app-update.mjs to publish the in-app update."
fi
