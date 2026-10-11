package dev.nulltogeek.codexremote

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

const val MAX_APK_BYTES = 128L * 1024 * 1024
val appUpdateChannel get() = if (BuildConfig.DEBUG) "debug" else "release"
data class AppUpdate(val applicationId: String, val versionCode: Long, val versionName: String,
    val sha256: String, val size: Long, val minSdk: Int, val notes: String) {
    val downloadPath get() = "/v1/app-update/apk/$sha256.apk"
}
fun parseAppUpdate(raw: JsonObject, applicationId: String, sdk: Int): AppUpdate {
    val update = AppUpdate(raw.text("applicationId"), raw["versionCode"]?.jsonPrimitive?.longOrNull ?: 0,
        raw.text("versionName"), raw.text("sha256"), raw["size"]?.jsonPrimitive?.longOrNull ?: 0,
        raw["minSdk"]?.jsonPrimitive?.intOrNull ?: 0, raw.text("notes"))
    require(update.applicationId == applicationId) { "This update belongs to a different app" }
    require(update.versionCode > 0 && update.versionName.length in 1..80 && update.notes.length <= 8000 &&
        Regex("[a-f0-9]{64}").matches(update.sha256) && update.size in 1..MAX_APK_BYTES && update.minSdk >= 30) { "Invalid app update information" }
    require(update.minSdk <= sdk) { "This update requires a newer Android version" }
    return update
}
fun sha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    digest.digest().joinToString("") { "%02x".format(it) }
}
suspend fun receiveAppUpdate(input: InputStream, directory: File, update: AppUpdate, progress: (Int) -> Unit): File {
    require(update.size in 1..MAX_APK_BYTES && Regex("[a-f0-9]{64}").matches(update.sha256)) { "Invalid update download" }
    check(directory.mkdirs() || directory.isDirectory) { "Cannot create the update cache" }
    val partial = File(directory, update.sha256 + ".part")
    val target = File(directory, update.sha256 + ".apk")
    val digest = MessageDigest.getInstance("SHA-256"); var total = 0L; var lastProgress = -1
    try {
        partial.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer); if (count < 0) break
                total += count; require(total <= update.size) { "Update download was larger than expected" }
                output.write(buffer, 0, count); digest.update(buffer, 0, count)
                val percent = (100 * total / update.size).toInt()
                if (percent != lastProgress) { lastProgress = percent; progress(percent) }
            }
        }
        require(total == update.size) { "Update download was incomplete. Try again." }
        require(digest.digest().joinToString("") { "%02x".format(it) } == update.sha256) { "Update checksum did not match. Check for updates and retry." }
        check(partial.renameTo(target)) { "Cannot save the verified update" }
        return target
    } finally { if (partial.exists()) partial.delete() }
}
