# Project files and editor (0.10.0 / code 39)

Open **Projects** and tap a project's folder icon. You can also use **Open
project files** in the header of a project's chats. Projects need at least one
saved folder; projects without folders retain their existing folder information
dialog. The updated standalone backend advertises `capabilities.projectFiles`.

Select a root folder, open subfolders, or search filenames from the current
folder. Search is case-insensitive and bounded to 10,000 entries, 200 matches,
32 levels and three seconds. It skips `.git`, `node_modules`, `.downloads`,
`.gradle`, `build` and `__pycache__`; browsing those directories directly remains
possible. Large directory listings load 200 entries per page, with a limit of
10,000 entries per directory. Links and special files are listed but cannot be
opened. Search limits are reported on screen.

Tap a UTF-8 text file to open the monospace editor. Files up to 256 KB are
supported. Save writes to the deployment server and preserves the file's mode.
The editor reports read-only files and rejects binary or invalid UTF-8 files.
Line endings and Unicode are preserved. Changes remain in memory across screen
rotation; they are not a durable offline draft and must be saved before closing
the process or signing out.

Closing, switching files or roots, navigating back, or reloading prompts before
discarding unsaved edits. A stale save returns a conflict while keeping edits
available to copy. Reload the newest server version after copying needed edits.
Saves are never retried automatically after connection failures. Wider screens
show the browser beside the editor; phones show one at a time.

## Server boundaries

`POST /v1/files` accepts an authenticated JSON request containing `action`,
`projectId`, `rootIndex` and relative `path`. Actions are `list`, `search`, `read`
and `write`. Listing uses `offset`; search uses `query`; writes require `content`
and the SHA-256 `revision` returned by the last read. The client cannot select an
arbitrary host root. The server obtains project roots from that user's catalogue.

Regular-user operations run through an internal `command/exec` in their existing
Docker runtime. Phones do not gain general command-execution access. Roots must
remain inside the user's private workspace, and Python resolves them inside the
container. Owner operations use the host's Python against registered project
roots. Python isolated mode prevents project modules from replacing helper
imports. No shell interprets file contents or paths.

Descriptor-relative traversal rejects symlinks below the selected root. Only
regular files may be read. Writes use temporary files and atomic replacement,
check the revision again before replacement, and retain executable permissions.
Phone saves are serialized per account so simultaneous phones cannot overwrite
each other using the same revision. External editors do not participate in that
lock; the final revision check reduces races with external writers but is not
a filesystem-wide transactional lock. Requests and results are bounded, with at
most two file operations per account and eight overall. Session cancellation
and operation generations prevent old reads from populating another account.

## Verification and delivery

Local development and builds use `scripts/build-local.sh`; deployment uses the
existing separate backend and APK publication helpers. The service must be idle
before restarting. APK 0.10.0 retains the 0.9.4 signing identity.

Verification completed on 2026-11-23:

- 84 backend tests passed in deployment checks; 257 Android JVM tests passed.
- Android debug lint reported no issues; signed debug/release APKs built locally.
- Ten distinct emulator tests passed: five file UI tests, four account-storage
  tests and the session-isolation test. The final five UI tests were rerun after
  adding the fixture theme and system insets. Phone and simulated wide-layout
  screenshots were inspected.
- Actual Codex/Docker HTTP fixtures passed on both machines, including Unicode
  file reads/writes, a 240 KB UTF-8 file, search, stale-save rejection, cross-user
  denial, traversal and symlink denial. Temporary accounts and a synthetic
  provider were used; no paid model calls or production accounts were changed.
- The backend restarted after authenticated idleness and tenant-container checks.
  Authenticated production HTTPS checks confirmed the file capability and denied
  unauthenticated file requests. Both complete APK downloads matched the local
  manifests and SHA-256 checksums. The old signing certificate was retained.

The live feeds now advertise 0.10.0/code 39. The restart ended existing sessions;
sign in again with the current password. Remote source changes and APKs are
archived under `artifacts/releases/0.10.0/`; the deployment and publication helpers
retain separate rollback backups.

See the ignored logs in `artifacts/verification/workspace-files/` for exact build,
device, container, deployment and update-download results. Physical-phone
installation is a user action and is not claimed by emulator verification.
