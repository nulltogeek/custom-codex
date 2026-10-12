# Rename chats and projects

Tap the pencil beside a chat in Chats or Archived chats, or in the open chat's
toolbar. In Projects, tap the pencil beside the project's name. Edit the current
name and tap Save. Cancel leaves the existing name unchanged. Saving requires a
connection and a nonblank, single-line name of at most 200 characters.

The app saves names on the host, using `thread/name/set` for chats and
`project/update` for projects. Chat names update the list, favorites, open-chat
title and saved history cache. Project names update the project list and cache.
IDs, history, folders and project membership are retained. Existing host events
propagate changes to other connected clients. Failures stay in the dialog for
retry; names are applied locally only after host acknowledgement.

The mobile broker allows project updates containing only `projectId` and `name`.
Folders, metadata and other project mutations remain outside that interface.
The updated broker must be loaded on the host before project renaming works.
The production backend was not restarted during this change because it owns
active work. The APK builds below are verification builds; no new app-update
version was published.

## Verification

- `npm run check` and `npm test`: 53 backend tests pass, including rename routing
  and rejection of folder/metadata overrides and invalid names.
- `npm run test:rename`: an isolated real app-server creates a project and opens
  synthetic saved chat history, renames both, then restarts. Names persist and
  history, IDs, roots, metadata and project membership remain unchanged. No model
  calls or production data are used.
- Android Gradle verification:

  ```sh
  ./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
    :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
    :app:assembleRelease :app:assembleDebugAndroidTest
  ```

  All 145 JVM tests and Android lint pass. Debug, release and instrumentation
  APKs build successfully. The two new Compose dialog tests compile into the
  instrumentation APK. Device instrumentation tests were not executed.
- `git diff --check` passes.

Logs are stored in `artifacts/rename-backend-tests.txt`,
`artifacts/rename-live-check.txt` and `artifacts/rename-android-build.txt`.
