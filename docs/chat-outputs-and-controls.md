# Chat outputs and controls — 0.10.1

Codex replies now show cards for Markdown links to local files and images,
including absolute paths, relative paths, `sandbox:/` and `file:///` links.
Native completed `imageGeneration` items remain visible and use their saved
image path or inline image result. Fenced code examples do not become cards.
Images show a thumbnail and tap-to-open preview; file cards provide **Open
file** and **Save**. Save uses Android's document picker, and Open uses an
installed viewer. Missing files show an error and Retry.

Local downloads require a current session and an exact source reference in
the selected assistant item. The server obtains that item from the user's
Codex runtime; paths supplied by the phone alone are insufficient. Regular
users read inside their own container, limited to their workspace, temporary
files and Codex artifacts. Symlinks and files over 8 MB are rejected. Public
HTTPS image requests carry no server session and do not follow redirects.
Up to two chat downloads run at once. Files are cached privately by the app;
only an explicit Open grants a viewer temporary read access.

Send checks live thread metadata and starts the turn without downloading
history. A cold chat resumes first; old servers retain a loaded-list fallback.
Background queue checks no longer hold the manual-input mutex. An ambiguous
send still enters the existing uncertain-delivery flow instead of retrying.

Stop uses the live turn ID when available, with compact turn metadata and a
legacy fallback otherwise. It remains enabled during unrelated loading,
shows **Stopping**, suppresses duplicate taps, and persists queue pause off
the UI thread. This removes avoidable control delays; provider generation
speed and network latency remain separate.

## Verification

The focused Android unit tests cover Markdown parsing, native image visibility,
manual send request count, archived/active chat rejection, unloaded-chat
resume, uncertain send, and compact/legacy Stop paths. The simulated latency
test deliberately assigns history a 3-second delay; its 3400 ms → 200 ms
result is a fixture assertion, not a phone benchmark.

Android emulator checks cover actual ViewModel Send while history and another
chat's queue preflight are blocked, Stop during unrelated loading, immediate
Stopping feedback and duplicate suppression, authenticated image downloads,
image preview, native image/file cards, Retry, redirect/size rejection, and
existing uploaded-image previews.

The disposable HTTP fixture exercises real Codex and Docker locally and on
192.168.1.7. It downloads image/text bytes from an assistant reply, rejects
another user's request and unreferenced paths, and starts then interrupts a
held synthetic provider response. No paid model calls or production user
changes are needed. Measured server-local metadata/Send/Stop acknowledgements
were 19/54/34 ms in the remote fixture; these are not phone-to-server timings.

The final local build passed all 87 backend tests, 267 Android unit tests,
and lint with no issues, and produced signed debug/release APKs. Seven focused
emulator scenarios passed across the final artifact/control checks and the
existing image-preview checks.

Verification logs are under ignored `artifacts/verification/turn-controls/`.
Use `./scripts/build-local.sh` for full backend tests, Android unit tests,
lint and signed APK builds. APK publication and backend activation are
separate operations; production must be idle before a backend restart.
