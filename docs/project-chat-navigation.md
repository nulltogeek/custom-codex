# Project-based chat navigation (0.9.1)

The workspace and navigation sidebar group chats by project. Project groups
start collapsed; expand a group to see its chats, or use its open button to
enter the project. Pins stay within their owning group. Chats without project
metadata or a matching project folder appear separately under **No project**,
which starts expanded and can be collapsed.

Explicit project assignments override saved metadata and folder inference.
Each chat appears in one group. Chats referencing a project no longer in the
inventory stay in an **Unavailable project** group rather than becoming
unassigned. Empty projects remain reachable. Search matches chat titles,
previews and project names, and automatically expands matching groups. Recent,
pinned, agent and archived views retain the same project grouping; an opened
project shows only that project's chats.

Resting chat rows now have an opaque background, and the Delete background
only appears during an enabled swipe on an archived row. This fixes red rows
and overlapping Delete labels on active chats without changing confirmation
or deletion permissions.

Built locally with `scripts/build-local.sh`. APKs and source are delivered to
`nulltogeek@192.168.1.7` under the existing project's
`artifacts/releases/0.9.1/`. No backend change or restart is needed. The APK uses
the 0.8.0/0.9.0 certificate, so it can update 0.9.0 in place. Both live updater
feeds offer 0.9.2/code 36, including this navigation; see [in-app updates](in-app-updates.md).
Physical phone installation is not automated.

Verification logs and fixture screenshots are retained locally under
`artifacts/verification/project-chats/` and `artifacts/screenshots/project-chats/`.

## Verification

- 255 Android unit tests and 73 backend tests passed; Android debug lint
  reported no issues. Both signed APK variants built successfully locally.
- 27 phone emulator tests passed. They cover collapsed project groups, orphan visibility, project
  isolation, search, live project moves without duplicates, pins, pagination,
  archive/restore, confirmed deletion and selection, and navigation.
- Screenshot inspection confirms resting rows no longer reveal Delete labels.
  The test hosts respect Android system insets, wait for navigation to settle,
  and keep disabled-swipe checks away from the system Back gesture edge.

The APK uses the same certificate as 0.9.0; install it over 0.9.0 to retain
local data. Legacy 0.7.24 installations require a one-time reinstall because
their signing certificate differs. Physical phone installation remains unverified.
