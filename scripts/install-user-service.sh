#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
task_node="$(command -v node)"
task_codex_home="${CODEX_HOME:-$HOME/.codex}"
task_backend="${CODEX_REAL_CLI_PATH:-/usr/lib/chatgpt/resources/codex}"
task_unit="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/codex-remote.service"
task_mode="${1:-install}"
if [[ "$task_mode" != install && "$task_mode" != --print ]]; then
  echo 'Usage: install-user-service.sh [--print]' >&2
  exit 1
fi
python3 - "$task_root" "$task_node" "$task_codex_home" "$task_backend" "$task_unit" "$task_mode" <<'PY'
import os
import sys
from pathlib import Path
root, node, codex_home, backend, destination, mode = sys.argv[1:]
def quote(value):
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"').replace('%', '%%') + '"'
unit = f'''[Unit]
Description=Codex Remote standalone app-server
After=network-online.target
StartLimitIntervalSec=60
StartLimitBurst=3

[Service]
Type=simple
WorkingDirectory=%h
ExecStart={quote(node)} {quote(str(Path(root) / 'bridge/standalone.mjs'))}
Environment={quote('CODEX_HOME=' + str(Path(codex_home).resolve()))}
Environment={quote('CODEX_REAL_CLI_PATH=' + backend)}
UMask=0077
Restart=on-failure
RestartSec=3
KillMode=mixed
TimeoutStopSec=20

[Install]
WantedBy=default.target
'''
if mode == '--print':
    print(unit, end='')
else:
    target = Path(destination)
    if os.path.lexists(target):
        raise SystemExit('Service unit already exists; inspect it with vim before making changes. Nothing overwritten.')
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open('x') as out:
        out.write(unit)
    print(f'Prepared {target}. Run systemctl --user daemon-reload and systemctl --user start codex-remote.service.')
PY
