#!/usr/bin/env bash
set -euo pipefail
if ! command -v tailscale >/dev/null; then
  echo 'Install Tailscale from https://tailscale.com/download/linux and sign in on this host and your phone.' >&2
  exit 1
fi
if ! curl --fail --silent http://127.0.0.1:8787/healthz >/dev/null; then
  echo 'Start the Codex Remote server before enabling private access.' >&2
  exit 1
fi
# Serve is private to your tailnet. Do not substitute the public Funnel command.
tailscale serve --bg http://127.0.0.1:8787
tailscale serve status
