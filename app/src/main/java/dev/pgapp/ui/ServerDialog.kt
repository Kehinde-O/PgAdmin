package dev.pgapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.pgapp.data.ConnectionProfile
import dev.pgapp.data.SslMode
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** pgAdmin "Register Server" style dialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDialog(
    initial: ConnectionProfile?,
    onDismiss: () -> Unit,
    onSave: (ConnectionProfile, String?) -> Unit,
    onTest: suspend (ConnectionProfile, String) -> String,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = remember { initial?.id ?: UUID.randomUUID().toString() }

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 5432).toString()) }
    var database by remember { mutableStateOf(initial?.database ?: "postgres") }
    var user by remember { mutableStateOf(initial?.user ?: "postgres") }
    var password by remember { mutableStateOf("") }
    var sslMode by remember { mutableStateOf(initial?.sslMode ?: SslMode.VERIFY_FULL) }
    var caPath by remember { mutableStateOf(initial?.caCertPath) }
    var allowWrites by remember { mutableStateOf(initial?.allowWrites ?: false) }
    var menuOpen by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    val pickCa = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val dest = File(ctx.filesDir, "certs/$id.crt").apply { parentFile?.mkdirs() }
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                caPath = dest.absolutePath
            }.onFailure { result = "Could not import certificate" }
        }
    }

    fun draft() = ConnectionProfile(
        id = id,
        name = name.trim(),
        host = host.trim(),
        port = port.toIntOrNull() ?: 0,
        database = database.trim(),
        user = user.trim(),
        sslMode = sslMode,
        caCertPath = caPath,
        allowWrites = allowWrites,
    )

    val problems = draft().validate()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Register Server" else "Edit Server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it }, label = { Text("Host name/address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(database, { database = it }, label = { Text("Maintenance database") }, singleLine = true, modifier = Modifier.weight(2f))
                }
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it },
                    label = { Text(if (initial == null) "Password" else "Password (blank = keep saved)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                ExposedDropdownMenuBox(menuOpen, { menuOpen = it }) {
                    OutlinedTextField(
                        sslMode.value, {}, readOnly = true, label = { Text("SSL mode") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(menuOpen) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(menuOpen, { menuOpen = false }) {
                        SslMode.entries.forEach { m ->
                            DropdownMenuItem(text = { Text(m.value) }, onClick = { sslMode = m; menuOpen = false })
                        }
                    }
                }
                if (!sslMode.verifiesServer) {
                    Text(
                        if (sslMode == SslMode.DISABLE) "Insecure: traffic and password are not encrypted."
                        else "Insecure: encrypted, but the server identity is NOT verified.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ pickCa.launch(arrayOf("*/*")) }) { Text("CA certificate") }
                    Text(caPath?.let { "Imported" } ?: "System CAs", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(allowWrites, { allowWrites = it })
                    Spacer(Modifier.width(8.dp))
                    Text(if (allowWrites) "Writes allowed" else "Read-only session (recommended)", style = MaterialTheme.typography.bodyMedium)
                }
                problems.firstOrNull()?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        testing = true; result = "Testing..."
                        scope.launch { result = onTest(draft(), password); testing = false }
                    },
                    enabled = problems.isEmpty() && !testing,
                ) { Text("Test") }
                Button(
                    onClick = { onSave(draft(), password.ifEmpty { null }) },
                    enabled = problems.isEmpty() && (initial != null || password.isNotEmpty()),
                ) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
