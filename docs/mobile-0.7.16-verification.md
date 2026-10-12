# 0.7.16 verification — October 12, 2026

Version **0.7.16 / code 24** fixes the reported `PatternSyntaxException` while
rendering shell commands and fenced shell code. The shell variable rule contained
an unescaped literal closing brace. Desktop Java accepts it; Android's ICU engine
rejects it. The rule now escapes that brace. Highlighting compilation also catches
`PatternSyntaxException` and leaves plain code visible rather than closing the chat.
The fix and regression checks were committed and pushed as `463fe81`.

## Executed verification

- Native **ICU 70** reproduces the original full shell regex failure with error
  **66305 at offset 139**, matching the user's screenshot. The corrected rule
  compiles and matches plain/braced/default/special shell variable forms.
- `scripts/verify-syntax-icu.py` exports the actual generated rules from the built
  Kotlin class. All **18 language rules plus the diff-line rule** compile in ICU.
  The verifier also confirms the original shell pattern fails. This complements
  the desktop JVM tests, which could not detect the Android-specific incompatibility.
- **135 JVM tests pass**, zero failures/errors/skips. New checks cover braced
  variables across all shell aliases, invalid-rule fallback and all language rules.
- Debug/release and instrumentation APKs build successfully. Android lint reports
  **No issues found**. Two new native tests compile for all-language Android regex
  compilation and rendering/copying a shell command containing braced variables.
- Both APK signatures verify and certificates match published 0.7.15. The
  publisher verifies package IDs, version 0.7.16/code 24 and minimum SDK 30.
- Authenticated loopback update metadata and APK downloads pass for both channels,
  including sizes, SHA-256 digests and delivery copies. Unauthenticated requests
  and browser-Origin requests receive 401. Delivery checksums and `git diff --check`
  pass. Backend tests were not repeated for this Android rendering fix.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
python3 scripts/verify-syntax-icu.py --report artifacts/v0.7.16-icu-verification.json
python3 /tmp/custom-codex-0716-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.16.txt
node /tmp/custom-codex-0716-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

Previous APKs are preserved under `artifacts/v0.7.15/`. Evidence is in
`artifacts/v0.7.16-android-build.txt`, `v0.7.16-icu-verification.json`,
`v0.7.16-signing-verification.json` and
`v0.7.16-local-update-verification.json`. Checks use local build outputs and an
ephemeral loopback host, without production credentials. No host restart is required.

## Unverified

No Android device is connected. The native interaction tests and installing and
reopening the affected chat on the user's phone remain unverified. ICU 70 runs
on this Linux machine; it is not a test of the exact ICU version on that phone.
