# Automatic chat and project updates

Opening a chat displays cached messages immediately and starts a fresh history
read without blocking the composer. Opening Chats, Archived, Projects or a
project's chat list automatically fetches fresh project metadata and chat
summaries. Manual Refresh remains available, but navigation does not require it.

The visible chat starts updating as soon as the connection is established,
without waiting for the host, project catalogue or model list to finish loading.
An empty chat shows “Updating chat…” while its history is being fetched. Cached
messages stay visible during updates. Navigating away cancels the visible-chat
read; returning starts a new read. Live messages keep their sequence guards so
an older history response cannot overwrite a newer streamed response.

Library metadata and task completion events trigger a short coalesced update
while a library is visible. These requests run one at a time, with at most one
follow-up when navigation or changes arrive during a read. Project inventory,
active summaries, archived summaries and live thread status fetch concurrently.
Library navigation never downloads command output, message history, models or
attachments. Chat reads honor the existing command-output download setting.
There is no periodic full-history polling.

Temporary visible-chat read failures retry after 1 second, increasing to at most
10 seconds between attempts. Library failures retry after 3 seconds while a
library remains visible and connected. Recoverable notices clear after success.
Account, backend epoch, search revision and per-chat read versions reject stale
responses. Existing search/project filters and previously loaded catalogue pages
are retained. Backgrounding disconnects the app as before; foreground reconnect
fetches fresh data again.

Verification uses `AutomaticSyncTest` for concurrent summary requests, coalescing,
follow-up reads during IO, failure cancellation, visible-screen eligibility,
bandwidth-sensitive event filtering and stale account/backend/read rejection.
`AutomaticSyncUiTest` covers empty-history loading and retaining cached messages.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
```

Verified on 2026-10-13: 180 JVM tests passed with no failures or skips; Android
lint, debug/release APK assembly and instrumentation APK compilation passed.
Device navigation tests were not run. These changes are published in
[0.7.17 / code 25](mobile-0.7.17-verification.md); install that update to receive
them on an existing phone installation.
