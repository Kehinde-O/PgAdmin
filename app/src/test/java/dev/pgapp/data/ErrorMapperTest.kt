package dev.pgapp.data

import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.sql.SQLException
import javax.net.ssl.SSLHandshakeException

class ErrorMapperTest {
    private fun kind(t: Throwable) = ErrorMapper.map(t).kind

    @Test fun auth() = assertEquals(FailureKind.AUTH, kind(SQLException("x", "28P01")))
    @Test fun missingDb() = assertEquals(FailureKind.DATABASE_MISSING, kind(SQLException("x", "3D000")))
    @Test fun dns() = assertEquals(FailureKind.HOST_NOT_FOUND, kind(SQLException("x", "08001", UnknownHostException())))
    @Test fun refused() = assertEquals(FailureKind.REFUSED, kind(SQLException("x", "08001", ConnectException())))
    @Test fun timeout() = assertEquals(FailureKind.TIMEOUT, kind(SQLException("x", "08001", SocketTimeoutException())))
    @Test fun tls() = assertEquals(FailureKind.TLS, kind(SQLException("x", "08006", SSLHandshakeException("bad cert"))))

    @Test fun otherIncludesSqlStateOnly() {
        val f = ErrorMapper.map(SQLException("password=hunter2 leaked", "XX000"), listOf("hunter2"))
        assertEquals(FailureKind.OTHER, f.kind)
        assertFalse(f.message!!.contains("hunter2"))
        assertTrue(f.message!!.contains("XX000"))
        assertTrue(f.message!!.contains("SQLException"))
    }

    @Test fun idempotent() {
        val f = ConnectionFailure(FailureKind.TIMEOUT, "t")
        assertSame(f, ErrorMapper.map(f))
    }
}
