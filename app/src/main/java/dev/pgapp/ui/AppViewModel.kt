package dev.pgapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pgapp.data.*
import dev.pgapp.security.KeystoreCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class NodeKind { SERVER, DATABASE, SCHEMA, FOLDER, TABLE, VIEW, MATVIEW, SEQUENCE, FOREIGN, FUNCTION, COLUMN, INDEX, CONSTRAINT }

data class TreeNode(
    val key: String,
    val kind: NodeKind,
    val label: String,
    val profileId: String,
    val db: String? = null,
    val schema: String? = null,
    val relation: Relation? = null,
    val function: FunctionInfo? = null,
    val folder: String? = null,
    val detail: String? = null,
) {
    val expandable: Boolean
        get() = kind == NodeKind.SERVER || kind == NodeKind.DATABASE || kind == NodeKind.SCHEMA || kind == NodeKind.FOLDER ||
            kind == NodeKind.TABLE || kind == NodeKind.VIEW || kind == NodeKind.MATVIEW || kind == NodeKind.FOREIGN
}

data class Dashboard(val info: List<Pair<String, String>>, val databases: QueryResult, val activity: QueryResult)

data class Details(
    val node: TreeNode,
    val props: List<Pair<String, String>> = emptyList(),
    val columns: List<ColumnInfo> = emptyList(),
    val constraints: List<ConstraintInfo> = emptyList(),
    val indexes: List<IndexInfo> = emptyList(),
    val stats: List<Pair<String, String>> = emptyList(),
    val ddl: String? = null,
    val dashboard: Dashboard? = null,
    val error: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProfileStore(File(app.filesDir, "profiles.json"), KeystoreCipher())

    private val _profiles = MutableStateFlow(safeList())
    val profiles: StateFlow<List<ConnectionProfile>> = _profiles

    private val _sessions = MutableStateFlow<Map<String, ServerSession>>(emptyMap())
    val sessions: StateFlow<Map<String, ServerSession>> = _sessions

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private val _children = MutableStateFlow<Map<String, List<TreeNode>>>(emptyMap())
    val children: StateFlow<Map<String, List<TreeNode>>> = _children

    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    val expanded: StateFlow<Set<String>> = _expanded

    private val _loading = MutableStateFlow<Set<String>>(emptySet())
    val loading: StateFlow<Set<String>> = _loading

    private val _selected = MutableStateFlow<TreeNode?>(null)
    val selected: StateFlow<TreeNode?> = _selected

    private val _details = MutableStateFlow<Details?>(null)
    val details: StateFlow<Details?> = _details

    private val _detailsLoading = MutableStateFlow(false)
    val detailsLoading: StateFlow<Boolean> = _detailsLoading

    private val _tabs = MutableStateFlow<List<WorkTab>>(emptyList())
    val tabs: StateFlow<List<WorkTab>> = _tabs

    private val _activeTab = MutableStateFlow(OBJECT_TAB)
    val activeTab: StateFlow<String> = _activeTab

    /** Bumped when a session's write mode changes so observers re-read it. */
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick

    private var detailsJob: Job? = null
    private var tabCounter = 0

    fun postStatus(msg: String) { _status.value = msg }
    fun clearStatus() { _status.value = null }

    // ---------------- Profiles ----------------

    fun save(profile: ConnectionProfile, password: String?) {
        runCatching { store.save(profile, password) }
            .onSuccess { _profiles.value = safeList(); _status.value = "Saved ${profile.name}" }
            .onFailure { _status.value = "Could not save: ${it.message ?: it.javaClass.simpleName}" }
    }

    fun delete(id: String) {
        disconnect(id)
        runCatching { store.delete(id) }
        _profiles.value = safeList()
    }

    fun storedPassword(id: String): String? = runCatching { store.password(id) }.getOrElse {
        _status.value = "Could not read saved password. Authenticate and try again."
        null
    }

    suspend fun test(profile: ConnectionProfile, password: String): String =
        ConnectionManager().test(profile, password).fold({ "Success: $it" }, { "Failed: ${it.message}" })

    // ---------------- Sessions ----------------

    private suspend fun connect(profile: ConnectionProfile, password: String): Boolean {
        _status.value = "Connecting to ${profile.name}..."
        val session = ServerSession(profile, password)
        return try {
            session.shared(profile.database)
            _sessions.value = _sessions.value + (profile.id to session)
            _status.value = "Connected to ${profile.name}"
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = "Failed: ${userMessage(e)}"
            false
        }
    }

    fun connectServer(profileId: String, authenticate: suspend (String) -> Boolean) {
        val profile = _profiles.value.firstOrNull { it.id == profileId } ?: return
        if (_sessions.value.containsKey(profileId)) return
        viewModelScope.launch {
            if (!authenticate("Unlock saved password")) return@launch
            if (connect(profile, storedPassword(profileId).orEmpty())) {
                val node = serverNode(profile)
                _expanded.value = _expanded.value + node.key
                loadChildren(node)
                select(node)
            }
        }
    }

    fun disconnect(profileId: String) {
        val session = _sessions.value[profileId]
        val prefix = "s:$profileId"
        _tabs.value.filter { it.profileId == profileId }.forEach { closeTab(it.id) }
        _sessions.value = _sessions.value - profileId
        _children.value = _children.value.filterKeys { !it.startsWith(prefix) }
        _expanded.value = _expanded.value.filterNot { it.startsWith(prefix) }.toSet()
        if (_selected.value?.profileId == profileId) {
            _selected.value = null
            _details.value = null
        }
        if (session != null) viewModelScope.launch { session.close() }
    }

    fun setWrites(profileId: String, on: Boolean) {
        val s = _sessions.value[profileId] ?: return
        viewModelScope.launch {
            s.setWrites(on)
            _status.value = if (on) "Write mode enabled for ${s.profile.name}" else "${s.profile.name} is read-only"
            _tick.value = _tick.value + 1
        }
    }

    fun serverNode(p: ConnectionProfile) =
        TreeNode("s:${p.id}", NodeKind.SERVER, p.name, p.id, db = p.database, detail = "${p.user}@${p.host}:${p.port}")

    // ---------------- Tree ----------------

    fun findNode(key: String): TreeNode? {
        _profiles.value.firstOrNull { "s:${it.id}" == key }?.let { return serverNode(it) }
        return _children.value.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.key == key } }
    }

    fun toggle(node: TreeNode) {
        if (!node.expandable) return
        if (node.key in _expanded.value) {
            _expanded.value = _expanded.value - node.key
            return
        }
        _expanded.value = _expanded.value + node.key
        if (_children.value[node.key] == null) viewModelScope.launch { loadChildren(node) }
    }

    fun refresh(node: TreeNode) {
        val prefix = node.key + "/"
        _children.value = _children.value.filterKeys { it != node.key && !it.startsWith(prefix) }
        _expanded.value = _expanded.value.filterNot { it.startsWith(prefix) }.toSet()
        if (node.key in _expanded.value && node.expandable) viewModelScope.launch { loadChildren(node) }
        if (_selected.value?.key == node.key || _selected.value?.key?.startsWith(prefix) == true) loadDetails(_selected.value!!)
    }

    private suspend fun loadChildren(node: TreeNode) {
        if (_sessions.value[node.profileId] == null) return
        _loading.value = _loading.value + node.key
        try {
            val list = fetchChildren(node)
            _children.value = _children.value + (node.key to list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = "Could not load ${node.label}: ${userMessage(e)}"
            _expanded.value = _expanded.value - node.key
        } finally {
            _loading.value = _loading.value - node.key
        }
    }

    private fun TreeNode.sub(
        seg: String, kind: NodeKind, label: String, detail: String? = null, folder: String? = null,
        relation: Relation? = this.relation, function: FunctionInfo? = null,
        db: String? = this.db, schema: String? = this.schema,
    ) = TreeNode("$key/$seg", kind, label, profileId, db, schema, relation, function, folder, detail)

    private fun kindOf(r: RelKind) = when (r) {
        RelKind.TABLE, RelKind.PARTITIONED -> NodeKind.TABLE
        RelKind.VIEW -> NodeKind.VIEW
        RelKind.MATVIEW -> NodeKind.MATVIEW
        RelKind.FOREIGN -> NodeKind.FOREIGN
        RelKind.SEQUENCE -> NodeKind.SEQUENCE
    }

    private suspend fun fetchChildren(node: TreeNode): List<TreeNode> {
        val session = _sessions.value[node.profileId] ?: error("Not connected")
        return withContext(Dispatchers.IO) {
            when (node.kind) {
                NodeKind.SERVER -> Catalog.databases(session.shared(session.profile.database))
                    .map { node.sub("db:$it", NodeKind.DATABASE, it, db = it) }

                NodeKind.DATABASE -> Catalog.schemas(session.shared(node.db!!))
                    .map { node.sub("sc:$it", NodeKind.SCHEMA, it, schema = it) }

                NodeKind.SCHEMA -> listOf(
                    "tables" to "Tables", "views" to "Views", "matviews" to "Materialized Views",
                    "sequences" to "Sequences", "functions" to "Functions",
                ).map { (f, l) -> node.sub(f, NodeKind.FOLDER, l, folder = f) }

                NodeKind.FOLDER -> {
                    val c = session.shared(node.db!!)
                    val schema = node.schema
                    val rel = node.relation
                    when (node.folder) {
                        "tables" -> Catalog.relations(c, schema!!, listOf(RelKind.TABLE, RelKind.PARTITIONED, RelKind.FOREIGN))
                            .map { node.sub("r:${it.name}", kindOf(it.kind), it.name, relation = it, detail = if (it.kind == RelKind.TABLE) null else it.kind.label) }
                        "views" -> Catalog.relations(c, schema!!, listOf(RelKind.VIEW)).map { node.sub("r:${it.name}", NodeKind.VIEW, it.name, relation = it) }
                        "matviews" -> Catalog.relations(c, schema!!, listOf(RelKind.MATVIEW)).map { node.sub("r:${it.name}", NodeKind.MATVIEW, it.name, relation = it) }
                        "sequences" -> Catalog.relations(c, schema!!, listOf(RelKind.SEQUENCE)).map { node.sub("r:${it.name}", NodeKind.SEQUENCE, it.name, relation = it) }
                        "functions" -> Catalog.functions(c, schema!!).map { node.sub("fn:${it.oid}", NodeKind.FUNCTION, it.signature, function = it, detail = it.returns) }
                        "columns" -> Catalog.columns(c, rel!!.schema, rel.name).map {
                            node.sub("col:${it.name}", NodeKind.COLUMN, it.name,
                                detail = listOfNotNull(it.type, if (it.isPk) "PK" else null, if (it.notNull) "not null" else null).joinToString(" · "))
                        }
                        "indexes" -> Catalog.indexes(c, rel!!.schema, rel.name).map {
                            node.sub("ix:${it.name}", NodeKind.INDEX, it.name, detail = if (it.primary) "primary" else if (it.unique) "unique" else null)
                        }
                        "constraints" -> Catalog.constraints(c, rel!!.schema, rel.name).map {
                            node.sub("ct:${it.name}", NodeKind.CONSTRAINT, it.name, detail = it.typeLabel)
                        }
                        else -> emptyList()
                    }
                }

                NodeKind.TABLE -> listOf("columns" to "Columns", "constraints" to "Constraints", "indexes" to "Indexes")
                    .map { (f, l) -> node.sub(f, NodeKind.FOLDER, l, folder = f) }
                NodeKind.MATVIEW -> listOf("columns" to "Columns", "indexes" to "Indexes")
                    .map { (f, l) -> node.sub(f, NodeKind.FOLDER, l, folder = f) }
                NodeKind.VIEW, NodeKind.FOREIGN -> listOf(node.sub("columns", NodeKind.FOLDER, "Columns", folder = "columns"))

                else -> emptyList()
            }
        }
    }

    // ---------------- Selection / details ----------------

    fun select(node: TreeNode) {
        val prev = _selected.value
        _selected.value = node
        _activeTab.value = OBJECT_TAB
        val sameRelation = node.relation != null && prev?.relation == node.relation && _details.value?.node?.relation == node.relation
        if (!sameRelation) loadDetails(node)
    }

    fun reloadDetails() { _selected.value?.let { loadDetails(it) } }

    private fun loadDetails(node: TreeNode) {
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            _detailsLoading.value = true
            _details.value = try {
                buildDetails(node)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Details(node, error = userMessage(e))
            } finally {
                _detailsLoading.value = false
            }
        }
    }

    private suspend fun buildDetails(node: TreeNode): Details {
        val session = _sessions.value[node.profileId] ?: return Details(node)
        return withContext(Dispatchers.IO) {
            val rel = node.relation
            when {
                node.kind == NodeKind.SERVER -> {
                    val c = session.shared(session.profile.database)
                    Details(node, dashboard = Dashboard(Catalog.serverInfo(c), Catalog.databaseSizes(c), Catalog.activity(c)))
                }
                node.kind == NodeKind.DATABASE -> Details(node, props = Catalog.databaseProperties(session.shared(node.db!!)))
                node.kind == NodeKind.SCHEMA -> Details(node, props = Catalog.schemaProperties(session.shared(node.db!!), node.schema!!))
                node.kind == NodeKind.FUNCTION -> {
                    val c = session.shared(node.db!!)
                    val f = node.function!!
                    Details(node, props = Catalog.functionProperties(c, f.oid), ddl = Catalog.functionDdl(c, f.oid))
                }
                rel != null -> {
                    val c = session.shared(node.db!!)
                    val isSeq = rel.kind == RelKind.SEQUENCE
                    Details(
                        node,
                        props = Catalog.relationProperties(c, rel.schema, rel.name),
                        columns = if (isSeq) emptyList() else Catalog.columns(c, rel.schema, rel.name),
                        constraints = if (rel.kind.isTableLike) Catalog.constraints(c, rel.schema, rel.name) else emptyList(),
                        indexes = if (isSeq) emptyList() else Catalog.indexes(c, rel.schema, rel.name),
                        stats = if (isSeq) emptyList() else Catalog.relationStatistics(c, rel.schema, rel.name),
                        ddl = when (rel.kind) {
                            RelKind.TABLE, RelKind.PARTITIONED, RelKind.FOREIGN -> Catalog.tableDdl(c, rel.schema, rel.name)
                            RelKind.VIEW -> Catalog.viewDdl(c, rel.schema, rel.name, false)
                            RelKind.MATVIEW -> Catalog.viewDdl(c, rel.schema, rel.name, true)
                            RelKind.SEQUENCE -> null
                        },
                    )
                }
                else -> Details(node)
            }
        }
    }

    // ---------------- Tabs ----------------

    fun openQuery(profileId: String, db: String, sql: String = "") {
        val session = _sessions.value[profileId] ?: return
        val n = ++tabCounter
        val tab = QueryTab("q$n", "Query $n · $db", profileId, db, session, sql)
        _tabs.value = _tabs.value + tab
        _activeTab.value = tab.id
    }

    fun openData(profileId: String, db: String, rel: Relation) {
        val session = _sessions.value[profileId] ?: return
        val existing = _tabs.value.filterIsInstance<DataTab>().firstOrNull { it.profileId == profileId && it.db == db && it.relation == rel }
        if (existing != null) { _activeTab.value = existing.id; return }
        val tab = DataTab("d${++tabCounter}", "${rel.schema}.${rel.name}", profileId, db, rel, session)
        _tabs.value = _tabs.value + tab
        _activeTab.value = tab.id
        tab.reload(viewModelScope)
    }

    fun activate(id: String) { _activeTab.value = id }

    fun closeTab(id: String) {
        val tab = _tabs.value.firstOrNull { it.id == id } ?: return
        tab.close(viewModelScope)
        _tabs.value = _tabs.value - tab
        if (_activeTab.value == id) _activeTab.value = _tabs.value.lastOrNull()?.id ?: OBJECT_TAB
    }

    // ---------------- DDL / commands ----------------

    /** Runs [sql] on the shared connection. Returns a summary message or a failure with a clean message. */
    suspend fun execute(profileId: String, db: String, sql: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val session = _sessions.value[profileId] ?: error("Not connected")
            val res = QueryRunner(session.shared(db)).run(sql)
            val updates = res.outcomes.filterIsInstance<StatementOutcome.Update>().sumOf { it.count }
            Result.success("Completed in ${res.elapsedMs} ms" + if (updates > 0) " · $updates rows affected" else "")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(Exception(userMessage(e)))
        }
    }

    private fun safeList() = runCatching { store.list() }.getOrDefault(emptyList())

    override fun onCleared() {
        _tabs.value.forEach { it.close(viewModelScope) }
        _sessions.value.values.forEach { s -> kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { s.close() } }
    }
}
