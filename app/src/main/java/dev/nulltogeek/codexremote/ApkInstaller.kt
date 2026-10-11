package dev.nulltogeek.codexremote

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

fun verifyUpdateApk(context: Context, file: File, update: AppUpdate) {
    require(file.isFile && file.length() == update.size && sha256(file) == update.sha256) { "The downloaded update changed. Download it again." }
    val flags = PackageManager.GET_SIGNING_CERTIFICATES
    val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Android could not read this APK")
    val installed = context.packageManager.getPackageInfo(context.packageName, flags)
    require(archive.packageName == context.packageName && archive.packageName == update.applicationId) { "The APK belongs to a different app" }
    require(archive.longVersionCode == update.versionCode && archive.versionName == update.versionName && archive.longVersionCode >= installed.longVersionCode) { "The APK version does not match this update" }
    require(archive.applicationInfo?.minSdkVersion == update.minSdk && update.minSdk <= android.os.Build.VERSION.SDK_INT) { "The APK is incompatible with this Android version" }
    fun signers(info: android.content.pm.PackageInfo) = info.signingInfo?.apkContentsSigners?.map { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
    }?.toSet().orEmpty()
    val expected = signers(installed)
    require(expected.isNotEmpty() && signers(archive) == expected) { "The APK signing identity does not match the installed app" }
}
fun apkInstallerIntent(context: Context, file: File): Intent {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".apkprovider", file)
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}
