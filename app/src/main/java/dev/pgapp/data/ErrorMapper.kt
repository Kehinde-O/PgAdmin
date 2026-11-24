package dev.pgapp.data

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.sql.SQLException
import javax.net.ssl.SSLException

enum class FailureKind { HOST_NOT_FOUND, REFUSED, TIMEOUT, TLS, AUTH, DATABASE_MISSING, OTHER }

/** User-facing failure. Messages never include passwords, URLs or raw driver text. */
class ConnectionFailure(val kind: FailureKind, message: String, cause: Throwable? = null) :
    Exception(message, cause)

object ErrorMapper {
    /** Maps [t] and appends a technical detail chain (class: message), with [secrets] scrubbed. */
    fun map(t: Throwable, secrets: List<String> = emptyList()): ConnectionFailure {
        val base = classify(t)
        if (t is ConnectionFailure && t.cause == null) return t
        val detail = describe(t, secrets)
        return if (detail.isBlank()) base else ConnectionFailure(base.kind, base.message + "\n\nDetails:\n" + detail, t)
    }

    fun describe(t: Throwable, secrets: List<String> = emptyList()): String =
        generateSequence(t) { it.cause }.take(6).distinctBy { it.javaClass to it.message }.joinToString("\n") { e ->
            var line = "• " + e.javaClass.name.substringAfterLast('.') + (e.message?.let { ": " + it.lineSequence().first().take(300) } ?: "")
            (e as? SQLException)?.sqlState?.let { line += " [SQLSTATE $it]" }
            secrets.filter { it.length >= 3 }.forEach { line = line.replace(it, "***") }
            line
        }

    private fun classify(t: Throwable): ConnectionFailure {
        if (t is ConnectionFailure) return t
        val chain = generateSequence(t) { it.cause }.take(10).toList()

        chain.filterIsInstance<SQLException>().firstOrNull { it.sqlState != null }?.let { e ->
            when (e.sqlState) {
                "28P01", "28000" ->
                    return ConnectionFailure(FailureKind.AUTH, "Authentication failed (check user, password and pg_hba.conf)", t)
                "3D000" ->
                    return ConnectionFailure(FailureKind.DATABASE_MISSING, "Database does not exist", t)
            }
        }
        for (c in chain) {
            when (c) {
                is UnknownHostException -> return ConnectionFailure(FailureKind.HOST_NOT_FOUND, "Host not found", t)
                is SocketTimeoutException -> return ConnectionFailure(FailureKind.TIMEOUT, "Connection timed out", t)
                is ConnectException -> return ConnectionFailure(FailureKind.REFUSED, "Connection refused (is the server reachable on that port?)", t)
                is CertPathValidatorException, is CertificateException, is SSLException ->
                    return ConnectionFailure(FailureKind.TLS, "TLS error: server certificate could not be verified or the server does not support TLS", t)
            }
        }
        val state = chain.filterIsInstance<SQLException>().firstOrNull()?.sqlState
        return ConnectionFailure(FailureKind.OTHER, "Connection failed" + (state?.let { " (SQLSTATE $it)" } ?: ""), t)
    }
}
