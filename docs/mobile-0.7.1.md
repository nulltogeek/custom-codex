# Desktop-owned chat recovery (0.7.1)

If Send reports that a chat already has an active writer, another Codex runtime
has it open. An idle desktop chat can still retain its writer; refreshing or
repeatedly sending cannot take ownership from that runtime.

The phone now explains the conflict and offers **Continue in a new chat**.
Confirm **Create copy** to fork the saved history into the phone's server runtime.
The app copies your latest unsent draft, uploaded attachments (including image
previews), coding/conversation mode, access choice, model and reasoning choice.
It retains the project assignment. Review the copy, then tap Send yourself.
The original chat and its unsent draft remain available as a backup.

History still being written by the other runtime may be missing from the saved
copy. Live work and pending approvals continue to belong to that runtime. Closing
the chat there and releasing its writer lets you try Send again in the original
chat instead. Never remove writer locks or terminate desktop work to bypass this
error.

Copying is disabled while offline, sending, uploading, archived or when delivery
is uncertain. Canceling the confirmation does nothing. If the connection drops
during copying, check Chats before making another copy. No prompt is sent and no
mutation is automatically retried. Inherited goals defer automatic continuation
until an explicit turn is sent in the copied chat.

The send path checks the server's loaded chats before either starting or steering
a turn. This prevents a desktop-owned chat with an active status from being
mistaken for work running in the phone's server. Resume rejection leaves the
draft and attachments intact.

Update using **Settings → App updates → Check → Download → Install**. Version
0.7.1 has version code 9 and retains the existing package/signing identities.

## Verification

`npm run test:writer` uses two real Codex 0.159.2 app-server processes with a
synthetic conversation in a temporary Codex home. It reproduces the writer
conflict, forks saved question/answer history, checks conversation permissions,
checks that the original rollout stays unchanged and verifies the original
owner remains connected. It makes no model calls and reads no desktop sessions
or credentials.

Backend regressions cover policy expansion on fork, forced goal deferral,
explicit conflict routing and rejection of raw policy overrides. JVM regressions
cover idle/active ownership conflicts before prompt mutations, single steer/start,
resumed status, unknown delivery and copy parameters. Android instrumentation
checks confirmation/cancel behavior, disabled actions and persistent draft/image
copying. See [the release verification record](mobile-0.7.1-verification.md) for
checks actually executed and device limits.
