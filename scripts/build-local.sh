#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/local-env.sh"
cd "$task_project"
[[ -x "$JAVA_HOME/bin/javac" ]] || { echo 'JDK 17 is missing.' >&2; exit 1; }
[[ -f "$CODEX_ANDROID_KEYSTORE" ]] || { echo 'The existing APK signing key is missing.' >&2; exit 1; }
npm run check
npm test
exec ./scripts/build-android.sh "$@"
