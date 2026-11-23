package dev.nulltogeek.codexremote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Passwords intentionally use remember, never rememberSaveable or persistent storage. */
@Composable fun AccountSignIn(state: ScreenState, vm: RemoteViewModel) {
    var endpoint by rememberSaveable { mutableStateOf(state.endpoint) }
    var username by rememberSaveable { mutableStateOf(state.username) }
    var signup by rememberSaveable { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val enabled = !state.loading
    val ready = endpoint.isNotBlank() && if (signup) validSignup(username, password, confirmation)
        else username.isNotBlank() && password.isNotEmpty()
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(20.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.padding(18.dp).size(36.dp))
        }
        Text(if (signup) "Create your account" else "Welcome back", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text(if (signup) "Your own chats and projects, in one place." else "Sign in to continue your chats and projects.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth().testTag("account-endpoint"),
            label = { Text("Server URL") }, singleLine = true, enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth().testTag("account-username"),
            label = { Text("Username") }, singleLine = true, enabled = enabled,
            supportingText = if (signup) ({ Text("1–64 letters, numbers, dots, underscores or hyphens") }) else null)
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().testTag("account-password"),
            label = { Text("Password") }, singleLine = true, enabled = enabled,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = if (signup) ({ Text("At least 12 characters") }) else null)
        if (signup) OutlinedTextField(confirmation, { confirmation = it }, Modifier.fillMaxWidth().testTag("account-confirm-password"),
            label = { Text("Confirm password") }, singleLine = true, enabled = enabled,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = confirmation.isNotEmpty() && password != confirmation,
            supportingText = if (confirmation.isNotEmpty() && password != confirmation) ({ Text("Passwords must match") }) else null)
        Button(onClick = { if (signup) vm.signUp(endpoint, username, password) else vm.signIn(endpoint, username, password) },
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("account-submit"), enabled = enabled && ready) {
            if (state.loading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
            Text(if (signup) "Create account" else "Sign in")
        }
        if (state.registrationAvailable || signup) TextButton(onClick = {
            signup = !signup; password = ""; confirmation = ""; vm.noticeDismiss()
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("account-toggle-signup")) {
            Text(if (signup) "Already have an account? Sign in" else "New here? Create an account")
        }
        Text("Connect using your private server address. Your password is never saved on this device.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun ManageUsersScreen(state: ScreenState, vm: RemoteViewModel) {
    var create by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf<ManagedUser?>(null) }
    var disable by remember { mutableStateOf<ManagedUser?>(null) }
    var revoke by remember { mutableStateOf<ManagedUser?>(null) }
    var delete by remember { mutableStateOf<ManagedUser?>(null) }
    LaunchedEffect(state.accountId, state.userManagementAvailable) { if (state.userManagementAvailable) vm.refreshUsers() }
    if (!state.userManagementAvailable) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text("User management is available to the administrator.") }
        return
    }
    val enabled = state.connection == "Connected" && !state.userMutationPending
    LazyColumn(Modifier.fillMaxSize().testTag("manage-users"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Manage users", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("${state.adminUsers.size} users · ${state.adminUsers.count { it.disabled }} disabled",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = vm::refreshUsers, enabled = enabled && !state.usersLoading) { Icon(Icons.Outlined.Refresh, "Refresh users") }
            }
        }
        item {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Allow new registrations", fontWeight = FontWeight.Medium)
                        Text("People can create their own account with a password.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(state.signupEnabled, vm::setSignupEnabled, enabled = enabled && !state.usersLoading,
                        modifier = Modifier.testTag("allow-registration"))
                }
            }
        }
        item {
            Button(onClick = { vm.clearUserManagementMessage(); create = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("create-user")) {
                Icon(Icons.Outlined.PersonAdd, null); Spacer(Modifier.width(8.dp)); Text("Create user")
            }
        }
        if (state.usersLoading || state.userMutationPending) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.userManagementMessage.isNotBlank()) item {
            Text(state.userManagementMessage, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("user-management-message"))
        }
        items(state.adminUsers, key = { it.id }) { user ->
            ManagedUserRow(user, user.id == state.accountId, enabled,
                changeEnabled = { if (user.disabled) vm.updateUser(user, disabled = false) else disable = user },
                resetPassword = { vm.clearUserManagementMessage(); reset = user }, revoke = { revoke = user },
                canDelete = state.userDeletionAvailable && user.id != "owner" && user.role != "admin",
                delete = { delete = user })
        }
        if (state.adminUsers.isEmpty() && !state.usersLoading) item { Text("No users loaded. Refresh to try again.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (create) UserPasswordDialog(null, state.userMutationPending, state.userManagementMessage, { create = false }, { username, password ->
        vm.createUser(username, password) { create = false }
    })
    reset?.let { user -> UserPasswordDialog(user, state.userMutationPending, state.userManagementMessage, { reset = null }, { _, password ->
        vm.updateUser(user, password = password) { reset = null }
    }) }
    disable?.let { user -> AlertDialog(onDismissRequest = { disable = null },
        title = { Text("Disable ${user.username}?") }, text = { Text("They will be signed out and cannot sign in until you enable their account again. Their chats and projects are retained.") },
        confirmButton = { TextButton(onClick = { vm.updateUser(user, disabled = true); disable = null }, enabled = enabled) { Text("Disable user") } },
        dismissButton = { TextButton(onClick = { disable = null }) { Text("Cancel") } }) }
    revoke?.let { user -> AlertDialog(onDismissRequest = { revoke = null },
        title = { Text("Sign out ${user.username}?") }, text = { Text("All of this user's current device sessions will end. They can sign in again with their password.") },
        confirmButton = { TextButton(onClick = { vm.revokeUserSessions(user); revoke = null }, enabled = enabled) { Text("Sign out devices") } },
        dismissButton = { TextButton(onClick = { revoke = null }) { Text("Cancel") } }) }
    delete?.let { user -> AlertDialog(onDismissRequest = { delete = null },
        title = { Text("Delete ${user.username}?") },
        text = { Text("Permanently remove this account, sign out all its devices, and stop its running work. Saved server files are retained. Reusing this username creates a fresh account with no access to the old files.") },
        confirmButton = { TextButton(onClick = { vm.deleteUser(user); delete = null },
            enabled = enabled && state.userDeletionAvailable, modifier = Modifier.testTag("confirm-delete-user")) {
            Text("Delete user", color = MaterialTheme.colorScheme.error)
        } },
        dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
}

@Composable private fun ManagedUserRow(user: ManagedUser, self: Boolean, enabled: Boolean,
    changeEnabled: () -> Unit, resetPassword: () -> Unit, revoke: () -> Unit, canDelete: Boolean, delete: () -> Unit) {
    var menu by remember(user.id) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large, modifier = Modifier.testTag("managed-user:${user.id}")) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { Text(user.username.take(1).uppercase(), fontWeight = FontWeight.Medium) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(user.username + if (self) " (you)" else "", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(if (user.role == "admin") "Administrator" else "User", if (user.disabled) "Disabled" else "Enabled").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) { Icon(Icons.Outlined.MoreVert, "Manage ${user.username}") }
                DropdownMenu(menu, { menu = false }) {
                    if (!self) DropdownMenuItem(text = { Text(if (user.disabled) "Enable user" else "Disable user") },
                        onClick = { menu = false; changeEnabled() })
                    if (!self) DropdownMenuItem(text = { Text("Reset password") }, onClick = { menu = false; resetPassword() })
                    if (!self) DropdownMenuItem(text = { Text("Sign out devices") }, onClick = { menu = false; revoke() })
                    if (!self && canDelete) DropdownMenuItem(text = { Text("Delete user", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.testTag("delete-user:${user.id}"), onClick = { menu = false; delete() })
                    if (self) DropdownMenuItem(text = { Text("Change your password in Settings") }, onClick = { menu = false }, enabled = false)
                }
            }
        }
    }
}

