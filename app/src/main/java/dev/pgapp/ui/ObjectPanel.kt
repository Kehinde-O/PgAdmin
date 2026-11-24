package dev.pgapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pgapp.R
import dev.pgapp.data.*

/** Right-hand "Properties" workspace for whatever is selected in the tree (pgAdmin's main panel). */
@Composable
fun ObjectPanel(
    vm: AppViewModel,
    onMenu: (TreeNode, MenuAction) -> Unit,
    onSql: (title: String, sql: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected by vm.selected.collectAsState()
    val details by vm.details.collectAsState()
    val loading by vm.detailsLoading.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val tick by vm.tick.collectAsState()

    val node = selected
    if (node == null) {
        EmptyState("Select an object", "Choose a server, database or table in the browser to see its properties.", modifier)
        return
    }
    val session = sessions[node.profileId]
    if (session == null) {
        Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(node.label, style = MaterialTheme.typography.titleMedium)
            Text("Not connected", color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            Button({ onMenu(node, MenuAction.CONNECT) }) { Text("Connect") }
        }
        return
    }

    val d = details?.takeIf { it.node.key == node.key || (it.node.relation != null && it.node.relation == node.relation) }
    val rel = node.relation
    val isTable = rel != null && (rel.kind == RelKind.TABLE || rel.kind == RelKind.PARTITIONED)

    Column(modifier.fillMaxSize()) {
        Header(node, tick.let { session.writes }, loading, onMenu, vm)
        if (loading && d == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        d?.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (d == null || d.error != null) return@Column

        val tabs = buildList {
            if (d.dashboard != null) add("Dashboard")
            if (d.props.isNotEmpty()) add("Properties")
            if (d.columns.isNotEmpty() || isTable) add("Columns")
            if (d.constraints.isNotEmpty()) add("Constraints")
            if (d.indexes.isNotEmpty()) add("Indexes")
            if (d.stats.isNotEmpty()) add("Statistics")
            if (d.ddl != null) add("SQL")
        }
        if (tabs.isEmpty()) {
            EmptyState(node.label, "Nothing to show for this item.", Modifier.fillMaxSize())
            return@Column
        }
        var tab by remember(node.key) { mutableIntStateOf(0) }
        val index = tab.coerceIn(0, tabs.lastIndex)
        ScrollableTabRow(index, edgePadding = 8.dp, containerColor = MaterialTheme.colorScheme.surface) {
            tabs.forEachIndexed { i, t -> Tab(index == i, { tab = i }, text = { Text(t) }) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
            when (tabs[index]) {
                "Dashboard" -> DashboardView(d.dashboard!!, vm)
                "Properties" -> KeyValueList(d.props)
                "Columns" -> ColumnsView(d, isTable, onSql)
                "Constraints" -> ListView(d.constraints.map { Triple(it.name, it.typeLabel, it.definition) }) { name ->
                    if (isTable && rel != null) onSql("Drop constraint", SqlBuilder.dropConstraint(rel.schema, rel.name, name))
                }
                "Indexes" -> ListView(d.indexes.map { Triple(it.name, if (it.primary) "Primary" else if (it.unique) "Unique" else "Index", it.definition) }) { name ->
                    if (rel != null) onSql("Drop index", SqlBuilder.dropIndex(rel.schema, name))
                }
                "Statistics" -> KeyValueList(d.stats)
                "SQL" -> SqlView(d.ddl.orEmpty(), onOpen = { sql -> onSql("Edit SQL", sql) })
            }
        }
    }
}

@Composable
private fun Header(node: TreeNode, writes: Boolean, loading: Boolean, onMenu: (TreeNode, MenuAction) -> Unit, vm: AppViewModel) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(node.kind), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(node.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    listOfNotNull(node.kind.name.lowercase().replaceFirstChar { it.uppercase() }, node.schema, node.db).distinct().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1,
                )
            }
            FilterChip(
                selected = writes,
                onClick = { onMenu(node, MenuAction.TOGGLE_WRITES) },
                label = { Text(if (writes) "Writes on" else "Read-only") },
                leadingIcon = { Icon(if (writes) Icons.Filled.LockOpen else Icons.Filled.Lock, null, Modifier.size(16.dp)) },
            )
            if (loading) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp), strokeWidth = 2.dp)
            IconButton({ vm.reloadDetails() }) { Icon(Icons.Filled.Refresh, "Reload") }
        }
    }
}

