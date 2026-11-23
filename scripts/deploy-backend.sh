#!/usr/bin/env bash
# Build/check locally; transfer a prepared runtime and restart only on the target.
set -euo pipefail
cd "$(dirname -- "${BASH_SOURCE[0]}")/.."
task_host=nulltogeek@192.168.1.7
task_remote=/home/nulltogeek/Desktop/nulltogeek/custom-codex
case "${1:---preview}" in
  --preview)
    rsync -azni --checksum bridge node_modules package.json package-lock.json "$task_host:$task_remote/"
    echo 'Preview only. Use --apply to stage, check idleness, back up, and activate.'
    exit 0 ;;
  --apply) ;;
  *) echo 'Usage: deploy-backend.sh [--preview|--apply]' >&2; exit 2 ;;
esac
npm run check
npm test
task_stamp="$(date -u +%Y%m%dT%H%M%SZ)-$$"
task_stage="/home/nulltogeek/.local/state/custom-codex-deploy/$task_stamp"
ssh "$task_host" "umask 077; mkdir -p '$task_stage/payload'"
rsync -az bridge node_modules package.json package-lock.json "$task_host:$task_stage/payload/"
# A supplied current login stays on this machine. The check URL may be an SSH
# tunnel; no password or session is copied into the deployment payload.
task_idle_at=0
if [[ -n "${CODEX_INITIAL_LOGIN_FILE:-}" ]]; then
  node scripts/verify-runtime-idle.mjs "${CODEX_DEPLOY_CHECK_URL:-https://codex.geeksun.ir}"
  task_idle_at="$(date +%s)"
fi
ssh "$task_host" bash -s -- "$task_stage" "$task_idle_at" <<'REMOTE'
set -euo pipefail
task_stage="$1"
task_idle_at="$2"
task_root=/home/nulltogeek/Desktop/nulltogeek/custom-codex
task_node=/home/nulltogeek/.local/lib/custom-codex/runtime/bin/node
exec 9>/home/nulltogeek/.local/state/custom-codex-deploy/deploy.lock
flock -n 9 || { echo 'Another deployment is running.' >&2; exit 1; }
cd "$task_root"
# Fails closed if saved login credentials are missing/obsolete or work is active.
if [[ "$task_idle_at" == 0 ]]; then
  "$task_node" scripts/verify-runtime-idle.mjs http://192.168.1.7:8787
else
  [[ "$task_idle_at" =~ ^[0-9]+$ ]] || exit 1
  task_idle_age=$(( $(date +%s) - task_idle_at ))
  (( task_idle_age >= 0 && task_idle_age <= 30 )) || { echo 'Local authenticated idle check expired; retry deployment.' >&2; exit 1; }
fi
# The legacy idle probe covers the administrator runtime only. Refuse activation
# while separate user runtimes exist, even if the administrator is idle.
command -v docker >/dev/null || { echo 'Cannot check user runtime containers.' >&2; exit 1; }
task_tenants="$(docker ps --filter name=codex-user- --format '{{.Names}}')"
[[ -z "$task_tenants" ]] || { echo 'User runtimes exist; wait for them to close before deployment.' >&2; exit 1; }
tar -czf "$task_stage/previous-backend.tar.gz" bridge node_modules package.json package-lock.json
task_stopped=0
rollback() {
  if [[ "$task_stopped" == 1 ]]; then
    rm -rf -- "$task_root/bridge" "$task_root/node_modules"
    tar -xzf "$task_stage/previous-backend.tar.gz" -C "$task_root"
    systemctl --user restart codex-remote.service
    echo "Restored previous backend from $task_stage" >&2
  fi
}
trap rollback EXIT
systemctl --user stop codex-remote.service
task_stopped=1
rm -rf -- "$task_root/bridge" "$task_root/node_modules"
cp -a "$task_stage/payload/." "$task_root/"
systemctl --user start codex-remote.service
task_healthy=0
for task_attempt in {1..20}; do
  if systemctl --user is-active --quiet codex-remote.service && curl -fsS --max-time 3 http://192.168.1.7:8787/healthz; then
    task_healthy=1
    break
  fi
  sleep 1
done
[[ "$task_healthy" == 1 ]] || exit 1
task_stopped=0
echo "Backend deployed. Backup: $task_stage/previous-backend.tar.gz"
REMOTE
