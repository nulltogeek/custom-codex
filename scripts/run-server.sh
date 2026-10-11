#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ ! -d "$task_root/node_modules/ws" ]]; then
  echo 'Run npm ci in the repository before starting the server.' >&2
  exit 1
fi
if ! node -e 'const [major, minor] = process.versions.node.split(".").map(Number); process.exit(major < 22 || (major === 22 && minor < 13) ? 1 : 0)'; then
  echo 'Node.js 22.13 or newer is required.' >&2
  exit 1
fi
task_codex_home="${CODEX_HOME:-$HOME/.codex}"
exec env CODEX_HOME="$task_codex_home" node "$task_root/bridge/standalone.mjs"