@Composable
fun EmptyState(title: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.ic_logo), null, Modifier.size(72.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun KeyValueList(items: List<Pair<String, String>>) {
    SelectionContainer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                items.forEachIndexed { i, (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(k, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(150.dp))
                        Text(v.ifEmpty { "—" }, modifier = Modifier.weight(1f))
                    }
                    if (i < items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ColumnsView(d: Details, isTable: Boolean, onSql: (String, String) -> Unit) {
    val rel = d.node.relation
    var menuFor by remember { mutableStateOf<String?>(null) }
    var prompt by remember { mutableStateOf<Pair<ColumnInfo, String>?>(null) } // column to action
    var adding by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isTable && rel != null) {
            Button({ adding = true }, Modifier.align(Alignment.End)) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add column") }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            d.columns.forEachIndexed { i, c ->
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${c.position}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (c.isPk) { Icon(Icons.Filled.Key, null, Modifier.size(14.dp), tint = Color(0xFFD08A1E)); Spacer(Modifier.width(4.dp)) }
                            Text(c.name, fontWeight = FontWeight.Medium)
                        }
                        Text(
                            listOfNotNull(c.type, if (c.notNull) "not null" else null, c.default?.let { "default $it" }).joinToString(" · "),
                            fontFamily = Mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline,
                        )
                        c.comment?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp) }
                    }
                    if (isTable && rel != null) {
                        Box {
                            IconButton({ menuFor = c.name }) { Icon(Icons.Filled.MoreVert, "Column actions") }
                            DropdownMenu(menuFor == c.name, { menuFor = null }) {
                                DropdownMenuItem(text = { Text("Rename...") }, onClick = { menuFor = null; prompt = c to "rename" })
                                DropdownMenuItem(text = { Text("Change type...") }, onClick = { menuFor = null; prompt = c to "type" })
                                DropdownMenuItem(text = { Text("Set default...") }, onClick = { menuFor = null; prompt = c to "default" })
                                DropdownMenuItem(
                                    text = { Text(if (c.notNull) "Allow NULL" else "Set NOT NULL") },
                                    onClick = { menuFor = null; onSql("Alter column", SqlBuilder.setNotNull(rel.schema, rel.name, c.name, !c.notNull)) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Drop column", color = MaterialTheme.colorScheme.error) },
                                    onClick = { menuFor = null; onSql("Drop column", SqlBuilder.dropColumn(rel.schema, rel.name, c.name)) },
                                )
                            }
                        }
                    }
                }
                if (i < d.columns.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    if (rel != null) {
        prompt?.let { (col, what) ->
            val (title, label, initial) = when (what) {
                "rename" -> Triple("Rename column", "New name", col.name)
                "type" -> Triple("Change type of ${col.name}", "New data type", col.type)
                else -> Triple("Default for ${col.name}", "Default expression (blank removes)", col.default.orEmpty())
            }
            PromptDialog(title, label, initial, "Review SQL", allowNull = what == "default", mono = what != "rename", onConfirm = { v ->
                val value = v.orEmpty().trim()
                val sql = when (what) {
                    "rename" -> SqlBuilder.renameColumn(rel.schema, rel.name, col.name, value)
                    "type" -> SqlBuilder.alterType(rel.schema, rel.name, col.name, value)
                    else -> SqlBuilder.setDefault(rel.schema, rel.name, col.name, value)
                }
                prompt = null
                onSql(title, sql)
            }, onDismiss = { prompt = null })
        }
        if (adding) AddColumnDialog(onGenerate = { adding = false; onSql("Add column", SqlBuilder.addColumn(rel.schema, rel.name, it)) }, onDismiss = { adding = false })
    }
}

@Composable
private fun ListView(items: List<Triple<String, String, String>>, onDrop: (String) -> Unit) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            items.forEachIndexed { i, (name, kind, def) ->
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(8.dp))
                            AssistChip({}, label = { Text(kind, fontSize = 11.sp) }, modifier = Modifier.height(24.dp))
                        }
                        SelectionContainer { Text(def, fontFamily = Mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) }
                    }
                    Box {
                        IconButton({ menuFor = name }) { Icon(Icons.Filled.MoreVert, "Actions") }
                        DropdownMenu(menuFor == name, { menuFor = null }) {
                            DropdownMenuItem(text = { Text("Drop...", color = MaterialTheme.colorScheme.error) }, onClick = { menuFor = null; onDrop(name) })
                        }
                    }
                }
                if (i < items.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun SqlView(sql: String, onOpen: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ clipboard.setText(AnnotatedString(sql)) }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copy") }
            OutlinedButton({ onOpen(sql) }) { Icon(Icons.Filled.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Edit & run") }
        }
        Card(Modifier.weight(1f).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            SelectionContainer {
                Text(
                    highlightSql(sql, SqlColors(MaterialTheme.colorScheme.primary, Color(0xFF2E7D32), MaterialTheme.colorScheme.outline, Color(0xFFB45F06), Color(0xFF7B1FA2))),
                    fontFamily = Mono, fontSize = 13.sp, lineHeight = 20.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun DashboardView(d: Dashboard, vm: AppViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Server", style = MaterialTheme.typography.titleSmall)
                d.info.forEach { (k, v) ->
                    Row {
                        Text(k, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(130.dp), fontSize = 12.sp)
                        Text(v, modifier = Modifier.weight(1f), fontSize = 12.sp)
                    }
                }
            }
        }
        GridCard("Databases", d.databases, 220.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Sessions", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton({ vm.reloadDetails() }) { Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Refresh") }
        }
        GridCard(null, d.activity, 300.dp)
    }
}

@Composable
private fun GridCard(title: String?, result: QueryResult, height: androidx.compose.ui.unit.Dp) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(12.dp)) }
            if (result.rows.isEmpty()) Text("No rows", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.outline)
            else ResultGrid(result, Modifier.fillMaxWidth().height(height))
        }
    }
}
