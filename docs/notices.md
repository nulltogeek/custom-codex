# Problem notices

Connection warnings disappear when the RPC connection returns to Connected.
Search and catalogue warnings disappear after those reads succeed. History
warnings disappear only after authoritative history for the affected chat loads.
If a message or answer was accepted but history could not refresh, its history
warning follows the same recovery rule.

Queue-storage warnings disappear after storage is recovered and the queue is
resumed. A queued-delivery warning disappears after its remaining failed or
uncertain messages are edited, reviewed for retry or removed. Reconnecting alone
does not resolve uncertain message delivery. Other action/session warnings stay
available until dismissed or handled by their existing flow.

Each problem has its own notice key, so recovering from one problem does not
remove another warning. Every banner also has a manual dismiss button. These
notices use actual success events rather than a timer or matching error text.

## Verification

After restoring filesystem access, the following command passed on October 13,
2026:

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  :app:testDebugUnitTest :app:lintDebug :app:compileDebugAndroidTestKotlin
```

All 141 JVM tests passed with zero failures, errors or skipped tests, including
six notice-recovery tests and both socket-dependent suites. Android lint passed,
and instrumentation-test Kotlin compiled, including the two new Compose tests.
`git diff --check` passed. The Gradle log is saved in
`artifacts/auto-dismiss-info-notices-gradle.txt`.

Earlier restricted-session verification used the cached Kotlin/Compose compiler
2.1.20 directly and passed 121 JVM tests. Its compiler/test logs and standalone
script remain in `artifacts/auto-dismiss-info-notices-verification/`.

Device instrumentation tests were not executed. No new APK was produced; the
release version remains 0.7.16.
