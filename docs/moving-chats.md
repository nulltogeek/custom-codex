# Move a chat into a project

Open the three-dot options beside a chat in Chats or Archived chats, or in the
open conversation's toolbar. Choose Move to project, select an existing project
and tap Move. The current project is marked and cannot be selected as a new
destination. If no projects exist, create one in Projects first.

The host saves the assignment using its existing `thread/metadata/update` API.
This keeps the same chat ID, history, archive status and working folder; it does
not fork the conversation, move files, send a prompt or interrupt running work.
The app updates chat lists, favorites, the open conversation, project filters,
loaded-chat counts and cached inventory after acknowledgement. Explicit project
membership takes precedence over folder-based grouping, so a moved chat does not
remain in its old project's list merely because its working folder is there.

Host membership events also update the other connected clients' chat copies.
Failures stay in the project picker for retry; repeating an assignment does not
create another conversation. Cancel closes the picker before any request is sent.

This uses an API already allowed by the deployed mobile broker and requires no
backend restart. An updated Android app is required; the verification APKs were
not published as a new app-update version.

## Verification

- `npm run check` and `npm test`: all 56 backend tests pass, including assignment routing and
  membership event delivery to two subscribed clients.
- `npm run test:rename`: an isolated real app-server moves saved synthetic chat
  history between projects and restarts. The new assignment persists; history,
  chat ID and working folder stay unchanged. An invalid destination leaves the
  previous membership intact. Existing creation/retry/rename checks also pass.
  No model calls or production data are used.
- Android command:

  ```sh
  ./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
    :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
    :app:assembleRelease :app:assembleDebugAndroidTest
  ```

  All 157 JVM tests and Android lint pass. Debug, release and instrumentation
  APKs build successfully. The two new Compose menu/project-picker tests compile into the instrumentation
  APK. Device instrumentation tests were not executed.
- `git diff --check` passes.

Logs are in `artifacts/move-chat-backend-tests.txt`,
`artifacts/move-chat-live-check.txt` and `artifacts/move-chat-android-build.txt`.
