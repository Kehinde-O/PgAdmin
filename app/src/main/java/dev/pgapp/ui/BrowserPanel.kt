package dev.pgapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pgapp.R
import dev.pgapp.data.ConnectionProfile

enum class MenuAction(val label: String) {
    CONNECT("Connect"), DISCONNECT("Disconnect"), EDIT_SERVER("Edit server..."), DELETE_SERVER("Remove server"),
    REFRESH("Refresh"), QUERY_TOOL("Query Tool"), VIEW_DATA("View/Edit Data"), SELECT_SCRIPT("Query Tool: SELECT *"),
    CREATE_DATABASE("Create database..."), CREATE_SCHEMA("Create schema..."), DROP_SCHEMA("Drop schema..."),
    CREATE_TABLE("Create table..."), RENAME_TABLE("Rename..."), TRUNCATE("Truncate..."), DROP_OBJECT("Drop..."),
    EDIT_FUNCTION("Open in Query Tool"), TOGGLE_WRITES("Toggle write mode"),
}

fun menuFor(node: TreeNode, connected: Boolean): List<MenuAction> = when (node.kind) {
    NodeKind.SERVER -> if (connected) {
        listOf(MenuAction.QUERY_TOOL, MenuAction.CREATE_DATABASE, MenuAction.REFRESH, MenuAction.DISCONNECT, MenuAction.EDIT_SERVER, MenuAction.DELETE_SERVER)
    } else listOf(MenuAction.CONNECT, MenuAction.EDIT_SERVER, MenuAction.DELETE_SERVER)
    NodeKind.DATABASE -> listOf(MenuAction.QUERY_TOOL, MenuAction.CREATE_SCHEMA, MenuAction.REFRESH)
    NodeKind.SCHEMA -> listOf(MenuAction.QUERY_TOOL, MenuAction.CREATE_TABLE, MenuAction.DROP_SCHEMA, MenuAction.REFRESH)
    NodeKind.FOLDER -> if (node.folder == "tables") listOf(MenuAction.CREATE_TABLE, MenuAction.REFRESH) else listOf(MenuAction.REFRESH)
    NodeKind.TABLE -> listOf(MenuAction.VIEW_DATA, MenuAction.SELECT_SCRIPT, MenuAction.RENAME_TABLE, MenuAction.TRUNCATE, MenuAction.DROP_OBJECT, MenuAction.REFRESH)
    NodeKind.VIEW, NodeKind.MATVIEW, NodeKind.FOREIGN -> listOf(MenuAction.VIEW_DATA, MenuAction.SELECT_SCRIPT, MenuAction.DROP_OBJECT, MenuAction.REFRESH)
    NodeKind.SEQUENCE -> listOf(MenuAction.DROP_OBJECT)
    NodeKind.FUNCTION -> listOf(MenuAction.EDIT_FUNCTION)
    else -> emptyList()
}

fun iconFor(kind: NodeKind): ImageVector = when (kind) {
    NodeKind.SERVER -> Icons.Filled.Storage
    NodeKind.DATABASE -> Icons.Filled.Dns
    NodeKind.SCHEMA -> Icons.Filled.AccountTree
    NodeKind.FOLDER -> Icons.Filled.Folder
    NodeKind.TABLE, NodeKind.FOREIGN -> Icons.Filled.TableChart
    NodeKind.VIEW -> Icons.Filled.Visibility
    NodeKind.MATVIEW -> Icons.Filled.Layers
    NodeKind.SEQUENCE -> Icons.Filled.Tag
    NodeKind.FUNCTION -> Icons.Filled.Functions
    NodeKind.COLUMN -> Icons.Filled.ViewColumn
    NodeKind.INDEX -> Icons.Filled.Bolt
    NodeKind.CONSTRAINT -> Icons.Filled.Rule
}

