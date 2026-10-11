# Visible attachments and picker recovery (0.7.2 / code 10)

Android's file/image picker pauses the app. That closes the chat WebSocket until
the app resumes. Previously, a picker result arriving before reconnection hit a
`connection != Connected` check and was silently discarded. The selected image
or file never appeared. Attachments upload through authenticated HTTP, so they
can use the retained session while the chat socket reconnects.

Version 0.7.2 accepts picker results during this reconnect. The composer displays
an attachment strip with image thumbnails, file names and explicit states:

- **Preparing…** while reading/compressing the selected file.
- **Uploading N%** with a progress bar during the request-body transfer.
- **Confirming upload…** after the bytes are written, until the server acknowledges.
- **Uploaded** with a check mark after the server returns the attachment.
- **Upload failed** with the error and retained thumbnail if upload fails.

The strip also reports the uploaded/uploading/failed counts and **Ready to send**
when all remaining files are uploaded. The X cancels an upload or removes an
attachment. Failed files remain visible and are saved with the draft; remove the
failed tile and choose the file again to retry. Send stays disabled until every
remaining attachment has a confirmed native input from the server.

Upload progress reports bytes written to the request body; only **Uploaded**
means the server acknowledged it. Uploading an attachment does not send a chat
message. The 20 MB/file and eight-attachment limits are unchanged.

Update through **Settings → App updates**. This is an APK-only change; the
deployed backend and Nginx require no reload and existing login sessions remain
valid. The signing/package identities remain compatible with prior versions.

## Verification

Focused JVM checks reproduce the picker reconnect state, verify authenticated
HTTP upload after `RpcClient.close()`, check intermediate transfer progress and
exact transmitted bytes, distinguish transfer completion from acknowledgment,
and check persistent failures/readiness. Existing cancellation tests still cover
stopping an upload before it returns an attachment.

`node scripts/verify-attachments.mjs https://codex.geeksun.ir` checks acknowledged
text, PNG and 2 MB binary fixture uploads through the deployed proxy, their sizes,
names and native input types, and unauthorized/browser-Origin rejection. It sends
no chat prompts. Diagnostic fixtures are retained in the private upload directory.

The Android preview/cancel check is updated to include progress and completion.
A second check verifies retained failure thumbnails and Send locking. Native UI
execution and physical-phone behavior remain unverified because the available
software emulator's Android framework could not reliably install the prior
release. See [the release verification record](mobile-0.7.2-verification.md) for
the executed build/test results and the later current-password verification limit.
