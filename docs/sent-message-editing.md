# Editing sent messages

In version 0.7.14, tap the pencil beside Copy on one of your sent messages.
Change the text, optionally remove original images or other attachments, then
choose **Save & resend**. The app creates an edited chat containing the history
before that exchange and resends the revision there. The original conversation
and its composer draft stay available. Cancel sends nothing.

If work is running, choose **Stop work** in the editor, wait for it to stop, then
save. Queued messages in the original chat are paused when editing begins and
remain there for separate review. This changes conversation history only:
previous commands, file edits and other actions on the computer are not undone.

The app uses `thread/fork` with `beforeTurnId`, selected model/access options and
`deferGoalContinuation`. It reads authoritative history (including older pages)
and verifies the new chat contains exactly the earlier turns before saving or
sending its draft. Servers that ignore the boundary are rejected. For a message
sent as a follow-up in the same turn, earlier user inputs from that turn are
included with the revision; assistant replies within that exchange are regenerated.

Original non-text inputs are retained as their exact protocol objects unless
removed. The copied draft and options are saved atomically before resending.
Once a fork is acknowledged, a preparation retry reuses it. If fork delivery
cannot be confirmed, resend is blocked and the editor asks the user to check
Chats first. Message delivery uses the existing send/uncertain-delivery flow,
so a lost acknowledgement never triggers an automatic resend. If sending fails,
the edited message remains in the copied chat's composer for review.

The editor survives activity recreation through the ViewModel. An unsaved edit
is not persisted across process death. Navigating away during preparation saves
the copied draft without sending from another chat. No backend reload is needed.
