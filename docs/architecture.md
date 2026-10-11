# Android remote Codex architecture

## Objective

A native Android 11+ client for a local Codex app-server, with optional desktop-owned runtime sharing. All execution, authentication, tools, project files and permission policies remain on the host.

## Current standalone deployment

The user selected independent app-server operation using the desktop's existing Codex home. `bridge/standalone.mjs` starts bundled Codex 0.159.2, sends the public initialization handshake and keeps its process under the Linux user service manager. `CODEX_BRIDGE_BIND` defaults to loopback and is explicitly `192.168.1.2` in this deployment. Nginx on the VPS proxies HTTPS/WSS over WireGuard; bearer authentication and VPN restrictions are both required. See [deployment](local-wireguard-deployment.md).

Both remote clients share one server runtime. The ordinary desktop reads the same persisted home but owns separate live turns and approvals. Desktop-only client tools return an explicit unsupported error in standalone mode. Human approvals require an explicit answer from a connected client. No original desktop restart is needed.

## Optional desktop-owned transport

The installed desktop bundles Codex 0.159.2. Its public JSON-RPC protocol is documented at https://developers.openai.com/codex/app-server/. The desktop also has private extensions, including project/list, which are not in the public schema. The installed desktop accepts CODEX_CLI_PATH. This launcher hook is observed implementation behavior, not an OpenAI compatibility guarantee.

The launcher substitutes our CLI wrapper for the primary desktop app-server process. The wrapper starts the real bundled binary with the original arguments, forwards initialization unchanged, and recognizes the desktop by initialize.clientInfo.title. Other CLI commands and auxiliary app-server clients retain ordinary behavior. The bridge owns one upstream stdio connection; both desktop and phone requests use that connection. It remaps client request IDs and routes results to their origin. Notifications are mirrored. Desktop-only server requests (attestation and dynamic tools) stay on desktop. Human approvals/questions are mirrored, and the first valid answer is forwarded once to the original backend.

The installed desktop marks readiness after the successful initialize response and does not emit initialized. The bridge accepts this observed behavior and also transparently forwards initialized from clients that use the documented notification.

The bridge binds to 127.0.0.1:8787. Tailscale Serve supplies private HTTPS/WSS; application bearer authentication is required as well. No public unauthenticated listener is created. A token file is generated with mode 0600. Credentials never appear in URLs, command arguments, logs or repository files. Android encrypts the token with Android Keystore.

## Bridge interfaces

- GET /healthz: non-sensitive process health.
- GET /v1/host: authenticated runtime identity, readiness, protocol and version.
- GET /v1/projects: authenticated normalized project inventory and legacy chat assignments.
- GET /v1/history?archived=BOOL&cursor=CURSOR: read-only, paginated persisted summaries across every provider, including child records hidden by the root thread API. Runtime thread/list enriches those summaries with live status.
- GET /v1/snapshot: authenticated stream sequence, epoch and pending human requests.
- /v1/rpc WebSocket: authenticated JSON-RPC requests/responses. Notifications are wrapped as {type:"event",epoch,sequence,message}; bridge/subscribe accepts the last epoch/sequence, replays bounded events or reports a replay gap. bridge/respond answers a mirrored server request. The connection never auto-retries mutations.

Phone RPC responses also carry bridgeEpoch/bridgeSequence. A history snapshot uses that marker to discard older buffered deltas while retaining newer item updates. Project assignment uses the observed desktop extension thread/metadata/update.projectId; a protocol rejection is surfaced, not silently hidden.

Android tracks timeline, status, title and pending-request sequences independently. A late history response cannot overwrite a newer status/title event, and an older HTTP pending snapshot cannot restore an approval already resolved through the WebSocket. Epoch changes invalidate these markers. Foreground connection startup runs on the main dispatcher to serialize lifecycle decisions; parsing and networking run off the main thread. Cache reads and writes are synchronized to prevent overlapping reconnect/history updates from corrupting the local snapshot.

Project inventory reads only selected keys from .codex-global-state.json, including local-projects, project-order, thread-project-assignments and project ID migration mappings. The desktop's live project/list responses augment this inventory. Metadata is never rewritten directly. Lists preserve projects without chats. Chat lists explicitly request all supported source kinds and query active and archived separately. Host project mappings determine grouping; cwd is only a fallback.

## Android

Kotlin, Compose, coroutines, StateFlow, OkHttp and Kotlin serialization. Navigation: connect, chats, projects, conversation, changed-file review and settings. Device navigation is independent; conversation state is shared. Model and effort options come from model/list. New chats inherit host policy; the phone never broadens sandbox or approval settings.

Lazy stable-key lists and off-main-thread parsing keep work out of rendering. Streamed text is batched around 50 ms. History is loaded on demand; unsupported pagination falls back to a full authoritative read. Tool output and diffs are progressively exposed. A bounded private cache enables offline browsing and drafts. Drafts are keyed by chat; uncertain sends are never automatically retried. Foreground reconnect performs initialization, event subscription and authoritative state refresh.

Send acknowledgement clears a stored draft only if its text still matches the submitted prompt. A newer edit made while sending remains saved, including when the user switches chats or recreates the app. Draft operations serialize across store instances through the shared preferences lock.

Unpairing cancels the connection and removes the saved credential while retaining the Activity's actual foreground state. Pairing again in the same visible Activity starts reconnect immediately; no background/resume cycle is required.

Dark palette: charcoal, yellow highlights, high-contrast text, inspired by Binance. Light palette: white/lavender surfaces and purple highlights, inspired by Kraken. Dark/light/system selection overrides dynamic system color. The app is independently branded.

## Limits

In optional desktop-sharing mode, the desktop must launch through the bridge after a one-time restart. An already-running private stdio connection cannot be attached retroactively. Closing the desktop/its backend ends this connection. Primary desktop compatibility and auxiliary process detection are tested separately; future desktop updates may need adapter changes. Pending approvals are retained in bridge memory, not auto-approved. Backend restart starts a new epoch and requires resynchronization. Unsupported history formats or client-only desktop tools are surfaced explicitly.

Physical-device timing and actual tailnet connectivity require a connected device and signed-in Tailscale installation. Compilation and simulated transport tests do not establish those results.
