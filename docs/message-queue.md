# Message queue

Send while Codex is working to queue your next message instead of interrupting
the active turn. When work finishes, queued messages start new turns in order.
Sending while this chat already has a queue appends to that queue. Stop sits
beside Send and pauses the chat's queue before stopping work.

Tap **Pause queue** to expand the queue and open the first waiting message for
editing. Saving or cancelling leaves it paused until you tap **Resume queue**.
You can also tap the queued-message count above the composer to expand the queue. Use the
arrows to move a message up or down, the pencil to edit its text or remove an
attachment, and the close button to remove it. Editing pauses the queue; use
**Resume queue** when ready. A message being sent cannot be edited or removed.
If pausing cannot be saved, the editor does not open; if saving an edit fails,
the editor keeps your draft and shows the error so you can retry.
Queues keep the model, effort, chat mode and access setting chosen when added.
Each chat can hold up to 20 queued messages, including uploaded attachments.

The queue is saved on this phone, separately for each server and account. It
sends while this app is open and connected, including queues for other chats.
Switching projects or reading another chat does not pause the queue or redirect
its messages. The account-wide dispatcher checks each chat independently; a busy
or paused chat does not block a ready queue in another project. Background queue
sends do not set the displayed chat's Send button to busy.
Temporary connection loss during a status check leaves messages waiting for the
next connected check. It does not pause them when no send was attempted. Errors
after sending still require delivery review to prevent duplicates.
After relaunching or signing in again, saved queues require **Resume queue**.
Failed or interrupted turns pause the queue. A rejected send retains its message
for review rather than moving on to the next queued message.
Failure/stop notifications pause only existing queues, from the current backend,
and only if newer than the queue's creation or last explicit Resume. Delayed
notifications cannot undo a Resume or create a pause for a future queue. A new
failure includes its reason in the expanded queue. Previously saved paused
queues still need **Resume queue**; navigation never overrides a deliberate pause.

If a request loses its delivery confirmation, the queue stays paused. Check chat
history and remove the queued message if it was delivered. Otherwise, use its
retry icon, review the duplicate-delivery warning and confirm **Retry message**,
then **Resume queue**. An in-flight request restored after a process restart
follows this same review flow. The app saves the in-flight state before sending
and never automatically retries uncertain delivery.

The sender reads live thread status before starting each turn. It never uses
`turn/steer` for queued messages. It retains the existing runtime ownership checks;
a chat owned by another writer stays queued and paused if ownership is rejected.

Regression verification for switching projects: `QueueDispatchTest` exercises
navigation during pending status reads, concurrent busy/paused projects, sending
to the original chat with saved options, connectivity/account guards, lost
acknowledgements, delayed completion notifications and backend epoch changes.
Verified with 171 passing JVM tests, Android lint, debug/release APK assembly
and instrumentation APK compilation:

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
```

Device navigation tests remain unverified. The navigation fixes are published in
[0.7.17 / code 25](mobile-0.7.17-verification.md).
