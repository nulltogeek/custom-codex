# Local build verification — 2026-11-23

The source was imported from `nulltogeek@192.168.1.7` and built on this machine.

- Node.js 22.23.3; Temurin JDK 17.0.21.1; Gradle 8.11.1; Android SDK 35.
- Backend syntax checks passed; all 73 backend tests passed.
- All 249 Android JVM unit tests passed, with zero failures, errors, or skipped tests.
- Android debug lint: **No issues found.** Release vital lint passed.
- Debug and release APK assembly passed. Full Gradle run: 6m 23s.
- Both APK signatures verified, and both artifact checksums passed.
- APK metadata verified: 0.8.0/code 33; release package
  `dev.nulltogeek.codexremote`; minimum Android API 30; target API 35.
- Local update staging verified APK signatures and package/version metadata.
- Deployment helper shell syntax and remote dry-run preview passed. Production
  activation and rollback have not been exercised by this import.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `artifacts/codex-remote-release.apk` | 3260098 | `40bb569becac36db3fc42b3380d828a65a23d96b65c91a5be9464d24adf6d161` |
| `artifacts/codex-remote-debug.apk` | 61155011 | `b827d27f0ab928c757a9822bf9c9a3ae57f244608af211cbc33749fa6437158c` |

The build log is `artifacts/local-build.log`; backend results are in
`artifacts/backend-tests.log`. SDK remote-manifest lookup warnings and existing
Kotlin deprecation warnings did not prevent the build. Google's alternate Maven
CDN was selected for this local environment.

The final remote check returned `{"ok":true}`. The service remains active with
PID 2838950 and its original 2026-11-20 start timestamp. Its published APK remains
0.7.24/code 32. No production deployment or restart occurred.

The new local APK certificate is
`be39a12735de524caac88a05507203c677b549ac0a35aa04291bff6c7088aab9`.
The published 0.7.24 release has a different certificate, so these APKs cannot
replace it in place. Preserve/recover the old signing key for seamless upgrades,
or follow the existing 0.8.0 reinstall instructions. Physical phone installation
and emulator UI behavior were not tested during this environment migration.
