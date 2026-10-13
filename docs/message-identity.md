# Sent-message identity across history refreshes

A sent message can have a live item ID and a different rematerialized saved ID.
Previously, keeping both sets of observed items caused duplicate user bubbles
when the post-send history read completed. The history merge now pairs user
messages within the same turn by their text, attachments and normalized input,
and uses the saved ID for both copies. Harmless text-element metadata is ignored.
Command cards and other item types still reconcile by their existing IDs.

Matching is one-to-one, with shared IDs reserved first. Equal text in separate
turns, repeated saved occurrences and different attachments stay separate. A
new user event beyond the saved watermark remains eligible as a separate input.
Older redundant observations beside a saved equivalent are removed. Missing
command cards and newer command output remain preserved as before.

The live cache retains bounded aliases while their target items remain cached.
Aliases and version numbers migrate when saved IDs change again. Live item
completion events use the canonical ID, including events processed after a
history read and events buffered before that read. The visible timeline and
newer-item overlay also use these aliases. This keeps refresh/reopen behavior
consistent and retains IDs that sent-message editing can locate in saved history.

This Android change adds no RPCs or sends, and changes no queue dispatch or
message-submission behavior. Existing per-account and backend cache isolation
also clears aliases. Regression checks use synthetic live/history data in
`TimelineIdentityTest`; see [0.7.22 verification](mobile-0.7.22-verification.md).
