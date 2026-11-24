package dev.pgapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pgapp.data.NewColumn
import dev.pgapp.data.SqlBuilder
import kotlinx.coroutines.launch

@Composable
fun ConfirmDialog(
    title: String, message: String, confirmLabel: String = "Confirm", destructive: Boolean = false,
    onConfirm: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onConfirm, colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** Single text field prompt; [allowNull] adds a "Set NULL" action (used for cell editing). */
@Composable
fun PromptDialog(
    title: String, label: String, initial: String = "", confirmLabel: String = "OK", allowNull: Boolean = false,
    mono: Boolean = false, onConfirm: (String?) -> Unit, onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
                textStyle = if (mono) TextStyle(fontFamily = Mono, fontSize = 13.sp) else LocalTextStyle.current,
                singleLine = !mono && !allowNull, minLines = if (allowNull) 2 else 1,
            )
        },
        confirmButton = { TextButton({ onConfirm(text) }, enabled = text.isNotBlank() || allowNull) { Text(confirmLabel) } },
        dismissButton = {
            Row {
                if (allowNull) TextButton({ onConfirm(null) }) { Text("Set NULL") }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Shows generated/edited SQL before it runs (like pgAdmin's SQL pane). If the session is read-only
 * the user is offered an explicit "Enable writes" step first.
 */
@Composable
fun ExecSqlDialog(
    title: String,
    initialSql: String,
    writesEnabled: Boolean,
    onEnableWrites: () -> Unit,
    onRun: suspend (String) -> Result<String>,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    var sql by remember { mutableStateOf(initialSql) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!writesEnabled) {
                    Text(
                        "This server session is read-only, so this change would be rejected. Enable write mode to continue.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    sql, { sql = it }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 14,
                    textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp), label = { Text("SQL") },
                )
                message?.let {
                    Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (!writesEnabled) {
                Button(onEnableWrites) { Text("Enable writes") }
            } else {
                Button(
                    enabled = !running && sql.isNotBlank(),
                    onClick = {
                        running = true; message = "Running..."; failed = false
                        scope.launch {
                            onRun(sql).fold(
                                onSuccess = { message = it; running = false; onDone() },
                                onFailure = { message = it.message; failed = true; running = false },
                            )
                        }
                    },
                ) { Text("Run") }
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !running) { Text("Close") } },
    )
}

private data class ColumnDraft(val id: Int, val name: String = "", val type: String = "text", val notNull: Boolean = false, val pk: Boolean = false, val default: String = "")

private val commonTypes = listOf("text", "integer", "bigint", "serial", "bigserial", "boolean", "numeric", "timestamptz", "date", "uuid", "jsonb", "varchar(255)")

/** pgAdmin "Create > Table" with a columns editor. Produces SQL for review in [ExecSqlDialog]. */
@Composable
fun CreateTableDialog(schema: String, onGenerate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var nextId by remember { mutableIntStateOf(2) }
    var cols by remember { mutableStateOf(listOf(ColumnDraft(1, "id", "bigserial", pk = true))) }

    fun update(id: Int, f: (ColumnDraft) -> ColumnDraft) { cols = cols.map { if (it.id == id) f(it) else it } }
    val valid = name.isNotBlank() && cols.isNotEmpty() && cols.all { it.name.isNotBlank() && it.type.isNotBlank() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create table in $schema") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Table name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Columns", style = MaterialTheme.typography.titleSmall)
                cols.forEach { c ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(c.name, { v -> update(c.id) { it.copy(name = v) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(c.type, { v -> update(c.id) { it.copy(type = v) } }, label = { Text("Type") }, singleLine = true, modifier = Modifier.weight(1f))
                                IconButton({ cols = cols.filterNot { it.id == c.id } }) { Icon(Icons.Filled.Close, "Remove column") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(c.pk, { update(c.id) { it.copy(pk = !it.pk) } }, label = { Text("Primary key") })
                                FilterChip(c.notNull, { update(c.id) { it.copy(notNull = !it.notNull) } }, label = { Text("Not null") })
                                OutlinedTextField(c.default, { v -> update(c.id) { it.copy(default = v) } }, label = { Text("Default") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                TextButton({ cols = cols + ColumnDraft(nextId++); }) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("Add column")
                }
                Text("Types: " + commonTypes.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onGenerate(
                        SqlBuilder.createTable(
                            schema, name.trim(),
                            cols.map { NewColumn(it.name.trim(), it.type.trim(), it.notNull, it.pk, it.default.ifBlank { null }) },
                        ),
                    )
                },
            ) { Text("Review SQL") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AddColumnDialog(onGenerate: (NewColumn) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("text") }
    var notNull by remember { mutableStateOf(false) }
    var default by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add column") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(type, { type = it }, label = { Text("Data type") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(default, { default = it }, label = { Text("Default (SQL expression)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(notNull, { notNull = it }); Spacer(Modifier.width(8.dp)); Text("Not null")
                }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank() && type.isNotBlank(), onClick = {
                onGenerate(NewColumn(name.trim(), type.trim(), notNull, false, default.ifBlank { null }))
            }) { Text("Review SQL") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** Insert-row form: one field per column; blank fields are omitted so defaults apply. */
@Composable
fun InsertRowDialog(columns: List<Pair<String, String>>, onInsert: (Map<String, String?>) -> Unit, onDismiss: () -> Unit) {
    val values = remember { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert row") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                columns.forEach { (name, type) ->
                    OutlinedTextField(
                        values[name] ?: "", { values[name] = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("$name ($type)") }, placeholder = { Text("default / null") },
                    )
                }
            }
        },
        confirmButton = { Button({ onInsert(columns.associate { (n, _) -> n to values[n]?.takeIf { it.isNotEmpty() } }) }) { Text("Insert") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
