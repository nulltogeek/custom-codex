# Optional command output downloads

Settings → Data usage → **Load command output** controls terminal output downloads on this phone. It is on by default to preserve existing behavior. Turn it off to reduce bandwidth; command names, status and the heartbeat list remain available. Expanding an omitted command explains how to enable downloads. The preference persists across app restarts and sign-out.

The broker applies the preference separately for each WebSocket client. It removes `aggregatedOutput` and `output` from command execution items in all RPC responses (including paginated history, older pages, full history and resume) and notifications. Output delta events retain a small envelope with their thread, turn, item and sequence IDs, an empty delta, and an omission marker. Reconnect replay uses the same filtering. Shared upstream data, desktop frames and replay originals are preserved, so another phone can continue receiving full output.

Approval requests, user and assistant messages, file diffs, command identity, exit codes, and completion notifications remain intact. This setting covers terminal command execution output; attachments and other tool results use their existing download behavior. It does not undo bytes already downloaded or remove locally cached history. Re-enabling refreshes the open chat from the server; running commands regain complete output at completion or the next history refresh.

`bridge/commandOutput` accepts only `{ "loadCommandOutput": boolean }` and acknowledges the applied preference. `bridge/subscribe` also accepts it, applies it before replay, and returns the applied value. The app negotiates disabling before subscribing, so an older server cannot silently replay output when bandwidth savings were requested. An unsupported server requires an update and the app disconnects instead of continuing large downloads.

The separate task notification connection requests no command output on updated servers. It allows its historical behavior on older servers only while the phone's output preference remains enabled; it rechecks the preference while monitoring.

Verification: `npm run check`; `npm test` (60 backend tests, including per-client live/replay filtering, history and resume responses, byte reduction, approval preservation, and preference validation). Android verification: `./gradlew --offline --no-daemon -PcodexGoogleCdn=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest` (163 JVM tests). New transport tests exercise negotiation, reconnect and unsupported servers over loopback WebSockets; model tests exercise omission and recovery. UI tests cover the switch, persistence and expanded command placeholder. Instrumentation tests are compiled but require a device to run.

Deployment requires an updated Android app and loading the updated broker on the host. The active production backend is not restarted during this work.
