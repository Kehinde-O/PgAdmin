package dev.pgapp.data

import org.junit.Assert.*
import org.junit.Test

class CompletionTest {
    @Test fun suggestsNamesBeforeKeywords() {
        val s = Completion.suggest("select * from us", 16, listOf("users", "orders"))!!
        assertEquals(14, s.start)
        assertEquals("users", s.options.first())
    }

    @Test fun matchesKeywordCase() {
        assertEquals("SELECT", Completion.suggest("SEL", 3, emptyList())!!.options.first())
        assertEquals("select", Completion.suggest("sel", 3, emptyList())!!.options.first())
    }

    @Test fun shortPrefixOrNoMatch() {
        assertNull(Completion.suggest("s", 1, emptyList()))
        assertNull(Completion.suggest("zzzz", 4, listOf("users")))
    }

    @Test fun csvEscapesAndNeutralisesFormulas() {
        val csv = Csv.write(listOf("a", "b"), listOf(listOf("x,y", "say \"hi\""), listOf("=cmd()", null), listOf("-5", "ok")))
        assertEquals("a,b\r\n\"x,y\",\"say \"\"hi\"\"\"\r\n'=cmd(),\r\n-5,ok\r\n", csv)
    }
}
