package dev.pgapp.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/** pgAdmin "View/Edit Data": paged grid with inline cell editing, insert and delete (tables with a primary key). */
@Composable
fun DataScreen(tab: DataTab, tick: Int, onEnableWrites: () -> Unit, modifier: Modifier = Modifier) {
    val writesOn = tick.let { tab.writesEnabled }
    val editable = writesOn && tab.isTable && tab.pk.isNotEmpty()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var editing by remember { mutableStateOf(false) }
    var inserting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val result = tab.result
    val row = tab.selRow
    val col = tab.selCol

    Column(modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ tab.reload(scope) }) { Icon(Icons.Filled.Refresh, "Reload") }
                IconButton({ tab.page(scope, -1) }, enabled = tab.offset > 0) { Icon(Icons.Filled.ChevronLeft, "Previous page") }
                Text("Rows ${tab.offset + 1}–${tab.offset + (result?.rows?.size ?: 0)}", style = MaterialTheme.typography.labelMedium)
                IconButton({ tab.page(scope, 1) }, enabled = tab.hasNext) { Icon(Icons.Filled.ChevronRight, "Next page") }
                VerticalSpacer()
                IconButton({ inserting = true }, enabled = editable) { Icon(Icons.Filled.Add, "Add row") }
                IconButton({ editing = true }, enabled = editable && row != null && col != null) { Icon(Icons.Filled.Edit, "Edit cell") }
                IconButton({ deleting = true }, enabled = editable && row != null) { Icon(Icons.Filled.Delete, "Delete row") }
                IconButton({
                    if (result != null && row != null && col != null) clipboard.setText(AnnotatedString(result.rows[row][col] ?: ""))
                }, enabled = row != null && col != null) { Icon(Icons.Filled.ContentCopy, "Copy cell") }
                if (tab.loading) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp), strokeWidth = 2.dp)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val notice = when {
            !tab.isTable -> "Read-only view of ${tab.relation.kind.label.lowercase()}"
            tab.columns.isNotEmpty() && tab.pk.isEmpty() -> "No primary key: rows cannot be edited safely"
            !writesOn -> "Session is read-only"
            else -> null
        }
        notice?.let {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (!writesOn && tab.isTable && tab.pk.isNotEmpty()) TextButton(onEnableWrites) { Text("Enable writes") }
                }
            }
        }
        tab.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        tab.info?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (result != null) {
                ResultGrid(
                    result, Modifier.fillMaxSize(), selectedRow = row, selectedCol = col,
                    onSelect = { r, c -> tab.selRow = r; tab.selCol = c },
                    onCopy = { clipboard.setText(AnnotatedString(it)) },
                )
                if (result.rows.isEmpty()) Text("No rows", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.outline)
            } else if (!tab.loading) {
                Text("No data", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.outline)
            }
        }
    }

    if (editing && result != null && row != null && col != null) {
        PromptDialog(
            title = "Edit ${result.columns[col]}", label = result.columnTypes.getOrElse(col) { "value" },
            initial = result.rows[row][col] ?: "", confirmLabel = "Update", allowNull = true, mono = true,
            onConfirm = { v -> editing = false; tab.updateCell(scope, row, col, v) },
            onDismiss = { editing = false },
        )
    }
    if (deleting && row != null) {
        ConfirmDialog(
            "Delete row ${row + 1 + tab.offset}?", "This permanently deletes the selected row from ${tab.relation.name}.",
            "Delete", destructive = true,
            onConfirm = { deleting = false; tab.deleteRow(scope, row) }, onDismiss = { deleting = false },
        )
    }
    if (inserting) {
        InsertRowDialog(tab.columns.map { it.name to it.type }, onInsert = { inserting = false; tab.insertRow(scope, it) }, onDismiss = { inserting = false })
    }
}

@Composable
private fun VerticalSpacer() = Spacer(Modifier.width(12.dp))
