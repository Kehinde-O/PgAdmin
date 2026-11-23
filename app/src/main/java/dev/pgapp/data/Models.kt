package dev.pgapp.data

enum class RelKind(val code: String, val label: String) {
    TABLE("r", "Table"),
    PARTITIONED("p", "Partitioned table"),
    VIEW("v", "View"),
    MATVIEW("m", "Materialized view"),
    FOREIGN("f", "Foreign table"),
    SEQUENCE("S", "Sequence");

    val isTableLike get() = this == TABLE || this == PARTITIONED || this == FOREIGN
    val hasRows get() = this != SEQUENCE

    companion object {
        fun from(code: String?) = entries.firstOrNull { it.code == code } ?: TABLE
    }
}

data class Relation(val schema: String, val name: String, val kind: RelKind)

data class FunctionInfo(
    val oid: Long,
    val schema: String,
    val name: String,
    val args: String,
    val returns: String,
    val language: String,
) {
    val signature get() = "$name($args)"
}

data class ColumnInfo(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val default: String?,
    val isPk: Boolean,
    val position: Int,
    val comment: String?,
)

data class ConstraintInfo(val name: String, val type: String, val definition: String) {
    val typeLabel get() = when (type) {
        "p" -> "Primary key"
        "f" -> "Foreign key"
        "u" -> "Unique"
        "c" -> "Check"
        "x" -> "Exclusion"
        else -> type
    }
}

data class IndexInfo(val name: String, val definition: String, val unique: Boolean, val primary: Boolean)

/** One result set. Cells are the driver's string form; null = SQL NULL. */
data class QueryResult(
    val columns: List<String>,
    val columnTypes: List<String>,
    val rows: List<List<String?>>,
    val truncated: Boolean = false,
)

sealed class StatementOutcome {
    data class Rows(val result: QueryResult) : StatementOutcome()
    data class Update(val count: Int) : StatementOutcome()
}

data class RunResult(
    val outcomes: List<StatementOutcome>,
    val notices: List<String>,
    val elapsedMs: Long,
)
