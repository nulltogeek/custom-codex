package dev.nulltogeek.codexremote

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CommandActivity(state: ScreenState) {
    var opened by remember(state.selectedChat?.id) { mutableStateOf(false) }
    val active = state.selectedChat?.status == "active" || state.activeTurnId.isNotEmpty() || state.sending
    val opacity = if (active && state.motion) {
        val animation = rememberInfiniteTransition(label = "Working heartbeat")
        val pulse by animation.animateFloat(0.4f, 1f,
            infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "Heartbeat opacity")
        pulse
    } else 1f
    IconButton(onClick = { opened = true }, modifier = Modifier.testTag("work-heartbeat")) {
        Icon(Icons.Outlined.Favorite, if (active) "Working: show command activity" else "Show command activity",
            Modifier.size(20.dp).alpha(opacity), tint = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (opened) ModalBottomSheet(onDismissRequest = { opened = false }) {
        val commands = state.timeline.filter { it.type == "commandExecution" }
        val (running, finished) = commands.partition { commandStatus(it) == "Running" }
        Column(Modifier.fillMaxWidth().testTag("activity-sheet").padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Command activity", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { opened = false }) { Icon(Icons.Outlined.Close, "Close command activity") }
            }
            Text("${running.size} running · ${finished.size} finished", style = MaterialTheme.typography.bodySmall)
            Text("Showing commands from loaded chat history.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (commands.isEmpty()) item { Text(if (active) "Waiting for the next command." else "No commands in this history.", Modifier.padding(16.dp)) }
                if (running.isNotEmpty()) item { Text("Running", style = MaterialTheme.typography.titleSmall) }
                items(running.asReversed(), key = { it.id }) { command -> MessageItem(command, {}, { error("No image") }) }
                if (finished.isNotEmpty()) item { Text("Finished commands", style = MaterialTheme.typography.titleSmall) }
                items(finished.asReversed(), key = { it.id }) { command -> MessageItem(command, {}, { error("No image") }) }
            }
        }
    }
}
