# Multi-user server (0.8.0)

The standalone bridge supports password registration and an administrator account. The existing owner `nulltogeek` remains the administrator and keeps the existing account password, Codex home, history, projects, provider configuration, and host execution behavior. Registration never grants administrator access.

## Accounts and administration

New users select **Create an account** on Android and enter a unique username and a password of at least 12 characters (maximum 1024 UTF-8 bytes). Usernames contain 1–64 letters, numbers, dots, underscores, or hyphens; new usernames are stored in lowercase and sign-in is case-insensitive. The owner name and `owner` are reserved. The server stores salted scrypt password hashes; plaintext passwords are not retained. Sessions last up to 30 days and are invalidated by a bridge restart. An account may have up to 32 sessions.

The administrator can list users, create users, disable/enable users, reset another user's password, revoke their sessions, and open/close registration. Disabling a user revokes sessions and stops their private runtime. Password reset and session revocation end authenticated client sessions while running work continues. Disabling an account retains saved chats and projects. The administrator cannot disable their own account; they use the existing Change password flow for their own password. Closing registration prevents public signup; the administrator can still create accounts.

The private `users.json` registry sits beside the existing `account.json`. The existing owner hash stays in `account.json`, so the previous local password-recovery workflow remains available. Back up these private files and the user workspace directory using the same protection as other credentials.

From 0.9.2, administrator deletion removes a non-owner account and its password
hash from the active registry, revokes all sessions/WebSockets, and retires its
runtime so in-flight requests cannot reopen it. The owner cannot be deleted.
Private workspace/history/upload files are retained for host-side recovery;
the operation does not erase server backups. `deletedUsernames` in the registry
reserves deleted names for legacy clients. From 0.9.4, clients sending
`X-Codex-Account-Identity: 2` can recreate a deleted username. The replacement
receives a fresh UUID and `accountIdentityVersion: 2`; its runtime uses the new
UUID and cannot access the deleted account’s data. Login to a replacement
requires the same identity capability (legacy clients receive HTTP 426). Android
binds cached data to the returned UUID and clears the old identity’s history,
drafts, attachments, favorites, queue and notification records before saving
the new login. Disabled accounts may also be deleted. Account changes
already in progress must finish before deletion.

## Runtime and storage boundaries

Every non-admin user gets a separate Codex home, project workspace, upload directory, backend process, event Broker, replay buffer, and approval queue. A user's HTTP requests and WebSocket are routed using the authenticated server-side identity. The phone cannot select another user's runtime.

Non-admin Codex runs inside Docker as the host's non-root UID with a read-only root filesystem, dropped capabilities, no-new-privileges, process/memory/CPU limits, and only that user's mounts. The Docker socket and the owner's Codex configuration/history are not mounted. The provider credential stays in a host-side gateway; the container receives a revocable gateway token instead.

**Non-admin containers have no general network access.** A small loopback relay forwards model requests over a mounted Unix socket. Coding commands cannot download packages, browse the Internet, reach deployment targets, or connect to host/other-user services. Packages already present in the configured image remain usable. A future controlled-egress feature would require a separate design; do not switch these containers to the default Docker bridge to work around this restriction.

Codex's default inner execution setting is intentionally permissive inside the restricted container so its commands can run without nested user namespaces. The container provides the execution boundary; explicitly selected conversation/read-only/access presets can still impose additional inner restrictions. The phone cannot request full host access, arbitrary host project roots, or select host rollout/configuration paths.

User catalogues are queried inside their backend, rather than opening user-writable database paths on the host. Upload directories are separate and read-only to runtime commands. Android also scopes history, drafts, attachments, and chat options by server/account.

## Host prerequisites

- Keep the existing private HTTPS/WireGuard deployment and Node/Codex runtime.
- Docker must be running and available to the bridge service account. Docker is a required boundary; the bridge does not fall back to executing users directly on the host.
- The configured tenant image must be present locally. The default is `python:3.12.14-slim-bookworm`, a glibc image compatible with the bundled Linux Codex binary. An administrator can prepare a different compatible image with project tools. Select it using `CODEX_TENANT_IMAGE` in the service environment. Alpine/musl images are unsuitable for the current binary.
- The selected model provider must have an HTTPS base URL and an API key available to the service. Only selected model/provider values are used, rather than copying the owner's plugins, MCP commands, trust settings, or credentials into containers.

