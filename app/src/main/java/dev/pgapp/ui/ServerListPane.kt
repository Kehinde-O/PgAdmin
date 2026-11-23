package dev.pgapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pgapp.data.ConnectionProfile
import kotlinx.coroutines.launch

/**
 * Left-hand server list (M2 will turn this into the full object browser tree).
 * [authenticate] must return true only after a successful biometric/credential prompt;
 * it gates every operation that touches the encrypted password store.
 */
@Composable
fun ServerListPane(
    vm: ServersViewModel,
    authenticate: suspend (String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val profiles by vm.profiles.collectAsState()
    val status by vm.status.collectAsState()
    val connected by vm.connected.collectAsState()
    val scope = rememberCoroutineScope()

    var dialogOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ConnectionProfile?>(null) }

    Column(modifier.padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Servers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton({ editing = null; dialogOpen = true }) { Icon(Icons.Filled.Add, "Register server") }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }
        if (profiles.isEmpty()) {
            Text("No servers registered yet.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
        }
        LazyColumn {
            items(profiles, key = { it.id }) { p ->
                val isConnected = connected?.profile?.id == p.id
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text("${p.user}@${p.host}:${p.port}/${p.database} · ${p.sslMode.value}") },
                    colors = ListItemDefaults.colors(
                        containerColor = if (isConnected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                    ),
                    trailingContent = {
                        Row {
                            IconButton({
                                if (isConnected) vm.disconnect() else scope.launch {
                                    if (authenticate("Unlock saved password")) {
                                        vm.connect(p, vm.storedPassword(p.id).orEmpty())
                                    }
                                }
                            }) { Icon(Icons.Filled.PowerSettingsNew, if (isConnected) "Disconnect" else "Connect") }
                            IconButton({ editing = p; dialogOpen = true }) { Icon(Icons.Filled.Edit, "Edit") }
                            IconButton({ vm.delete(p.id) }) { Icon(Icons.Filled.Delete, "Delete") }
                        }
                    },
                )
            }
        }
    }

    if (dialogOpen) {
        val initial = editing
        ServerDialog(
            initial = initial,
            onDismiss = { dialogOpen = false },
            onSave = { profile, pw ->
                scope.launch {
                    // Saving a new password encrypts with the auth-gated key, so authenticate first.
                    if (pw == null || authenticate("Protect saved password")) {
                        vm.save(profile, pw)
                        dialogOpen = false
                    }
                }
            },
            onTest = { profile, typed ->
                val pw = typed.ifEmpty {
                    if (initial != null && authenticate("Unlock saved password")) vm.storedPassword(initial.id) else null
                }
                if (pw == null) "No password available" else vm.test(profile, pw)
            },
        )
    }
}
