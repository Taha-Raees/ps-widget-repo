package app.pocketshell.widget.network

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketshell.ui.home.HomeTokens
import app.pocketshell.ui.theme.TerminalTheme

/** A labelled single-line field in the app's mono type. */
@Composable
internal fun Field(label: String, value: String, onChange: (String) -> Unit, number: Boolean = false, hint: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontFamily = TerminalTheme.mono, fontSize = 11.sp) },
        placeholder = hint?.let { { Text(it, fontFamily = TerminalTheme.mono, fontSize = 12.sp, color = HomeTokens.textDim) } },
        singleLine = true,
        textStyle = TextStyle(fontFamily = TerminalTheme.mono, fontSize = 13.sp, color = HomeTokens.textPrimary),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
private fun ErrorLine(error: String?) {
    if (error != null) MonoText(text = error, color = HomeTokens.danger, maxLines = 3, modifier = Modifier.padding(top = 8.dp))
}

/** Add or edit a host. */
@Composable
internal fun HostFormDialog(initial: SshHost?, keys: List<KeyInfo>, onSave: (SshHost, (String?) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var alias by remember { mutableStateOf(initial?.alias.orEmpty()) }
    var host by remember { mutableStateOf(initial?.hostName.orEmpty()) }
    var user by remember { mutableStateOf(initial?.user.orEmpty()) }
    var port by remember { mutableStateOf(initial?.port?.toString().orEmpty()) }
    var key by remember { mutableStateOf(initial?.identityFile.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add host" else "Edit ${initial.alias}") },
        text = {
            Column {
                Field("Name", alias, { alias = it }, hint = "pi")
                Field("Host or address", host, { host = it }, hint = "192.168.1.20")
                Field("User", user, { user = it }, hint = "pi")
                Field("Port", port, { port = it.filter(Char::isDigit).take(5) }, number = true, hint = "22")
                Field("Key file", key, { key = it }, hint = "~/.ssh/id_ed25519")
                if (keys.isNotEmpty()) {
                    Row(Modifier.padding(top = 6.dp)) {
                        keys.take(4).forEach { k -> ActionText(k.name) { key = "~/.ssh/${k.name}" } }
                    }
                }
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val h = SshHost(
                    alias = alias.trim(),
                    hostName = host.trim().ifEmpty { null },
                    user = user.trim().ifEmpty { null },
                    port = port.toIntOrNull(),
                    identityFile = key.trim().ifEmpty { null },
                    proxyJump = initial?.proxyJump,
                    extra = initial?.extra.orEmpty(),
                )
                val problem = SshConfig.problem(h)
                if (problem != null) error = problem else onSave(h) { err -> if (err == null) onDismiss() else error = err }
            }) { Text("Save", color = HomeTokens.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = HomeTokens.textDim) } },
    )
}

/** Add or edit a tunnel. */
@Composable
internal fun TunnelFormDialog(initial: Tunnel?, hostAliases: List<String>, onSave: (Tunnel, (String?) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: TunnelKind.LOCAL) }
    var via by remember { mutableStateOf(initial?.via.orEmpty()) }
    var bind by remember { mutableStateOf(initial?.bindPort?.toString().orEmpty()) }
    var destHost by remember { mutableStateOf(initial?.destHost ?: "localhost") }
    var destPort by remember { mutableStateOf(initial?.destPort?.takeIf { it > 0 }?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add tunnel" else "Edit tunnel") },
        text = {
            Column {
                Field("Name", name, { name = it.take(40) }, hint = "Postgres")
                Choices(listOf("Local" to TunnelKind.LOCAL, "Remote" to TunnelKind.REMOTE, "SOCKS" to TunnelKind.DYNAMIC), kind) { kind = it }
                Field("Via (host from your list, or user@host)", via, { via = it }, hint = "prod")
                if (hostAliases.isNotEmpty()) {
                    Row(Modifier.padding(top = 4.dp)) { hostAliases.take(4).forEach { a -> ActionText(a) { via = a } } }
                }
                Field(if (kind == TunnelKind.REMOTE) "Port on the remote host" else "Port on this phone", bind, { bind = it.filter(Char::isDigit).take(5) }, number = true, hint = "5432")
                if (kind != TunnelKind.DYNAMIC) {
                    Field(if (kind == TunnelKind.REMOTE) "Goes to (on this phone)" else "Goes to (seen from the host)", destHost, { destHost = it }, hint = "localhost")
                    Field("Destination port", destPort, { destPort = it.filter(Char::isDigit).take(5) }, number = true, hint = "5432")
                }
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = Tunnel(
                    id = initial?.id ?: Tunnels.newId(),
                    name = name.trim(),
                    kind = kind,
                    via = via.trim(),
                    bindPort = bind.toIntOrNull() ?: 0,
                    destHost = if (kind == TunnelKind.DYNAMIC) "localhost" else destHost.trim(),
                    destPort = if (kind == TunnelKind.DYNAMIC) 0 else destPort.toIntOrNull() ?: 0,
                )
                val problem = Tunnels.problem(t)
                if (problem != null) error = problem else onSave(t) { err -> if (err == null) onDismiss() else error = err }
            }) { Text("Save", color = HomeTokens.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = HomeTokens.textDim) } },
    )
}

/** Create a key. */
@Composable
internal fun KeyFormDialog(existing: Set<String>, defaultComment: String, onCreate: (String, String, (String?) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(if ("id_ed25519" in existing) "" else "id_ed25519") }
    var comment by remember { mutableStateOf(defaultComment) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create an SSH key") },
        text = {
            Column {
                MonoText(text = "ED25519, no passphrase. Anyone who can read this phone's app storage can use it — add a passphrase later with ssh-keygen -p if that matters.", color = HomeTokens.textDim, maxLines = 5)
                Field("File name", name, { name = it }, hint = "id_ed25519")
                Field("Comment", comment, { comment = it }, hint = "me@phone")
                ErrorLine(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.trim() in existing) error = "A key named \"${name.trim()}\" already exists"
                else onCreate(name.trim(), comment.trim()) { err -> if (err == null) onDismiss() else error = err }
            }) { Text("Create", color = HomeTokens.accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = HomeTokens.textDim) } },
    )
}

/** A destructive confirmation; with [typeToConfirm] the user must type that word. */
@Composable
internal fun ConfirmDialog(title: String, message: String, confirmLabel: String, typeToConfirm: String? = null, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val ready = typeToConfirm == null || typed.trim() == typeToConfirm
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message, fontSize = 13.sp)
                if (typeToConfirm != null) Field("Type \"$typeToConfirm\" to confirm", typed, { typed = it })
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (ready) HomeTokens.danger else HomeTokens.textDim)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = HomeTokens.textDim) } },
    )
}

/** First contact with a host: show the fingerprints and let the user decide. */
@Composable
internal fun TrustDialog(request: TrustRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trust ${request.host}?") },
        text = {
            Column {
                Text("These are the fingerprints ${request.host} presented. Compare them with a trusted source before you accept.", fontSize = 13.sp)
                request.prints.forEach { MonoText(text = it, color = HomeTokens.textPrimary, maxLines = 3, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Trust", color = HomeTokens.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = HomeTokens.textDim) } },
    )
}
