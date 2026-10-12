# 0.7.15 verification — October 12, 2026

Version **0.7.15 / code 23** explicitly handles **Shift+Enter** in the composer
instead of depending on the keyboard's default handling. It inserts a newline
at the cursor or replaces the selected text, moves the caret after the newline
and does not send. Shift+Numpad Enter works too. Plain Enter and the software
keyboard Send action retain the existing send checks. Newlines can still be
inserted while Send is disabled. The change is committed and pushed as `e06050d`.

## Executed verification

- **132 JVM tests pass**, zero failures/errors/skips. Three new tests verify
  insertion in the middle of a draft, forward/reverse selection replacement,
  composition completion, and empty/multiline/Unicode drafts and caret positions.
- Debug/release and instrumentation APKs build successfully. Android lint
  reports **No issues found**.
- Updated Compose tests compile for exact Shift+Enter text, cursor placement,
  selection replacement with Shift+Numpad Enter and disabled-send editing.
  Existing Enter, IME Send and pasted multiline checks also compile.
- Both signatures verify and signing certificates match published 0.7.14.
  The publisher verifies package IDs, version 0.7.15/code 23 and minimum SDK 30.
- Authenticated loopback checks verify update metadata and APK downloads for both
  channels, including lengths, SHA-256 digests and delivery copies. Requests
  without authentication or with a browser Origin receive 401. Delivery checksums
  and `git diff --check` pass. Backend tests were not repeated for this input change.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
python3 /tmp/custom-codex-0715-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.15.txt
node /tmp/custom-codex-0715-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

Previous APKs are preserved under `artifacts/v0.7.14/`. Evidence is in
`artifacts/v0.7.15-android-build.txt`, `v0.7.15-signing-verification.json` and
`v0.7.15-local-update-verification.json`. Transport checks use an ephemeral
loopback host without production credentials. No host restart is required.

## Unverified

No Android device is connected. Native interaction tests, real hardware/software
keyboard behavior and update installation remain unverified on a phone.
