# Standalone Codex app-server

The requested default is now a standalone OpenAI Codex app-server using the existing `/home/nulltogeek/.codex` directory. The desktop application does not need to stay open. The Linux host and server must remain running and reachable.

## Runtime ownership

The remote host starts the installed Codex 0.159.2 binary with `app-server --listen stdio://` and `CODEX_HOME=/home/nulltogeek/.codex`. It identifies itself as Codex Remote, completes the documented `initialize`/`initialized` handshake, and exposes the authenticated HTTP/WebSocket adapter (loopback by default; a selected WireGuard interface in the deployed setup) for Android. It does not copy or rewrite `config.toml`, replace credentials, or rewrite host permission policies. Mobile 0.3.0 may choose a named access preset for a thread/turn as described in [the update guide](mobile-0.3.0.md). Trusted project configuration still follows Codex's ordinary configuration layering.

The user service manager owns this server rather than Electron. Closing the desktop or disconnecting the phone does not close this server. The Android transport remains `/v1/rpc`, with HTTP host/projects/history snapshots, bounded replay and explicit pending-request responses. The current APK signs in to the standalone runtime; the historical desktop-sharing launcher uses its earlier token protocol.

Sharing a Codex home shares configuration and persisted local history, not live execution between independent processes. Work started in the standalone server is controlled through its connected phone clients. The ordinary desktop can read saved history when refreshed, but its own in-flight work, approvals and client-executed tools belong to its own runtime. The optional desktop-sharing launcher retains the earlier single-runtime behavior and uses the same default bridge port; choose one bridge mode at a time.

Project and legacy-assignment adapters read the selected keys and tables under the same Codex home, including archived and child-chat summaries and projects without chats. Desktop-only dynamic tools and attestation cannot be implemented by a closed desktop; unexpected requests receive an explicit protocol error rather than hanging or being approved. Standard server-executed tools and configured MCP servers remain on Linux.

Official references: [app-server](https://learn.chatgpt.com/docs/app-server), [configuration basics](https://developers.openai.com/codex/config-basic/). The official protocol documents stdio as the default transport. Native TCP WebSocket transport is experimental; the adapter keeps the version-tested stdio path and supplies authenticated remote WebSocket access to Android.

## Implementation acceptance

1. Start and initialize without any desktop protocol driver.
2. Verify the real backend uses the selected Codex home and that `config.toml` is unchanged.
3. Confirm host readiness, model discovery, complete paginated history, project inventory and phone RPC/events.
4. Verify human requests remain pending for explicit phone answers; unsupported client-only requests fail explicitly.
5. Confirm user-service ownership outside Electron, so desktop lifetime does not own the server.
6. Verify Android username/password login against this server, including session expiry and sign-out.

## Start in a terminal

From the repository:

```bash
npm ci
./scripts/run-server.sh
```

The launcher defaults to `$HOME/.codex`, and accepts an existing `CODEX_HOME` when intentionally configured. The default binary remains `/usr/lib/chatgpt/resources/codex`; `CODEX_REAL_CLI_PATH` can select another compatible installation. The server prints its address and initial-login file location, never the password. A terminal-launched server needs that process to remain running.

## Run independently of the desktop

Install the prepared unit and start it under the Linux user service manager:

```bash
./scripts/install-user-service.sh
systemctl --user daemon-reload
systemctl --user start codex-remote.service
systemctl --user status codex-remote.service
```

The installer refuses to overwrite an existing unit. Use `./scripts/install-user-service.sh --print` to inspect the unit without writing it. The service starts from the Linux home directory, uses the selected Codex home and binary, and requires no desktop launch override. To also start it at future Linux logins, explicitly enable it:

```bash
systemctl --user enable codex-remote.service
```

The service runs under the Linux user manager. This machine already has user lingering enabled; no logout or reboot was performed. On another host, keeping it alive after a full Linux logout requires a separate system-administration choice; closing the Codex desktop is sufficient for this use case and does not log Linux out.

Readiness probe:

```bash
curl --fail http://127.0.0.1:8787/healthz
```

Health confirms the listener. The authenticated `/v1/host` endpoint reports readiness, backend version/PID, runtime mode and configuration-file path. The initial username/password is owner-only at `~/.local/share/custom-codex/initial-login.json`; open it locally with `vim` and enter it into the APK's sign-in screen. The server stores a salted password hash in `~/.local/share/custom-codex/account.json`. `CODEX_LOGIN_ACCOUNT_FILE` and `CODEX_INITIAL_LOGIN_FILE` can select private files; `CODEX_LOGIN_USERNAME` selects the initial username (default: the Linux user). Existing accounts are preserved.

For this machine, use [the deployed WireGuard/Nginx setup](local-wireguard-deployment.md) and [username/password login](username-password-login.md). `CODEX_BRIDGE_BIND` selects an explicit IP address and defaults to `127.0.0.1`; do not bind a public interface. On other machines, optionally configure private Tailscale HTTPS with `./scripts/serve-private.sh`, then sign in using its HTTPS URL. Release builds require HTTPS. For debug APK testing over authorized USB, use `adb reverse tcp:8787 tcp:8787` and `http://127.0.0.1:8787`.

## Recovery and compatibility

Check the specific service with `systemctl --user status codex-remote.service`. If credentials referenced by configuration are supplied through environment variables, configure them privately for the server's environment; a user service does not inherit arbitrary variables from an already-running desktop. Do not put credentials in command arguments, URLs, source files or logs.

Provider authentication errors remain host-side errors. The phone login authenticates Android to this adapter and does not replace model credentials. Model availability and successful generation must be verified separately from inventory/transport readiness. Server restarts invalidate login sessions; Android requires a fresh sign-in afterward.

Finish server-owned work before stopping or restarting its service. A backend restart changes the replay epoch; Android reconciles history and never resends uncertain prompts automatically. Existing desktop work is not interrupted by starting this independent server.
