# Question navigation

Tap **Your questions** above the chat timeline. The picker lists sent user
messages, newest first, with searchable previews. Tap an entry to close the
picker and scroll directly to its highlighted bubble. **Jump to latest** clears
the highlight and resumes following new replies.
Tall questions align their beginning with the viewport so the message header and
first lines are visible.

Repeated text remains separate because each entry uses its message ID. Image-only
messages use attachment names; blank attachment messages use “Attached message”.
Search covers loaded messages. Use **Load earlier messages** in the picker to
expand cached history or fetch the next older page through the existing history
loader. The picker stays open and updates as messages arrive. Errors expose a
retry button; unavailable remote history requires a connection. Opening the
picker does not download the entire chat or bypass command-output preferences.

Scroll indices are resolved from the current presented timeline in reverse-list
order, including commands and final changes, rather than raw history indices.
No message IDs, sending behavior, backend API or queue behavior change.
