package dev.nulltogeek.codexremote

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private val bytes = "APK download fixture".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun update() = AppUpdate("test.app", 4, "0.4.0", hash, bytes.size.toLong(), 30, "Update notes")
    private fun raw() = WireJson.parseToJsonElement("""{"applicationId":"test.app","versionCode":4,"versionName":"0.4.0","sha256":"$hash","size":${bytes.size},"minSdk":30,"notes":"Update notes","url":"https://untrusted.test/file.apk"}""").jsonObject

    @Test fun updateMetadataKeepsDownloadsOnTheHostAndRejectsIncompatiblePackages() {
        assertEquals("/v1/app-update/apk/$hash.apk", parseAppUpdate(raw(), "test.app", 30).downloadPath)
        assertTrue(runCatching { parseAppUpdate(raw(), "other.app", 30) }.isFailure)
        assertTrue(runCatching { parseAppUpdate(JsonObject(raw() + ("minSdk" to JsonPrimitive(36))), "test.app", 35) }.isFailure)
        assertTrue(runCatching { parseAppUpdate(JsonObject(raw() + ("sha256" to JsonPrimitive("../../file"))), "test.app", 35) }.isFailure)
        assertTrue(runCatching { parseAppUpdate(JsonObject(raw() + ("size" to JsonPrimitive(MAX_APK_BYTES + 1))), "test.app", 35) }.isFailure)
    }
    @Test fun completeDownloadBecomesInstallableOnlyAfterChecksumVerification() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("apk-update").toFile(); val progress = mutableListOf<Int>()
        val result = receiveAppUpdate(ByteArrayInputStream(bytes), directory, update(), progress::add)
        assertArrayEquals(bytes, result.readBytes()); assertEquals(hash, sha256(result)); assertEquals(100, progress.last())
        assertFalse(File(directory, "$hash.part").exists())
    }
    @Test fun interruptedOversizedAndAlteredDownloadsNeverBecomeInstallable() = runBlocking {
        for (content in listOf(bytes.dropLast(1).toByteArray(), bytes + byteArrayOf(1), ByteArray(bytes.size))) {
            val directory = java.nio.file.Files.createTempDirectory("bad-apk-update").toFile()
            assertTrue(runCatching { receiveAppUpdate(ByteArrayInputStream(content), directory, update()) {} }.isFailure)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }
}
