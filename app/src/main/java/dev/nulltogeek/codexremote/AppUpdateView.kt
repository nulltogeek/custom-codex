package dev.nulltogeek.codexremote

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.*

@Composable fun AppUpdateSection(state: ScreenState, vm: RemoteViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope(); var openingInstaller by remember { mutableStateOf(false) }
    fun install() {
        val update = state.appUpdate ?: return; val path = state.updateApk ?: return
        if (openingInstaller) return
        openingInstaller = true
        scope.launch {
            try {
                val file = File(path)
                withContext(Dispatchers.IO) { verifyUpdateApk(context, file, update) }
                context.startActivity(apkInstallerIntent(context, file))
                vm.updateMessage("Confirm the update in Android’s installer. Your app data will be retained.")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                vm.updateMessage(e.message ?: "Could not open Android’s installer")
            } finally { openingInstaller = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) install()
        else vm.updateMessage("Allow updates from Codex Remote in Android settings, then tap Install update.")
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("APP UPDATES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Installed version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        if (state.updateMessage.isNotEmpty()) Text(state.updateMessage, style = MaterialTheme.typography.bodyMedium)
        state.appUpdate?.let { update ->
            Text(update.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.updateDownloading) {
                LinearProgressIndicator(progress = { state.updateProgress / 100f }, modifier = Modifier.fillMaxWidth())
                Text("${state.updateProgress}% · Keep the app open during download", style = MaterialTheme.typography.labelSmall)
            } else if (state.updateApk != null) {
                Button(onClick = {
                    if (context.packageManager.canRequestPackageInstalls()) install()
                    else {
                        vm.updateMessage("Allow Codex Remote to install updates on the next Android settings screen.")
                        try { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())) }
                        catch (_: Exception) { vm.updateMessage("Open Android settings and allow updates from Codex Remote, then try again.") }
                    }
                }, enabled = !openingInstaller) { Text("Install update") }
            } else {
                Button(onClick = vm::downloadAppUpdate, enabled = !state.updateChecking) { Text("Download ${update.versionName} · ${"%.1f".format(update.size / 1048576.0)} MB") }
            }
        }
        OutlinedButton(onClick = { vm.checkAppUpdate() }, enabled = !state.updateChecking && !state.updateDownloading && !openingInstaller) {
            Text(if (state.updateChecking) "Checking…" else "Check for updates")
        }
    }
}
