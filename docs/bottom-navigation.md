# Bottom navigation restored

The app again uses the bottom menu from before the sidebar change: Chats,
Archived, Projects and Settings. Tap a destination to switch sections; the
current section is highlighted. The menu is hidden in conversations, diffs and
the sign-in screen. Back from a chat returns to its original library section,
including Archived and its project filter.

This restores the previous layout while retaining the newer chat, project,
rename and creation controls. It requires an updated Android app. No backend
change or restart is needed for this navigation change, and no new app-update
version was published during this commit.

Verification command:

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
```

All 151 JVM tests and Android lint pass; debug, release and instrumentation APKs
build successfully. `git diff --check` passes.

The existing library instrumentation checks were updated for the restored menu,
including destination selection, archived-chat return behavior and hiding the
menu inside conversations. Device instrumentation tests were not executed.
The build log is saved in `artifacts/bottom-navigation-android-build.txt`.