@Composable
private fun tintFor(kind: NodeKind, connected: Boolean): Color = when (kind) {
    NodeKind.SERVER -> if (connected) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.outline
    NodeKind.DATABASE -> MaterialTheme.colorScheme.primary
    NodeKind.TABLE, NodeKind.FOREIGN -> Color(0xFF2F7FC1)
    NodeKind.VIEW, NodeKind.MATVIEW -> Color(0xFF8E5BC7)
    NodeKind.FUNCTION -> Color(0xFFD08A1E)
    NodeKind.FOLDER, NodeKind.SCHEMA -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.outline
}

private data class TreeRow(val node: TreeNode, val depth: Int)

private fun flatten(
    profiles: List<ConnectionProfile>, expanded: Set<String>, children: Map<String, List<TreeNode>>,
    serverNode: (ConnectionProfile) -> TreeNode,
): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    fun walk(n: TreeNode, d: Int) {
        out += TreeRow(n, d)
        if (n.key in expanded) children[n.key]?.forEach { walk(it, d + 1) }
    }
    profiles.forEach { walk(serverNode(it), 0) }
    return out
}

/** pgAdmin-style object browser tree. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserPanel(
    vm: AppViewModel,
    onAddServer: () -> Unit,
    onMenu: (TreeNode, MenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profiles by vm.profiles.collectAsState()
    val expanded by vm.expanded.collectAsState()
    val children by vm.children.collectAsState()
    val loading by vm.loading.collectAsState()
    val selected by vm.selected.collectAsState()
    val sessions by vm.sessions.collectAsState()
    var menuKey by remember { mutableStateOf<String?>(null) }

    val rows = remember(profiles, expanded, children) { flatten(profiles, expanded, children, vm::serverNode) }

    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(30.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("PgApp", style = MaterialTheme.typography.titleMedium)
                Text("Object Explorer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            IconButton(onAddServer) { Icon(Icons.Filled.Add, "Register server") }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (profiles.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("No servers yet", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text("Register a PostgreSQL server to start browsing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Button(onAddServer) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Register server") }
            }
            return@Column
        }

        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.node.key }) { (node, depth) ->
                val isConnected = sessions.containsKey(node.profileId)
                val isSelected = selected?.key == node.key
                val isOpen = node.key in expanded
                Box {
                    Row(
                        Modifier.fillMaxWidth().height(36.dp)
                            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .combinedClickable(
                                onClick = {
                                    if (node.kind == NodeKind.SERVER && !isConnected) {
                                        vm.select(node); onMenu(node, MenuAction.CONNECT)
                                    } else {
                                        vm.select(node)
                                        if (node.expandable && !isOpen) vm.toggle(node)
                                    }
                                },
                                onLongClick = { vm.select(node); menuKey = node.key },
                            )
                            .padding(start = (8 + depth * 16).dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (node.expandable && (node.kind != NodeKind.SERVER || isConnected)) {
                            Icon(
                                if (isOpen) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null,
                                Modifier.size(20.dp).clickable { vm.toggle(node) }, tint = MaterialTheme.colorScheme.outline,
                            )
                        } else Spacer(Modifier.width(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Icon(iconFor(node.kind), null, Modifier.size(18.dp), tint = tintFor(node.kind, isConnected))
                        Spacer(Modifier.width(8.dp))
                        Text(node.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
                        node.detail?.let {
                            Spacer(Modifier.width(6.dp))
                            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.weight(1f, fill = false))
                        }
                        Spacer(Modifier.weight(0.001f))
                        if (node.key in loading) {
                            CircularProgressIndicator(Modifier.size(14.dp).padding(end = 0.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        if (isSelected && menuFor(node, isConnected).isNotEmpty()) {
                            IconButton({ menuKey = node.key }, Modifier.size(32.dp)) { Icon(Icons.Filled.MoreVert, "Actions", Modifier.size(18.dp)) }
                        }
                    }
                    DropdownMenu(expanded = menuKey == node.key, onDismissRequest = { menuKey = null }) {
                        menuFor(node, isConnected).forEach { action ->
                            DropdownMenuItem(text = { Text(action.label) }, onClick = { menuKey = null; onMenu(node, action) })
                        }
                    }
                }
            }
        }
    }
}
