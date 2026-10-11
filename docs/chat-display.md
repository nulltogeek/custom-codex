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

The Android APK must be rebuilt and installed. Sent uploaded-image previews also
require the updated bridge process, which adds the authenticated
`GET /v1/attachments/image` endpoint. This change does not publish an app update
or restart the running server.
