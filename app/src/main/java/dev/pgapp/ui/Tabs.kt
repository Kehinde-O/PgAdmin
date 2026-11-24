package dev.pgapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.pgapp.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.sql.Connection

const val OBJECT_TAB = "object"

sealed interface WorkTab {
    val id: String
    val title: String
    val profileId: String
    val db: String
    fun close(scope: CoroutineScope)
}

/** A pgAdmin-style Query Tool: own connection, editor text, results and history. */
class QueryTab(
    override val id: String,
    override val title: String,
    override val profileId: String,
    override val db: String,
    private val session: ServerSession,
    initialSql: String,
) : WorkTab {
    var text by mutableStateOf(TextFieldValue(initialSql, TextRange(initialSql.length)))
    var running by mutableStateOf(false)
    var result by mutableStateOf<RunResult?>(null)
    var error by mutableStateOf<String?>(null)
    var activeResult by mutableIntStateOf(0)
    var names by mutableStateOf<Set<String>>(emptySet())
    val history = mutableStateListOf<String>()
    val writesEnabled get() = session.writes

    private var runner: QueryRunner? = null
    private var generation = -1
    private var job: Job? = null

    private fun currentSql(): String {
        val sel = text.selection
        return if (!sel.collapsed) text.text.substring(sel.min, sel.max) else text.text
    }

    fun run(scope: CoroutineScope, sqlOverride: String? = null) = execute(scope, (sqlOverride ?: currentSql()).trim(), remember = true)

    fun explain(scope: CoroutineScope, analyze: Boolean) {
        val sql = currentSql().trim().trimEnd(';')
        if (sql.isEmpty()) return
        execute(scope, "EXPLAIN ${if (analyze) "(ANALYZE, BUFFERS) " else ""}$sql", remember = false)
    }

    private fun execute(scope: CoroutineScope, sql: String, remember: Boolean) {
        if (running || sql.isEmpty()) return
        running = true
        error = null
        if (remember) {
            history.remove(sql)
            history.add(0, sql)
            while (history.size > 50) history.removeAt(history.lastIndex)
        }
        job = scope.launch(Dispatchers.IO) {
            try {
                val r = ensureRunner()
                result = r.run(sql)
                activeResult = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val m = userMessage(e)
                error = if (m.contains("user request")) "Query cancelled" else m
                result = null
            } finally {
                running = false
            }
        }
    }

    private suspend fun ensureRunner(): QueryRunner {
        val cur = runner
        if (cur != null && generation == session.generation) return cur
        cur?.close()
        val conn = session.open(db)
        generation = session.generation
        val r = QueryRunner(conn)
        runner = r
        if (names.isEmpty()) names = runCatching { Catalog.completionNames(conn) }.getOrDefault(emptySet())
        return r
    }

    fun cancel(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { runner?.cancel() }
    }

    override fun close(scope: CoroutineScope) {
        job?.cancel()
        val r = runner
        runner = null
        scope.launch(Dispatchers.IO) { r?.cancel(); r?.close() }
    }
}

/** "View/Edit Data" for one table or view: paged grid with cell edit, insert and delete by primary key. */
class DataTab(
    override val id: String,
    override val title: String,
    override val profileId: String,
    override val db: String,
    val relation: Relation,
    private val session: ServerSession,
) : WorkTab {
    var columns by mutableStateOf<List<ColumnInfo>>(emptyList())
    var result by mutableStateOf<QueryResult?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    var offset by mutableIntStateOf(0)
    var selRow by mutableStateOf<Int?>(null)
    var selCol by mutableStateOf<Int?>(null)

    val pageSize = 100
    val pk get() = columns.filter { it.isPk }
    val isTable get() = relation.kind == RelKind.TABLE || relation.kind == RelKind.PARTITIONED
    val writesEnabled get() = session.writes
    val editable get() = isTable && pk.isNotEmpty() && session.writes
    val hasNext get() = (result?.rows?.size ?: 0) >= pageSize

    private var conn: Connection? = null
    private var generation = -1

    private suspend fun connection(): Connection {
        val c = conn
        if (c != null && generation == session.generation && !c.isClosed) return c
        c?.let { runCatching { it.close() } }
        val fresh = session.open(db)
        conn = fresh
        generation = session.generation
        return fresh
    }

    fun reload(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { load() }
    }

    private suspend fun load() {
        loading = true
        try {
            val c = connection()
            if (columns.isEmpty()) columns = Catalog.columns(c, relation.schema, relation.name)
            val order = pk.joinToString(", ") { Sql.ident(it.name) }.let { if (it.isEmpty()) "" else " ORDER BY $it" }
            val sql = "SELECT * FROM ${Sql.qualified(relation.schema, relation.name)}$order LIMIT $pageSize OFFSET $offset"
            val rows = QueryRunner(c).run(sql, pageSize).outcomes.filterIsInstance<StatementOutcome.Rows>().firstOrNull()?.result
            result = rows
            selRow = null
            selCol = null
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = userMessage(e)
        } finally {
            loading = false
        }
    }

    fun page(scope: CoroutineScope, delta: Int) {
        val next = (offset + delta * pageSize).coerceAtLeast(0)
        if (next == offset) return
        offset = next
        reload(scope)
    }

    private fun apply(scope: CoroutineScope, p: ParamSql, ok: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val c = connection()
                c.prepareStatement(p.sql).use { ps ->
                    p.params.forEachIndexed { i, v -> ps.setString(i + 1, v) }
                    ps.executeUpdate()
                }
                info = ok
                error = null
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = userMessage(e)
            }
        }
    }

    private fun pkValues(row: Int): List<String?> {
        val r = result ?: return emptyList()
        return pk.map { col -> r.rows[row][r.columns.indexOf(col.name)] }
    }

    private fun pkTypes() = pk.map { it.name to it.type }

    fun updateCell(scope: CoroutineScope, row: Int, col: Int, value: String?) {
        val r = result ?: return
        val column = columns.firstOrNull { it.name == r.columns[col] } ?: return
        apply(
            scope,
            SqlBuilder.updateCell(relation.schema, relation.name, column.name, column.type, value, pkTypes(), pkValues(row)),
            "Row updated",
        )
    }

    fun deleteRow(scope: CoroutineScope, row: Int) =
        apply(scope, SqlBuilder.deleteRow(relation.schema, relation.name, pkTypes(), pkValues(row)), "Row deleted")

    fun insertRow(scope: CoroutineScope, values: Map<String, String?>) =
        apply(
            scope,
            SqlBuilder.insertRow(relation.schema, relation.name, columns.map { Triple(it.name, it.type, values[it.name]) }),
            "Row inserted",
        )

    override fun close(scope: CoroutineScope) {
        val c = conn
        conn = null
        scope.launch(Dispatchers.IO) { runCatching { c?.close() } }
    }
}
