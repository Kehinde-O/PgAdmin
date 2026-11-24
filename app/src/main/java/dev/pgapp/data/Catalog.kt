package dev.pgapp.data

import java.sql.Connection

/** pg_catalog queries behind the object browser, properties tabs and dashboard. */
object Catalog {

    private fun <T> Connection.query(sql: String, params: List<Any?> = emptyList(), map: (java.sql.ResultSet) -> T): List<T> =
        prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }

    /** First row as label/value pairs, using column aliases as labels. */
    private fun Connection.rowAsPairs(sql: String, params: List<Any?> = emptyList()): List<Pair<String, String>> =
        prepareStatement(sql).use { ps ->
            params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
            ps.executeQuery().use { rs ->
                if (!rs.next()) emptyList()
                else (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) to (rs.getString(it) ?: "") }
            }
        }

    private fun regclass(schema: String, name: String) = Sql.qualified(schema, name)

    fun databases(c: Connection): List<String> =
        c.query("SELECT datname FROM pg_database WHERE NOT datistemplate AND datallowconn ORDER BY 1") { it.getString(1) }

    fun schemas(c: Connection): List<String> = c.query(
        "SELECT nspname FROM pg_namespace WHERE nspname !~ '^pg_' AND nspname <> 'information_schema' ORDER BY 1",
    ) { it.getString(1) }

    /** [kinds] e.g. listOf(RelKind.TABLE, RelKind.PARTITIONED). */
    fun relations(c: Connection, schema: String, kinds: List<RelKind>): List<Relation> = c.query(
        """SELECT c.relname, c.relkind::text FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE n.nspname = ? AND c.relkind::text = ANY (string_to_array(?, ',')) ORDER BY 1""",
        listOf(schema, kinds.joinToString(",") { it.code }),
    ) { Relation(schema, it.getString(1), RelKind.from(it.getString(2))) }

    fun functions(c: Connection, schema: String): List<FunctionInfo> = c.query(
        """SELECT p.oid::bigint, p.proname, pg_get_function_identity_arguments(p.oid),
                  pg_get_function_result(p.oid), l.lanname
           FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace JOIN pg_language l ON l.oid = p.prolang
           WHERE n.nspname = ? AND p.prokind IN ('f','p') ORDER BY 2, 3""",
        listOf(schema),
    ) { FunctionInfo(it.getLong(1), schema, it.getString(2), it.getString(3) ?: "", it.getString(4) ?: "", it.getString(5) ?: "") }

    fun columns(c: Connection, schema: String, table: String): List<ColumnInfo> = c.query(
        """SELECT a.attnum, a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull,
                  pg_get_expr(d.adbin, d.adrelid), col_description(a.attrelid, a.attnum),
                  EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = a.attrelid AND i.indisprimary AND a.attnum = ANY (i.indkey))
           FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
           WHERE a.attrelid = ?::regclass AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum""",
        listOf(regclass(schema, table)),
    ) {
        ColumnInfo(
            position = it.getInt(1), name = it.getString(2), type = it.getString(3), notNull = it.getBoolean(4),
            default = it.getString(5), comment = it.getString(6), isPk = it.getBoolean(7),
        )
    }

    fun constraints(c: Connection, schema: String, table: String): List<ConstraintInfo> = c.query(
        "SELECT conname, contype::text, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = ?::regclass ORDER BY contype, conname",
        listOf(regclass(schema, table)),
    ) { ConstraintInfo(it.getString(1), it.getString(2), it.getString(3)) }

    fun indexes(c: Connection, schema: String, table: String): List<IndexInfo> = c.query(
        """SELECT ic.relname, pg_get_indexdef(i.indexrelid), i.indisunique, i.indisprimary
           FROM pg_index i JOIN pg_class ic ON ic.oid = i.indexrelid WHERE i.indrelid = ?::regclass ORDER BY 1""",
        listOf(regclass(schema, table)),
    ) { IndexInfo(it.getString(1), it.getString(2), it.getBoolean(3), it.getBoolean(4)) }

    fun relationProperties(c: Connection, schema: String, name: String): List<Pair<String, String>> = c.rowAsPairs(
        """SELECT c.relname AS "Name", n.nspname AS "Schema", pg_get_userbyid(c.relowner) AS "Owner",
                  c.oid::bigint AS "OID",
                  CASE c.relkind WHEN 'r' THEN 'Table' WHEN 'p' THEN 'Partitioned table' WHEN 'v' THEN 'View'
                       WHEN 'm' THEN 'Materialized view' WHEN 'f' THEN 'Foreign table' WHEN 'S' THEN 'Sequence' ELSE c.relkind::text END AS "Type",
                  obj_description(c.oid, 'pg_class') AS "Comment",
                  c.reltuples::bigint AS "Estimated rows",
                  pg_size_pretty(pg_total_relation_size(c.oid)) AS "Total size",
                  pg_size_pretty(pg_relation_size(c.oid)) AS "Relation size",
                  c.relhasindex AS "Has indexes", c.relispartition AS "Is partition", c.relrowsecurity AS "Row level security"
           FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?""",
        listOf(schema, name),
    )

    fun relationStatistics(c: Connection, schema: String, name: String): List<Pair<String, String>> = c.rowAsPairs(
        """SELECT seq_scan AS "Sequential scans", seq_tup_read AS "Seq tuples read", idx_scan AS "Index scans",
                  idx_tup_fetch AS "Index tuples fetched", n_tup_ins AS "Inserted", n_tup_upd AS "Updated", n_tup_del AS "Deleted",
                  n_live_tup AS "Live tuples", n_dead_tup AS "Dead tuples",
                  last_vacuum AS "Last vacuum", last_autovacuum AS "Last autovacuum",
                  last_analyze AS "Last analyze", last_autoanalyze AS "Last autoanalyze"
           FROM pg_stat_all_tables WHERE schemaname = ? AND relname = ?""",
        listOf(schema, name),
    )

    fun schemaProperties(c: Connection, schema: String): List<Pair<String, String>> = c.rowAsPairs(
        """SELECT n.nspname AS "Name", pg_get_userbyid(n.nspowner) AS "Owner", obj_description(n.oid, 'pg_namespace') AS "Comment"
           FROM pg_namespace n WHERE n.nspname = ?""",
        listOf(schema),
    )

    fun databaseProperties(c: Connection): List<Pair<String, String>> = c.rowAsPairs(
        """SELECT d.datname AS "Name", pg_get_userbyid(d.datdba) AS "Owner", pg_encoding_to_char(d.encoding) AS "Encoding",
                  d.datcollate AS "Collation", pg_size_pretty(pg_database_size(d.datname)) AS "Size",
                  d.datconnlimit AS "Connection limit"
           FROM pg_database d WHERE d.datname = current_database()""",
    )

    fun functionProperties(c: Connection, oid: Long): List<Pair<String, String>> = c.rowAsPairs(
        """SELECT p.proname AS "Name", pg_get_userbyid(p.proowner) AS "Owner", l.lanname AS "Language",
                  pg_get_function_result(p.oid) AS "Returns", pg_get_function_arguments(p.oid) AS "Arguments",
                  CASE p.provolatile WHEN 'i' THEN 'Immutable' WHEN 's' THEN 'Stable' ELSE 'Volatile' END AS "Volatility",
                  obj_description(p.oid, 'pg_proc') AS "Comment"
           FROM pg_proc p JOIN pg_language l ON l.oid = p.prolang WHERE p.oid = ?::oid""",
        listOf(oid),
    )

    fun tableDdl(c: Connection, schema: String, table: String): String =
        DdlBuilder.table(schema, table, columns(c, schema, table), constraints(c, schema, table), indexes(c, schema, table))

    fun viewDdl(c: Connection, schema: String, name: String, materialized: Boolean): String {
        val def = c.query("SELECT pg_get_viewdef(?::regclass, true)", listOf(regclass(schema, name))) { it.getString(1) }.firstOrNull() ?: ""
        val head = if (materialized) "CREATE MATERIALIZED VIEW" else "CREATE OR REPLACE VIEW"
        return "$head ${Sql.qualified(schema, name)} AS\n$def\n"
    }

    fun functionDdl(c: Connection, oid: Long): String =
        c.query("SELECT pg_get_functiondef(?::oid)", listOf(oid)) { it.getString(1) }.firstOrNull() ?: ""

    /** Names used for editor autocomplete: tables/views and their columns outside system schemas. */
    fun completionNames(c: Connection): Set<String> {
        val names = linkedSetOf<String>()
        c.query(
            """SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE c.relkind IN ('r','p','v','m','f') AND n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' LIMIT 3000""",
        ) { it.getString(1) }.let(names::addAll)
        c.query(
            """SELECT DISTINCT a.attname FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE a.attnum > 0 AND NOT a.attisdropped AND c.relkind IN ('r','p','v','m','f')
                 AND n.nspname !~ '^pg_' AND n.nspname <> 'information_schema' LIMIT 5000""",
        ) { it.getString(1) }.let(names::addAll)
        return names
    }

    // ---- Dashboard ----

    fun serverInfo(c: Connection): List<Pair<String, String>> = c.rowAsPairs(
        """SELECT version() AS "Version", current_user AS "Connected as",
                  pg_postmaster_start_time()::text AS "Started",
                  current_setting('max_connections') AS "Max connections",
                  (SELECT count(*) FROM pg_stat_activity) AS "Current sessions"
        """,
    )

    fun databaseSizes(c: Connection): QueryResult = QueryRunner(c).run(
        """SELECT datname AS "Database", pg_size_pretty(pg_database_size(datname)) AS "Size",
                  numbackends AS "Sessions", xact_commit AS "Commits", xact_rollback AS "Rollbacks"
           FROM pg_stat_database WHERE datname IS NOT NULL AND datname NOT LIKE 'template%' ORDER BY pg_database_size(datname) DESC""",
    ).outcomes.filterIsInstance<StatementOutcome.Rows>().firstOrNull()?.result ?: QueryResult(emptyList(), emptyList(), emptyList())

    fun activity(c: Connection): QueryResult = QueryRunner(c).run(
        """SELECT pid AS "PID", usename AS "User", datname AS "Database", state AS "State",
                  (now() - query_start)::text AS "Running for", left(query, 300) AS "Query"
           FROM pg_stat_activity WHERE pid <> pg_backend_pid() ORDER BY query_start NULLS LAST LIMIT 50""",
    ).outcomes.filterIsInstance<StatementOutcome.Rows>().firstOrNull()?.result ?: QueryResult(emptyList(), emptyList(), emptyList())
}
