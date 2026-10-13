# Live command history across navigation

Command cards observed by the Android app remain available when leaving a chat
for the main menu, another chat or a project, then returning. Live events update
each chat independently of the selected screen. Saved history is merged with
observed cards, because an in-progress turn can return incomplete saved items
even with a newer bridge watermark. Commands remain ordered before the final
answer and can be loaded through earlier-history batches.

The cache retains at most eight recently touched chats and 256 items per chat
in session memory. It is cleared for a different account or backend epoch and
on sign-out; deleted chats are forgotten. App process restarts and eviction
still depend on server-saved history. Subscription replay is captured even
before the initial connection snapshot reaches the view model. Duplicate events
are ignored, and saved output prefixes seed subsequent streamed deltas.

Saved terminal turn status settles stale running cards, and completion events
received while browsing elsewhere clear the active turn. Command-output download
preferences remain effective; refreshing with output enabled restores hydrated
output. There are no additional RPCs, downloads or history polling.

`LiveChatHistoryTest` covers repeated reopening, missing saved commands, output
and completion in another chat, duplicate replay, ordering, stale status,
output preferences, hydration, account/epoch isolation, cleanup, bounds and
expanded history windows. See [0.7.19 verification](mobile-0.7.19-verification.md).

From 0.7.22, user messages from live events and saved history are matched by
turn and input when their item IDs differ. The saved ID remains canonical for
editing and later completion events. Repeated inputs in different turns,
separate occurrences and different attachments remain separate. See
[message identity](message-identity.md).
