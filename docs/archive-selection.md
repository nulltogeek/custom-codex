# Selecting archived chats for deletion

In Archived, hold a chat to select it. Selected rows show a checked box and
highlight. Tap another chat to add it, or tap a selected chat to deselect it.
A fixed bar shows the selected count and Delete. Delete opens a confirmation
for exactly those chats. Cancel closes the dialog while keeping the selection;
Back or the selection bar's close button exits selection mode.

The row's favorite, options and swipe actions are hidden or disabled during
selection. Normal row taps still open chats outside selection mode. Search,
project, destination or account changes clear the selection. Restored or removed
chats are pruned, and a confirmation with missing targets cannot be submitted.
Selection supports accessible long-press, checked state and descriptive controls.

Delete is disabled while disconnected or busy, or if any selected chat has a
running task, pending input or queued message. The view model claims the loading
state immediately to prevent duplicate batches and shares the existing input
mutex with sending and queue dispatch. Each chat receives a lightweight metadata
read before deletion, and eligibility is checked again after that read.

Deletes run sequentially. Acknowledged deletions use the existing local cleanup
for history, favorites and attachments. A failure stops the batch and reports
how many chats were deleted; undeleted chats remain selected when still visible.
A lost acknowledgement is never automatically retried. Session expiry and
cancellation follow existing recovery. The operation checks the account and
backend epoch and refreshes the library once after the batch. Single-chat swipe
and toolbar deletion use the same batch implementation with one target.

`ArchiveSelectionTest` covers eligibility, unique sequential deletes, metadata
checks, changing task/queue/context state, partial failures, uncertain replies,
session expiry and cancellation. `ArchiveSelectionUiTest` covers multi-selection,
confirmation/cancel, exact targets, swipe suppression, accessible long-press,
Back, disabled Delete, stale dialogs and filter changes. See
[0.7.20 verification](mobile-0.7.20-verification.md) for executed checks and limits.
