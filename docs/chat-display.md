# Conversation display and image previews

Chats show your messages and Codex's final replies. Reasoning, progress messages,
terminal output and intermediate edits stay out of the conversation. The running
status and approval/input buttons remain available while work is in progress.
Older messages without a message phase use the last assistant reply from each
finished turn.

Each finished turn has one changes summary below its final reply. Tap a file to
inspect its diff. When the server supplies a cumulative turn diff, it replaces
the intermediate patches and survives history refreshes and local cached reopen.
Older history without a cumulative diff groups saved patches by file in one
summary.

Tap an image thumbnail in the composer or in a sent message to open a full-screen
preview. Close it with the close button or Android Back. Uploaded images come
from the authenticated host; embedded image history can also be displayed.
Missing or unsupported images show an unavailable message. Image decoding is
bounded to keep large photos from exhausting phone memory.

These changes are published as **0.7.6 / build 14** through **Settings → App
updates**. Sent uploaded-image previews also require the updated bridge process,
which adds the authenticated `GET /v1/attachments/image` endpoint. Publishing
the APKs does not restart the running server. See
[release verification](mobile-0.7.6-verification.md) for checks and limits.

When you scroll up, a down-arrow button appears at the bottom right. Tap it to
return to the latest message and resume following new replies. The button keeps
an accessible “Jump to latest” label without visible text.

Open **Archived** in bottom navigation to browse archived chats, including agent
chats. Search and project filters apply to this section, and older archived
pages load as you scroll. Open a chat and tap **Restore chat**, or swipe right
on its row, to review the restore confirmation. Back returns to Archived.
