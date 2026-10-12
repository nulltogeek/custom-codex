# Create a project

Open Projects and tap the plus button labelled New project. Enter a name and,
optionally, full paths to existing folders on your computer, one per line. Leave
folders empty to create a project without folders. Tap Create to save it on the
host and open its chat list. Creating a project does not send a message or create
folders on disk. Cancel leaves the host unchanged before submission.

Names follow the existing rename validation: 1–200 characters on one line. Up to
eight absolute folder paths are accepted. Paths are normalized and duplicates
removed. The mobile broker exposes `project/create` with only the name, roots and
an opaque request key; arbitrary project metadata is not accepted.

The app applies the returned project ID and updates its inventory cache only
after host acknowledgement. Retries reuse the same request key and details. When
the result is unconfirmed, fields remain fixed and Retry creation checks the same
operation. A rejected validation request permits corrections. Closing an
unconfirmed attempt does not undo a project that may already exist on the host.

The updated mobile broker must be loaded on the host before project creation
works. The production backend was not restarted because it owns active work.
Verification APKs were built; no new app-update version was published.

## Verification

- `npm run check` and `npm test`: all 55 backend tests pass, including project
  creation routing, stable retry keys, normalized roots and invalid request
  rejection.
- `npm run test:rename`: real app-server verification in a temporary Codex home
  proves folderless creation, inventory discovery and deduplicated retries before
  and after restart. Existing rename/history/membership checks also pass. No model
  calls or production data are used.
- Android verification command:

  ```sh
  ./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
    :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
    :app:assembleRelease :app:assembleDebugAndroidTest
  ```

  All 151 JVM tests and Android lint pass. Debug, release and instrumentation
  APKs build successfully. Device instrumentation tests were not executed. The two new Compose creation
  dialog tests compile into the instrumentation APK.
- `git diff --check` passes.

Logs are in `artifacts/project-creation-backend-tests.txt`,
`artifacts/project-creation-live-check.txt` and
`artifacts/project-creation-android-build.txt`.
