package dev.nulltogeek.codexremote

import java.io.InputStream
import java.io.ByteArrayOutputStream

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