Users can register before their private runtime has started. If Docker, the image, or provider configuration is unavailable, private-runtime startup fails with an actionable error. An existing account should sign in again after host setup is fixed, rather than registering repeatedly.

If an enabled account gets HTTP 503 immediately after sign-in, check that the
configured image exists on the deployment host: `docker image inspect
python:3.12.14-slim-bookworm`. Registration only writes an account record;
`GET /v1/host` starts its private workspace. Installing the missing image fixes
this prerequisite without an APK update or bridge restart. Do not recreate the
account or reset its password for this failure. See the [deployed runtime
recovery](user-runtime-login-recovery.md).

The default limits are 100 registered accounts (including the administrator) and eight concurrent private runtimes. Each container is limited to one CPU, 1 GiB RAM, and 128 processes. The pool reclaims disconnected, idle runtimes without pending requests, approvals, or active turns after ten minutes, or earlier when a new user needs a full pool's idle slot. Saved history/workspaces survive runtime eviction. Requests fail clearly if every runtime slot is busy. Workspace mounts use persistent host storage; these container compute limits do not establish a hard per-user disk quota.

Password hashing is globally limited to two concurrent checks/changes. Signup permits five attempts per source in ten minutes; failed sign-ins are tracked by source plus target username, with an additional source-wide ceiling. Model forwarding permits two concurrent requests per runtime and sixteen overall. Uploads permit two per user and eight overall, with the existing 20 MB file limit. Backend protocol frames are limited to 16 MiB; pending RPC counts and queued backend input are bounded as well.

## Activation and deployment

Source changes take effect only after the existing standalone bridge is restarted. Do this when server-owned turns and approvals are idle: restarting signs out existing sessions and ends that runtime's live work. Do not reset the existing owner password or replace their Codex home as part of migration.

The Android 0.8.0 build uses a new local signing identity because the old key was missing. The user accepted reinstalling. Install the new APK directly after removing the previous app; do not publish a differently signed APK as an ordinary in-app update to the old installation. Preserve the new signing key for future updates. Reinstalling removes phone-only cached history and drafts; the owner's server-side chats/projects remain on the server.

The existing desktop-sharing/token bridge remains a historical compatibility path. Multi-user signup and private runtimes apply to the standalone server.

## API contract

| Method/path | Body/result |
| --- | --- |
| `POST /v1/signup` | `{username,password}` → login session |
| `GET /v1/host` | Includes authenticated `user` and capabilities |
| `GET /v1/admin/users` | `{users,signupEnabled}`; admin only |
| `POST /v1/admin/users` | `{username,password}` → `{user}`; admin only |
| `PATCH /v1/admin/users/:id` | `{disabled}` and/or `{password}` → `{user}`; admin only |
| `DELETE /v1/admin/users/:id` | `{}` → `{ok:true}`; admin only; owner protected |
| `POST /v1/admin/users/:id/revoke` | `{}` → `{ok:true}`; admin only |
| `PATCH /v1/admin/settings` | `{signupEnabled}`; admin only |

Passwords are sent only in HTTPS JSON request bodies. Never place credentials in URLs, shell arguments, source, or logs.

`GET /v1/host` advertises `capabilities.userDeletion` only to the administrator
on a backend supporting this operation. Older clients can continue using the
existing administration routes.

## Verification

Run the backend suite with the configured Node executable and `--test bridge/test/*.test.mjs`. `scripts/verify-multiuser-isolation.mjs` uses disposable user homes, actual Codex/Docker runtimes, and a local fake model provider to verify command execution, separate chats/projects/files, provider relay authentication, and denied host/network access. It does not require a live paid model request or modify production accounts.

`scripts/verify-multiuser-http.mjs` exercises the actual standalone HTTP/WebSocket routes and Docker runtimes with disposable accounts and a local synthetic streaming provider. It checks signup/login, administrator controls, account identities, separate persisted chats/projects/events, and rejection of another user's thread IDs. Its two synthetic replies make the new staging conversations persistent without sending paid model requests or using production accounts.

Android account RPC/storage tests and targeted device tests cover cancellation, account switching, legacy storage migration, signup/admin screens, and drawer navigation. Read the task delivery verification report for the exact tests actually executed; test source alone is not evidence that device tests passed.
