# Chats list pagination (0.7.3 / code 11)

Update through **Settings → App updates**. The Chats list fetches the next
50-record catalogue page as you approach the bottom. New rows are merged by chat
ID into the same keyed list, preserving the visible chat. A loading indicator
appears during requests; failures stop automatic requests and offer **Tap to
retry**. Switching to another catalogue does not inherit the first one's error.

Recent uses active history; Archived uses archived history. Agent chats now
continues through both catalogues, including older archived agents. Favorites
uses the saved local favorites. Project and category filters may hide every row
in a page, so the list continues checking pages until matching chats fill the
screen or history is exhausted. This can require several requests for sparse
projects. Changing tab, search or project resets the scroll position for that
view.

Previously, refreshing/reconnecting replaced loaded pages with the first page.
Now refresh keeps older loaded records while updating first-page metadata. If
the server returns a complete catalogue, stale cached records in that catalogue
are removed. Pagination restarts from the refreshed first-page cursor to avoid
skipping records when new chats shift offsets; overlapping pages are deduplicated.
Older retained rows are reconciled when their pages are encountered again.

Search, refresh and pagination share a request mutex. Each queued pagination
request carries its cursor, search and catalogue revision; outdated callbacks
are discarded. Search replaces its raw cache, preventing later scroll requests
from mixing in an earlier search. Prefetch waits for the new page's rows to be
measured before deciding whether another page is needed, avoiding eager loading
of the entire catalogue after one scroll.

This change concerns the **Chats list**. Scrolling up through older messages in
an open chat retains the existing history-loading behavior.

The release changes only the Android app. No backend restart or Nginx reload is
needed. See [verification](mobile-0.7.3-verification.md) for executed checks and
the native-device testing limit.
