package dev.pgapp.data

object Sql {
    fun ident(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    fun qualified(schema: String, name: String) = ident(schema) + "." + ident(name)
    fun literal(s: String) = "'" + s.replace("'", "''") + "'"
}

data class NewColumn(
    val name: String,
    val type: String,
    val notNull: Boolean = false,
    val primaryKey: Boolean = false,
    val default: String? = null,
)

/** SQL text plus positional parameters, for statements that carry user values. */
data class ParamSql(val sql: String, val params: List<String?>)

/** Pure DDL/DML generators. Identifiers are always quoted; type names and defaults are trusted SQL. */
object SqlBuilder {

    private fun colDef(c: NewColumn) = buildString {
        append(Sql.ident(c.name)).append(' ').append(c.type.trim())
        if (!c.default.isNullOrBlank()) append(" DEFAULT ").append(c.default.trim())
        if (c.notNull && !c.primaryKey) append(" NOT NULL")
    }

    fun createTable(schema: String, table: String, cols: List<NewColumn>): String {
        val lines = cols.map { "    " + colDef(it) }.toMutableList()
        val pks = cols.filter { it.primaryKey }
        if (pks.isNotEmpty()) {
            lines += "    CONSTRAINT ${Sql.ident(table + "_pkey")} PRIMARY KEY (${pks.joinToString(", ") { Sql.ident(it.name) }})"
        }
        return "CREATE TABLE ${Sql.qualified(schema, table)} (\n${lines.joinToString(",\n")}\n);"
    }

    fun addColumn(schema: String, table: String, c: NewColumn) =
        "ALTER TABLE ${Sql.qualified(schema, table)} ADD COLUMN ${colDef(c)};"

    fun dropColumn(schema: String, table: String, col: String) =
        "ALTER TABLE ${Sql.qualified(schema, table)} DROP COLUMN ${Sql.ident(col)};"

    fun renameColumn(schema: String, table: String, old: String, new: String) =
        "ALTER TABLE ${Sql.qualified(schema, table)} RENAME COLUMN ${Sql.ident(old)} TO ${Sql.ident(new)};"

    fun alterType(schema: String, table: String, col: String, type: String) =
        "ALTER TABLE ${Sql.qualified(schema, table)} ALTER COLUMN ${Sql.ident(col)} TYPE ${type.trim()} " +
            "USING ${Sql.ident(col)}::${type.trim()};"

    fun setNotNull(schema: String, table: String, col: String, on: Boolean) =
        "ALTER TABLE ${Sql.qualified(schema, table)} ALTER COLUMN ${Sql.ident(col)} ${if (on) "SET" else "DROP"} NOT NULL;"

    fun setDefault(schema: String, table: String, col: String, default: String?) =
        "ALTER TABLE ${Sql.qualified(schema, table)} ALTER COLUMN ${Sql.ident(col)} " +
            if (default.isNullOrBlank()) "DROP DEFAULT;" else "SET DEFAULT ${default.trim()};"

    fun renameTable(schema: String, table: String, new: String) =
        "ALTER TABLE ${Sql.qualified(schema, table)} RENAME TO ${Sql.ident(new)};"

    fun truncate(schema: String, table: String) = "TRUNCATE TABLE ${Sql.qualified(schema, table)};"

    fun dropRelation(kind: RelKind, schema: String, name: String, cascade: Boolean = false): String {
        val what = when (kind) {
            RelKind.VIEW -> "VIEW"
            RelKind.MATVIEW -> "MATERIALIZED VIEW"
            RelKind.SEQUENCE -> "SEQUENCE"
            RelKind.FOREIGN -> "FOREIGN TABLE"
            else -> "TABLE"
        }
        return "DROP $what ${Sql.qualified(schema, name)}${if (cascade) " CASCADE" else ""};"
    }

    fun createSchema(name: String) = "CREATE SCHEMA ${Sql.ident(name)};"
    fun dropSchema(name: String, cascade: Boolean) = "DROP SCHEMA ${Sql.ident(name)}${if (cascade) " CASCADE" else ""};"
    fun createDatabase(name: String) = "CREATE DATABASE ${Sql.ident(name)};"
    fun dropIndex(schema: String, name: String) = "DROP INDEX ${Sql.qualified(schema, name)};"

    fun dropConstraint(schema: String, table: String, name: String) =
        "ALTER TABLE ${Sql.qualified(schema, table)} DROP CONSTRAINT ${Sql.ident(name)};"

    // ---- Row editing (parameterised; values are cast back to the column type) ----

    private fun cast(type: String) = "CAST(? AS ${type.trim()})"

    /** [pk] = list of (column, type). Returns params in order: new value, then pk values. */
    fun updateCell(
        schema: String, table: String, col: String, colType: String, newValue: String?,
        pk: List<Pair<String, String>>, pkValues: List<String?>,
    ): ParamSql {
        val set = if (newValue == null) "${Sql.ident(col)} = NULL" else "${Sql.ident(col)} = ${cast(colType)}"
        val where = pk.joinToString(" AND ") { (c, t) -> "${Sql.ident(c)} = ${cast(t)}" }
        val params = buildList { if (newValue != null) add(newValue); addAll(pkValues) }
        return ParamSql("UPDATE ${Sql.qualified(schema, table)} SET $set WHERE $where", params)
    }

    fun deleteRow(schema: String, table: String, pk: List<Pair<String, String>>, pkValues: List<String?>): ParamSql {
        val where = pk.joinToString(" AND ") { (c, t) -> "${Sql.ident(c)} = ${cast(t)}" }
        return ParamSql("DELETE FROM ${Sql.qualified(schema, table)} WHERE $where", pkValues)
    }

    /** Columns with a null value are omitted so server defaults apply. */
    fun insertRow(schema: String, table: String, values: List<Triple<String, String, String?>>): ParamSql {
        val used = values.filter { it.third != null }
        if (used.isEmpty()) return ParamSql("INSERT INTO ${Sql.qualified(schema, table)} DEFAULT VALUES", emptyList())
        return ParamSql(
            "INSERT INTO ${Sql.qualified(schema, table)} (${used.joinToString(", ") { Sql.ident(it.first) }}) " +
                "VALUES (${used.joinToString(", ") { cast(it.second) }})",
            used.map { it.third },
        )
    }
}

/** Reverse-engineers CREATE statements from catalog data. */
object DdlBuilder {
    fun table(
        schema: String, table: String,
        cols: List<ColumnInfo>, constraints: List<ConstraintInfo>, indexes: List<IndexInfo>,
    ): String = buildString {
        val lines = cols.map { c ->
            buildString {
                append("    ").append(Sql.ident(c.name)).append(' ').append(c.type)
                if (c.default != null) append(" DEFAULT ").append(c.default)
                if (c.notNull) append(" NOT NULL")
            }
        } + constraints.map { "    CONSTRAINT ${Sql.ident(it.name)} ${it.definition}" }
        append("CREATE TABLE ${Sql.qualified(schema, table)} (\n")
        append(lines.joinToString(",\n"))
        append("\n);\n")
        val constraintNames = constraints.map { it.name }.toSet()
        indexes.filter { it.name !in constraintNames && !it.primary }.forEach { append('\n').append(it.definition).append(";\n") }
        cols.filter { !it.comment.isNullOrBlank() }.forEach {
            append("\nCOMMENT ON COLUMN ${Sql.qualified(schema, table)}.${Sql.ident(it.name)} IS ${Sql.literal(it.comment!!)};")
        }
    }.trimEnd() + "\n"
}
