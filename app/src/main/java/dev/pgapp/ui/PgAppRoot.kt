package dev.pgapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pgapp.R
import dev.pgapp.data.*
import kotlinx.coroutines.launch

private sealed interface Dlg {
    data class Server(val profile: ConnectionProfile?) : Dlg
    data class Exec(val title: String, val profileId: String, val db: String, val sql: String, val refreshKey: String?) : Dlg
    data class CreateTable(val profileId: String, val db: String, val schema: String, val refreshKey: String) : Dlg
    data class Confirm(val title: String, val message: String, val label: String, val destructive: Boolean, val action: () -> Unit) : Dlg
    data class Prompt(val title: String, val label: String, val ok: (String) -> Unit) : Dlg
}

/**
 * Root layout (pgAdmin style):
 *   left  = object browser tree (resizable on wide screens, drawer on narrow)
 *   right = tab strip (Properties, Query Tools, Data views) and the active workspace.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PgAppRoot(authenticate: suspend (String) -> Boolean) {
    val vm: AppViewModel = viewModel()
    PgAppTheme {
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val status by vm.status.collectAsState()
        val sessions by vm.sessions.collectAsState()
        val profiles by vm.profiles.collectAsState()
        val selected by vm.selected.collectAsState()
        val tabs by vm.tabs.collectAsState()
        val active by vm.activeTab.collectAsState()
        val tick by vm.tick.collectAsState()
        val drawer = rememberDrawerState(DrawerValue.Closed)
        var dlg by remember { mutableStateOf<Dlg?>(null) }
        var browserWidth by remember { mutableStateOf(340.dp) }

        LaunchedEffect(status) {
            status?.let { snackbar.showSnackbar(it); vm.clearStatus() }
        }

        fun dbOf(n: TreeNode) = n.db ?: profiles.firstOrNull { it.id == n.profileId }?.database ?: "postgres"
        fun parentKey(n: TreeNode) = n.key.substringBeforeLast("/")

        fun exec(title: String, n: TreeNode, sql: String, refreshKey: String?) {
            dlg = Dlg.Exec(title, n.profileId, dbOf(n), sql, refreshKey)
        }

        fun askWrites(profileId: String) {
            val name = profiles.firstOrNull { it.id == profileId }?.name ?: "server"
            dlg = Dlg.Confirm(
                "Enable write mode?",
                "Queries on \"$name\" will be able to change data and structure. Open connections are re-created. You can switch back to read-only at any time.",
                "Enable writes", false,
            ) { vm.setWrites(profileId, true) }
        }

        fun handle(node: TreeNode, action: MenuAction) {
            when (action) {
                MenuAction.CONNECT -> vm.connectServer(node.profileId, authenticate)
                MenuAction.DISCONNECT -> vm.disconnect(node.profileId)
                MenuAction.EDIT_SERVER -> dlg = Dlg.Server(profiles.firstOrNull { it.id == node.profileId })
                MenuAction.DELETE_SERVER -> dlg = Dlg.Confirm("Remove server?", "\"${node.label}\" and its saved password will be deleted from this device.", "Remove", true) { vm.delete(node.profileId) }
                MenuAction.REFRESH -> vm.refresh(node)
                MenuAction.QUERY_TOOL -> { vm.openQuery(node.profileId, dbOf(node)); scope.launch { drawer.close() } }
                MenuAction.VIEW_DATA -> node.relation?.let { vm.openData(node.profileId, dbOf(node), it); scope.launch { drawer.close() } }
                MenuAction.SELECT_SCRIPT -> node.relation?.let {
                    vm.openQuery(node.profileId, dbOf(node), "SELECT * FROM ${Sql.qualified(it.schema, it.name)}\nLIMIT 100;")
                    scope.launch { drawer.close() }
                }
                MenuAction.CREATE_DATABASE -> dlg = Dlg.Prompt("Create database", "Database name") { exec("Create database", node, SqlBuilder.createDatabase(it.trim()), node.key) }
                MenuAction.CREATE_SCHEMA -> dlg = Dlg.Prompt("Create schema", "Schema name") { exec("Create schema", node, SqlBuilder.createSchema(it.trim()), node.key) }
                MenuAction.DROP_SCHEMA -> dlg = Dlg.Confirm("Drop schema?", "Review the SQL on the next screen before it runs.", "Continue", true) {
                    exec("Drop schema", node, SqlBuilder.dropSchema(node.schema.orEmpty(), false), parentKey(node))
                }
                MenuAction.CREATE_TABLE -> {
                    val schemaKey = if (node.kind == NodeKind.SCHEMA) node.key else parentKey(node)
                    dlg = Dlg.CreateTable(node.profileId, dbOf(node), node.schema.orEmpty(), "$schemaKey/tables")
                }
                MenuAction.RENAME_TABLE -> node.relation?.let { r ->
                    dlg = Dlg.Prompt("Rename ${r.name}", "New name") { exec("Rename table", node, SqlBuilder.renameTable(r.schema, r.name, it.trim()), parentKey(node)) }
                }
                MenuAction.TRUNCATE -> node.relation?.let { r ->
                    dlg = Dlg.Confirm("Truncate ${r.name}?", "All rows will be removed. Review the SQL on the next screen.", "Continue", true) {
                        exec("Truncate table", node, SqlBuilder.truncate(r.schema, r.name), null)
                    }
                }
                MenuAction.DROP_OBJECT -> node.relation?.let { r ->
                    dlg = Dlg.Confirm("Drop ${r.name}?", "This removes the ${r.kind.label.lowercase()}. Review the SQL on the next screen.", "Continue", true) {
                        exec("Drop ${r.kind.label.lowercase()}", node, SqlBuilder.dropRelation(r.kind, r.schema, r.name), parentKey(node))
                    }
                }
                MenuAction.EDIT_FUNCTION -> {
                    val ddl = vm.details.value?.takeIf { it.node.key == node.key }?.ddl.orEmpty()
                    vm.openQuery(node.profileId, dbOf(node), ddl)
                    scope.launch { drawer.close() }
                }
                MenuAction.TOGGLE_WRITES -> {
                    val s = sessions[node.profileId]
                    if (s?.writes == true) vm.setWrites(node.profileId, false) else askWrites(node.profileId)
                }
            }
        }

        val browser: @Composable (Modifier) -> Unit = { m ->
            BrowserPanel(vm, onAddServer = { dlg = Dlg.Server(null) }, onMenu = ::handle, modifier = m)
        }

        val workspace: @Composable (Modifier) -> Unit = { m ->
            Column(m.background(MaterialTheme.colorScheme.background)) {
                TabBar(tabs, active, vm::activate, vm::closeTab)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val tab = tabs.firstOrNull { it.id == active }
                    when (tab) {
                        null -> ObjectPanel(vm, onMenu = ::handle, onSql = { title, sql -> selected?.let { exec(title, it, sql, it.key.substringBeforeLast("/")) } })
                        is QueryTab -> key(tab.id) { QueryScreen(tab, tick, onEnableWrites = { askWrites(tab.profileId) }) }
                        is DataTab -> key(tab.id) { DataScreen(tab, tick, onEnableWrites = { askWrites(tab.profileId) }) }
                    }
                }
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { pad ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
                if (maxWidth >= 840.dp) {
                    val density = LocalDensity.current
                    Row(Modifier.fillMaxSize()) {
                        browser(Modifier.width(browserWidth).fillMaxHeight())
                        Box(
                            Modifier.width(6.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant)
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures { _, delta ->
                                        browserWidth = (browserWidth + with(density) { delta.toDp() }).coerceIn(240.dp, 560.dp)
                                    }
                                },
                        )
                        workspace(Modifier.weight(1f).fillMaxHeight())
                    }
                } else {
                    ModalNavigationDrawer(
                        drawerState = drawer,
                        drawerContent = { ModalDrawerSheet(Modifier.width(320.dp)) { browser(Modifier.fillMaxSize()) } },
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            Surface(tonalElevation = 2.dp) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Open browser") }
                                    Image(painterResource(R.drawable.ic_logo), null, Modifier.size(24.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(selected?.label ?: "PgApp", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            workspace(Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
            }
        }

        // Dialogs. `tick` is read so write-mode changes recompose the SQL dialog.
        when (val d = dlg) {
            null -> {}
            is Dlg.Server -> ServerDialog(
                initial = d.profile,
                onDismiss = { dlg = null },
                onSave = { profile, pw ->
                    scope.launch {
                        if (pw == null || authenticate("Protect saved password")) { vm.save(profile, pw); dlg = null }
                    }
                },
                onTest = { profile, typed ->
                    val pw = typed.ifEmpty {
                        if (d.profile != null && authenticate("Unlock saved password")) vm.storedPassword(d.profile.id) else null
                    }
                    if (pw == null) "No password available" else vm.test(profile, pw)
                },
            )
            is Dlg.Confirm -> ConfirmDialog(d.title, d.message, d.label, d.destructive, onConfirm = { dlg = null; d.action() }, onDismiss = { dlg = null })
            is Dlg.Prompt -> PromptDialog(d.title, d.label, confirmLabel = "Review SQL", onConfirm = { v -> dlg = null; d.ok(v.orEmpty()) }, onDismiss = { dlg = null })
            is Dlg.CreateTable -> CreateTableDialog(
                d.schema,
                onGenerate = { sql -> dlg = Dlg.Exec("Create table", d.profileId, d.db, sql, d.refreshKey) },
                onDismiss = { dlg = null },
            )
            is Dlg.Exec -> {
                val writes = tick.let { sessions[d.profileId]?.writes == true }
                ExecSqlDialog(
                    title = d.title,
                    initialSql = d.sql,
                    writesEnabled = writes,
                    onEnableWrites = { vm.setWrites(d.profileId, true) },
                    onRun = { sql -> vm.execute(d.profileId, d.db, sql).onSuccess { vm.postStatus(it) } },
                    onDone = {
                        d.refreshKey?.let { k -> vm.findNode(k)?.let(vm::refresh) }
                        vm.reloadDetails()
                        dlg = null
                    },
                    onDismiss = { dlg = null },
                )
            }
        }
    }
}

@Composable
private fun TabBar(tabs: List<WorkTab>, active: String, onSelect: (String) -> Unit, onClose: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TabChip("Properties", Icons.Filled.Info, active == OBJECT_TAB, { onSelect(OBJECT_TAB) }, null)
            tabs.forEach { t ->
                TabChip(t.title, if (t is QueryTab) Icons.Filled.Code else Icons.Filled.TableChart, active == t.id, { onSelect(t.id) }, { onClose(t.id) })
            }
        }
    }
}

@Composable
private fun TabChip(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit, onClose: (() -> Unit)?) {
    Column(Modifier.background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)) {
        Row(Modifier.clickable(onClick = onClick).padding(start = 12.dp, end = if (onClose == null) 12.dp else 4.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            if (onClose != null) {
                IconButton(onClose, Modifier.size(28.dp)) { Icon(Icons.Filled.Close, "Close tab", Modifier.size(14.dp)) }
            }
        }
        Box(Modifier.height(2.dp).fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent))
    }
}
