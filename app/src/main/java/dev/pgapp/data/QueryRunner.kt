package dev.pgapp.data

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

/** Executes SQL on one dedicated connection and can be cancelled from another thread. */
class QueryRunner(private val conn: Connection) {
    @Volatile private var current: Statement? = null

    /** Runs one or more statements. Throws SQLException on the first failure. */
    fun run(sql: String, maxRows: Int = DEFAULT_MAX_ROWS): RunResult {
        val started = System.nanoTime()
        val outcomes = mutableListOf<StatementOutcome>()
        val notices = mutableListOf<String>()
        conn.createStatement().use { stmt ->
            current = stmt
            try {
                stmt.maxRows = maxRows
                var hasResultSet = stmt.execute(sql)
                while (true) {
                    if (hasResultSet) {
                        stmt.resultSet.use { outcomes += StatementOutcome.Rows(readAll(it, maxRows)) }
                    } else {
                        val n = stmt.updateCount
                        if (n == -1) break
                        outcomes += StatementOutcome.Update(n)
                    }
                    hasResultSet = stmt.moreResults
                }
                var w = stmt.warnings
                while (w != null) { notices += w.message ?: ""; w = w.nextWarning }
            } finally {
                current = null
            }
        }
        return RunResult(outcomes, notices, (System.nanoTime() - started) / 1_000_000)
    }

    /** Asks the server to cancel the running statement. Safe to call from any thread. */
    fun cancel() {
        try { current?.cancel() } catch (_: SQLException) { }
    }

    fun close() = runCatching { conn.close() }

    companion object {
        const val DEFAULT_MAX_ROWS = 5000

        fun readAll(rs: ResultSet, maxRows: Int): QueryResult {
            val md = rs.metaData
            val n = md.columnCount
            val names = (1..n).map { md.getColumnLabel(it) }
            val types = (1..n).map { md.getColumnTypeName(it) ?: "" }
            val rows = ArrayList<List<String?>>()
            while (rs.next()) {
                rows += (1..n).map { rs.getString(it) }
            }
            return QueryResult(names, types, rows, truncated = rows.size >= maxRows)
        }
    }
}
