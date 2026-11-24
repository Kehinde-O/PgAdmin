package dev.pgapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pgapp.data.Csv
import dev.pgapp.data.QueryResult
import dev.pgapp.data.StatementOutcome

/** pgAdmin Query Tool: toolbar, SQL editor on top, Data Output / Messages below. */
@Composable
fun QueryScreen(tab: QueryTab, tick: Int, onEnableWrites: () -> Unit, modifier: Modifier = Modifier) {
    val writesOn = tick.let { tab.writesEnabled }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var exporting by remember { mutableStateOf<QueryResult?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    var resultTab by remember { mutableIntStateOf(0) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val data = exporting
        if (uri != null && data != null) {
            runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(Csv.write(data.columns, data.rows).toByteArray(Charsets.UTF_8)) }
            }
        }
        exporting = null
    }

    val rowsOutcomes = tab.result?.outcomes?.filterIsInstance<StatementOutcome.Rows>().orEmpty()
    LaunchedEffect(tab.error) { if (tab.error != null) resultTab = 1 }
    LaunchedEffect(tab.result) { if (tab.result != null) resultTab = if (rowsOutcomes.isNotEmpty()) 0 else 1 }
    val current = rowsOutcomes.getOrNull(tab.activeResult)?.result

    Column(modifier.fillMaxSize()) {
        // Toolbar
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton({ tab.run(scope) }, enabled = !tab.running, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Run")
                }
                IconButton({ tab.cancel(scope) }, enabled = tab.running) { Icon(Icons.Filled.Stop, "Cancel query") }
                TextButton({ tab.explain(scope, false) }, enabled = !tab.running) { Text("Explain") }
                TextButton({ tab.explain(scope, true) }, enabled = !tab.running) { Text("Analyze") }
                Box {
                    IconButton({ historyOpen = true }, enabled = tab.history.isNotEmpty()) { Icon(Icons.Filled.History, "History") }
                    DropdownMenu(historyOpen, { historyOpen = false }) {
                        tab.history.forEach { q ->
                            DropdownMenuItem(
                                text = { Text(q.replace('\n', ' '), maxLines = 2, overflow = TextOverflow.Ellipsis, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.widthIn(max = 360.dp)) },
                                onClick = { historyOpen = false; tab.text = TextFieldValue(q, TextRange(q.length)) },
                            )
                        }
                    }
                }
                IconButton({ tab.text = TextFieldValue("") }) { Icon(Icons.Filled.DeleteSweep, "Clear editor") }
                IconButton({ current?.let { exporting = it; export.launch("query-result.csv") } }, enabled = current != null) { Icon(Icons.Filled.Download, "Export CSV") }
                IconButton({ current?.let { clipboard.setText(AnnotatedString(Csv.write(it.columns, it.rows).replace(",", "\t"))) } }, enabled = current != null) { Icon(Icons.Filled.ContentCopy, "Copy results") }
                Spacer(Modifier.width(8.dp))
                if (tab.running) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                AssistChip(
                    onClick = { if (!writesOn) onEnableWrites() },
                    label = { Text(if (writesOn) "Writes on" else "Read-only") },
                    leadingIcon = { Icon(if (writesOn) Icons.Filled.LockOpen else Icons.Filled.Lock, null, Modifier.size(16.dp)) },
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SqlEditor(tab.text, { tab.text = it }, tab.names, Modifier.weight(0.45f).fillMaxWidth(), onRun = { tab.run(scope) })
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.outlineVariant)

        // Results
        Column(Modifier.weight(0.55f).fillMaxWidth()) {
            TabRow(resultTab, containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.height(40.dp)) {
                Tab(resultTab == 0, { resultTab = 0 }, text = { Text("Data Output") })
                Tab(resultTab == 1, { resultTab = 1 }, text = { Text(if (tab.error != null) "Messages •" else "Messages") })
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (resultTab == 0) {
                    if (current != null) {
                        Column {
                            if (rowsOutcomes.size > 1) {
                                Row(Modifier.horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    rowsOutcomes.indices.forEach { i ->
                                        FilterChip(tab.activeResult == i, { tab.activeResult = i }, label = { Text("Result ${i + 1}") })
                                    }
                                }
                            }
                            ResultGrid(current, Modifier.weight(1f), onCopy = { clipboard.setText(AnnotatedString(it)) })
                        }
                    } else {
                        Text(
                            if (tab.running) "Running..." else "Run a query to see results here.\nTip: Ctrl/Cmd+Enter runs; selected text runs alone.",
                            color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    MessagesView(tab)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                statusLine(tab, current),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

private fun statusLine(tab: QueryTab, current: QueryResult?): String {
    val r = tab.result ?: return if (tab.error != null) "Query failed" else "Ready"
    val rows = current?.let { "${it.rows.size} rows" + if (it.truncated) " (limited to ${it.rows.size})" else "" }
    return listOfNotNull(rows, "${r.elapsedMs} ms").joinToString(" · ")
}

@Composable
private fun MessagesView(tab: QueryTab) {
    SelectionContainer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tab.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontFamily = Mono, fontSize = 13.sp) }
            tab.result?.let { r ->
                r.outcomes.forEach { o ->
                    when (o) {
                        is StatementOutcome.Rows -> Text("SELECT ${o.result.rows.size}", fontFamily = Mono, fontSize = 13.sp)
                        is StatementOutcome.Update -> Text("Command completed, ${o.count} rows affected", fontFamily = Mono, fontSize = 13.sp)
                    }
                }
                r.notices.forEach { Text("NOTICE: $it", fontFamily = Mono, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary) }
                Text("Query returned successfully in ${r.elapsedMs} ms.", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
            }
            if (tab.error == null && tab.result == null) Text("No messages yet.", color = MaterialTheme.colorScheme.outline)
        }
    }
}
