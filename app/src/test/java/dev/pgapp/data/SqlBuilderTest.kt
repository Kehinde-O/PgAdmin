package dev.pgapp.data

import org.junit.Assert.*
import org.junit.Test

class SqlBuilderTest {
    @Test fun identQuoting() {
        assertEquals("\"a\"\"b\"", Sql.ident("a\"b"))
        assertEquals("\"public\".\"my table\"", Sql.qualified("public", "my table"))
        assertEquals("'it''s'", Sql.literal("it's"))
    }

    @Test fun createTable() {
        val sql = SqlBuilder.createTable(
            "public", "users",
            listOf(
                NewColumn("id", "bigserial", primaryKey = true),
                NewColumn("email", "text", notNull = true),
                NewColumn("created", "timestamptz", default = "now()"),
            ),
        )
        assertEquals(
            "CREATE TABLE \"public\".\"users\" (\n" +
                "    \"id\" bigserial,\n" +
                "    \"email\" text NOT NULL,\n" +
                "    \"created\" timestamptz DEFAULT now(),\n" +
                "    CONSTRAINT \"users_pkey\" PRIMARY KEY (\"id\")\n);",
            sql,
        )
    }

    @Test fun alterStatements() {
        assertEquals("ALTER TABLE \"s\".\"t\" DROP COLUMN \"c\";", SqlBuilder.dropColumn("s", "t", "c"))
        assertEquals("ALTER TABLE \"s\".\"t\" ALTER COLUMN \"c\" SET NOT NULL;", SqlBuilder.setNotNull("s", "t", "c", true))
        assertEquals("ALTER TABLE \"s\".\"t\" ALTER COLUMN \"c\" DROP DEFAULT;", SqlBuilder.setDefault("s", "t", "c", " "))
        assertEquals("ALTER TABLE \"s\".\"t\" ALTER COLUMN \"c\" TYPE int USING \"c\"::int;", SqlBuilder.alterType("s", "t", "c", "int"))
        assertEquals("DROP VIEW \"s\".\"v\" CASCADE;", SqlBuilder.dropRelation(RelKind.VIEW, "s", "v", true))
    }

    @Test fun updateCellParameterised() {
        val p = SqlBuilder.updateCell("s", "t", "name", "text", "O'Brien", listOf("id" to "integer"), listOf("7"))
        assertEquals("UPDATE \"s\".\"t\" SET \"name\" = CAST(? AS text) WHERE \"id\" = CAST(? AS integer)", p.sql)
        assertEquals(listOf<String?>("O'Brien", "7"), p.params)
        val n = SqlBuilder.updateCell("s", "t", "name", "text", null, listOf("id" to "integer"), listOf("7"))
        assertTrue(n.sql.contains("\"name\" = NULL"))
        assertEquals(listOf<String?>("7"), n.params)
    }

    @Test fun insertSkipsNulls() {
        val p = SqlBuilder.insertRow("s", "t", listOf(Triple("a", "int", "1"), Triple("b", "text", null)))
        assertEquals("INSERT INTO \"s\".\"t\" (\"a\") VALUES (CAST(? AS int))", p.sql)
        assertEquals(listOf<String?>("1"), p.params)
        assertTrue(SqlBuilder.insertRow("s", "t", emptyList()).sql.endsWith("DEFAULT VALUES"))
    }

    @Test fun deleteRowUsesCompositeKey() {
        val p = SqlBuilder.deleteRow("s", "t", listOf("a" to "int", "b" to "text"), listOf("1", "x"))
        assertEquals("DELETE FROM \"s\".\"t\" WHERE \"a\" = CAST(? AS int) AND \"b\" = CAST(? AS text)", p.sql)
    }

    @Test fun reverseEngineeredDdl() {
        val ddl = DdlBuilder.table(
            "public", "t",
            listOf(ColumnInfo("id", "integer", true, "nextval('x')", true, 1, null), ColumnInfo("n", "text", false, null, false, 2, "name")),
            listOf(ConstraintInfo("t_pkey", "p", "PRIMARY KEY (id)")),
            listOf(IndexInfo("t_pkey", "CREATE UNIQUE INDEX t_pkey ON t (id)", true, true), IndexInfo("t_n_idx", "CREATE INDEX t_n_idx ON public.t USING btree (n)", false, false)),
        )
        assertTrue(ddl.contains("\"id\" integer DEFAULT nextval('x') NOT NULL"))
        assertTrue(ddl.contains("CONSTRAINT \"t_pkey\" PRIMARY KEY (id)"))
        assertTrue(ddl.contains("CREATE INDEX t_n_idx"))
        assertFalse(ddl.contains("CREATE UNIQUE INDEX t_pkey"))
        assertTrue(ddl.contains("COMMENT ON COLUMN \"public\".\"t\".\"n\" IS 'name';"))
    }
}
