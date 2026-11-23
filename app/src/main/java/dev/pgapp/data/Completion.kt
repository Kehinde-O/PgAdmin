package dev.pgapp.data

object Csv {
    private fun cell(v: String?): String {
        if (v == null) return ""
        // Neutralise spreadsheet formula injection from untrusted data.
        val safe = if (v.isNotEmpty() && v[0] in "=+-@\t\r" && v.toDoubleOrNull() == null) "'$v" else v
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    fun write(columns: List<String>, rows: List<List<String?>>): String = buildString {
        append(columns.joinToString(",") { cell(it) }).append("\r\n")
        rows.forEach { append(it.joinToString(",") { v -> cell(v) }).append("\r\n") }
    }
}

object SqlKeywords {
    val all: List<String> = (
        "SELECT FROM WHERE GROUP BY ORDER HAVING LIMIT OFFSET JOIN LEFT RIGHT INNER OUTER FULL CROSS ON USING AS AND OR NOT IN IS NULL " +
            "LIKE ILIKE BETWEEN EXISTS CASE WHEN THEN ELSE END DISTINCT UNION ALL INSERT INTO VALUES UPDATE SET DELETE CREATE ALTER DROP " +
            "TABLE VIEW INDEX SCHEMA DATABASE SEQUENCE FUNCTION TRIGGER PRIMARY KEY FOREIGN REFERENCES UNIQUE CHECK DEFAULT CONSTRAINT " +
            "ADD COLUMN RENAME TO TRUNCATE BEGIN COMMIT ROLLBACK EXPLAIN ANALYZE VACUUM WITH RECURSIVE RETURNING COUNT SUM AVG MIN MAX " +
            "COALESCE CAST ASC DESC TRUE FALSE INTEGER BIGINT SMALLINT TEXT VARCHAR BOOLEAN TIMESTAMP TIMESTAMPTZ DATE NUMERIC UUID JSONB JSON"
        ).split(' ')
}

/** Word-at-cursor autocomplete over SQL keywords plus catalog names. */
object Completion {
    data class Suggestion(val start: Int, val end: Int, val options: List<String>)

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'

    fun suggest(text: String, cursor: Int, names: Collection<String>, minChars: Int = 2, max: Int = 12): Suggestion? {
        val pos = cursor.coerceIn(0, text.length)
        var start = pos
        while (start > 0 && isWordChar(text[start - 1])) start--
        val prefix = text.substring(start, pos)
        if (prefix.length < minChars) return null
        val upper = prefix.all { !it.isLowerCase() }
        val fromNames = names.filter { it.startsWith(prefix, ignoreCase = true) && !it.equals(prefix, ignoreCase = true) }.sorted()
        val fromKeywords = SqlKeywords.all
            .filter { it.startsWith(prefix, ignoreCase = true) && !it.equals(prefix, ignoreCase = true) }
            .map { if (upper) it else it.lowercase() }
        val options = (fromNames + fromKeywords).distinct().take(max)
        return if (options.isEmpty()) null else Suggestion(start, pos, options)
    }
}
