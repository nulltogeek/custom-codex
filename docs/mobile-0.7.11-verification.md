# 0.7.11 verification — October 12, 2026

Version **0.7.11 / code 19** adds language-aware highlighting with separate
keyword, string, comment, number, function, type, property, variable and tag
styles, adaptive dark/light palettes and diff colors. Response typography uses
larger text and more line spacing, clearer headings, hanging list indentation,
task indicators, grouped quotes and aligned wrapping tables.

Enter, Numpad Enter and the software keyboard Send action use the same send
callback and readiness checks as the Send button. Shift+Enter inserts a new line;
pasted multiline text stays in the draft. Active work uses the existing queue
behavior. Repeat hardware key events do not trigger repeated sends.

Changes were committed and pushed separately:

- `6f0d32b`: language-aware syntax highlighting.
- `3258840`: response typography and Markdown layout.
- `cd8250e`: Enter-to-send and keyboard interaction checks.

See [response formatting](response-formatting.md) for behavior and supported
languages. Highlighting is lexical, with plain code for unsupported languages.

## Executed verification

- **120 JVM tests pass**, zero failures, errors or skips. New cases verify string
  and comment boundaries, URLs, Python multiline strings, JSON keys, CSS colors,
  shell variables, SQL/HTML rules, diff styles, language aliases and token ranges.
  Markdown cases verify nested/ordered/task lists, continuation lines, grouped
  quotes, inline code, escaped table pipes, empty cells and strikethrough.
- Debug/release APKs and the instrumentation APK build successfully. Android lint
  reports **No issues found**. Existing icon/clipboard deprecation warnings remain.
- Four new instrumentation tests compile for physical Enter/Shift+Enter,
  Numpad Enter, the software keyboard Send action, blocked sends and multiline
  insertion. They were not executed because no Android device is connected.
  The initial instrumentation compilation required the explicit experimental
  keyboard-test API opt-in; this was added and the final build passed.
- Both APK signatures verify and certificates match published 0.7.10. Publication
  validates the actual application IDs, version 0.7.11/code 19 and minimum SDK 30.
- Authenticated loopback downloads verify both update manifests and real APKs,
  content lengths, SHA-256 digests and delivery copies. Unauthenticated requests
  and browser-Origin requests return 401. Delivery checksums pass.
- `git diff --check` passes. Backend source was unchanged; its suite was not rerun
  for this Android rendering/input change.

```sh
./gradlew --offline --no-daemon -PcodexGoogleCdn=true \
  -Pkotlin.incremental=false -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleRelease :app:assembleDebugAndroidTest
python3 /tmp/custom-codex-0711-artifacts.py
node scripts/publish-app-update.mjs docs/release-notes/0.7.11.txt
node /tmp/custom-codex-0711-transport.mjs
sha256sum --check artifacts/SHA256SUMS
git diff --check
.downloads/android-sdk/platform-tools/adb devices
```

The artifact script compares signing certificates, preserves prior delivery APKs
under `artifacts/v0.7.10/` and updates checksums. The transport script creates an
ephemeral authenticated loopback host, checks actual published files and closes
it. Production credentials are not used. Evidence is saved in
`artifacts/v0.7.11-android-build.txt`, `v0.7.11-initial-build.txt`,
`v0.7.11-signing-verification.json` and `v0.7.11-local-update-verification.json`.

## Unverified

No Android device is connected. Actual response layout, font scaling, selection,
keyboard behavior across IMEs and hardware keyboards, and update discovery and
installation remain unverified on a phone. The software keyboard is requested
to show Send; its presentation depends on the installed keyboard.

These changes need no host restart. The previously reported uploaded-image route
still requires a safe host reload after active work; it was not reloaded here.
