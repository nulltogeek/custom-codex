#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export CODEX_CLI_PATH="$task_root/bridge/cli.mjs"
export CODEX_REAL_CLI_PATH="${CODEX_REAL_CLI_PATH:-/usr/lib/chatgpt/resources/codex}"
export CODEX_APP_SERVER_FORCE_CLI=1
if [[ ! -d "$task_root/node_modules/ws" ]]; then
  echo 'Run npm ci in the repository before launching.' >&2
  exit 1
fi
# The desktop's single-instance behavior requires quitting the old instance normally first.
exec /usr/bin/chatgpt "$@"