@Composable private fun UserPasswordDialog(user: ManagedUser?, busy: Boolean, error: String, dismiss: () -> Unit, submit: (String, String) -> Unit) {
    var username by rememberSaveable(user?.id) { mutableStateOf(user?.username.orEmpty()) }
    var password by remember(user?.id) { mutableStateOf("") }
    var confirmation by remember(user?.id) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, modifier = Modifier.testTag("user-password-dialog"),
        title = { Text(if (user == null) "Create user" else "Reset password for ${user.username}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (user == null) OutlinedTextField(username, { username = it }, Modifier.testTag("managed-username"), label = { Text("Username") }, singleLine = true, enabled = !busy,
                    supportingText = { Text("Letters, numbers, dots, underscores or hyphens") })
                OutlinedTextField(password, { password = it }, Modifier.testTag("managed-password"), label = { Text("New password") }, singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = { Text("At least 12 characters") })
                OutlinedTextField(confirmation, { confirmation = it }, Modifier.testTag("managed-confirm-password"), label = { Text("Confirm password") }, singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = confirmation.isNotEmpty() && password != confirmation)
                if (user != null) Text("Existing sessions will be signed out. Give the new password to this user privately.", style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("user-password-error"))
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text(if (user == null) "Creating user…" else "Resetting password…")
                }
            }
        }, confirmButton = { TextButton(onClick = { submit(username, password) }, enabled = !busy && validSignup(username, password, confirmation),
            modifier = Modifier.testTag("submit-managed-user")) {
            Text(if (user == null) "Create user" else "Reset password")
        } }, dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("Cancel") } })
}
