package dev.pgapp.data

import org.junit.Assert.*
import org.junit.Test

class JdbcConfigTest {
    private val base = ConnectionProfile(id = "1", name = "n", host = "db.example.com", user = "u")

    @Test fun urlBasic() =
        assertEquals("jdbc:postgresql://db.example.com:5432/postgres", JdbcConfig.url(base))

    @Test fun urlBracketsIpv6() =
        assertEquals("jdbc:postgresql://[::1]:5433/postgres", JdbcConfig.url(base.copy(host = "::1", port = 5433)))

    @Test fun urlEncodesDatabaseName() =
        assertEquals("jdbc:postgresql://db.example.com:5432/my+db%3Fx", JdbcConfig.url(base.copy(database = "my db?x")))

    @Test fun defaultsAreSecure() {
        val p = JdbcConfig.properties(base, "pw")
        assertEquals("verify-full", p.getProperty("sslmode"))
        assertEquals("true", p.getProperty("readOnly"))
        assertEquals("always", p.getProperty("readOnlyMode"))
        assertTrue(p.getProperty("options").contains("default_transaction_read_only=on"))
        assertEquals("pw", p.getProperty("password"))
    }

    @Test fun writesEnabledDropsReadOnly() {
        val p = JdbcConfig.properties(base.copy(allowWrites = true), "pw")
        assertNull(p.getProperty("readOnly"))
        assertNull(p.getProperty("options"))
    }

    @Test fun certPathsPassedThrough() {
        val p = JdbcConfig.properties(base.copy(caCertPath = "/ca.crt", clientCertPath = "/c.crt", clientKeyPath = "/c.pk8"), "")
        assertEquals("/ca.crt", p.getProperty("sslrootcert"))
        assertEquals("/c.crt", p.getProperty("sslcert"))
        assertEquals("/c.pk8", p.getProperty("sslkey"))
    }

    @Test fun unknownSslModeFallsBackToVerifyFull() {
        assertEquals(SslMode.VERIFY_FULL, SslMode.parse("garbage"))
        assertEquals(SslMode.VERIFY_FULL, SslMode.parse(null))
        assertEquals(SslMode.REQUIRE, SslMode.parse("require"))
    }

    @Test fun validation() {
        assertTrue(base.validate().isEmpty())
        assertFalse(base.copy(name = " ").validate().isEmpty())
        assertFalse(base.copy(port = 0).validate().isEmpty())
        assertFalse(base.copy(port = 70000).validate().isEmpty())
        assertFalse(base.copy(clientCertPath = "/c.crt").validate().isEmpty())
    }
}
