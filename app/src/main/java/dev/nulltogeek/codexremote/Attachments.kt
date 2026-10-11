package dev.nulltogeek.codexremote

import java.io.InputStream
import java.io.ByteArrayOutputStream

val Attachment.readyToSend: Boolean get() = !uploading && uploadError.isEmpty() && input.isNotEmpty()

// Returning from Android's picker can precede WebSocket reconnection. HTTP
// uploads use the retained login session and do not require a chat socket.
fun canAcceptPickedAttachments(state: ScreenState) = state.signedIn && state.selectedChat != null &&
    state.selectedChat.archived != true && !state.sending

fun attachmentStatus(file: Attachment): String = when {
    file.uploadError.isNotEmpty() -> "Upload failed"
    file.uploading && file.uploadProgress < 0 -> "Preparing…"
    file.uploading && file.uploadProgress >= 100 -> "Confirming upload…"
    file.uploading -> "Uploading ${file.uploadProgress}%"
    file.readyToSend -> "Uploaded"
    else -> "Not uploaded"
}

fun attachmentSummary(files: List<Attachment>): String {
    val uploaded = files.count { it.readyToSend }
    val uploading = files.count { it.uploading }
    val failed = files.count { it.uploadError.isNotEmpty() }
    return listOfNotNull(
        "$uploaded uploaded",
        if (uploading > 0) "$uploading uploading" else null,
        if (failed > 0) "$failed failed · remove and choose again" else null,
        if (files.isNotEmpty() && files.all { it.readyToSend }) "Ready to send" else null,
    ).joinToString(" · ")
}

// Uses Android 11 APIs and bounds memory even when a provider reports an incorrect file size.
fun readAttachmentBytes(input: InputStream, maximum: Int = 20 * 1024 * 1024): ByteArray {
    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= maximum) { "File is larger than 20 MB" }
        output.write(buffer, 0, count)
    }
    require(output.size() > 0) { "Select a non-empty file" }
    return output.toByteArray()
}
