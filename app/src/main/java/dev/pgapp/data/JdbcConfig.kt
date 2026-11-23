package dev.pgapp.data

import java.net.URLEncoder
import java.util.Properties

/** Builds the JDBC URL and connection properties for a profile. Pure functions, unit-tested. */
object JdbcConfig {

    fun url(p: ConnectionProfile): String {
        val host = if (':' in p.host && !p.host.startsWith("[")) "[${p.host}]" else p.host
        val db = URLEncoder.encode(p.database, "UTF-8")
        return "jdbc:postgresql://$host:${p.port}/$db"
    }

    fun properties(p: ConnectionProfile, password: String): Properties = Properties().apply {
        setProperty("user", p.user)
        setProperty("password", password)
        setProperty("ApplicationName", "PgApp")
        setProperty("connectTimeout", p.connectTimeoutSec.toString())
        setProperty("loginTimeout", p.connectTimeoutSec.toString())
        setProperty("sslmode", p.sslMode.value)
        p.caCertPath?.let { setProperty("sslrootcert", it) }
        p.clientCertPath?.let { setProperty("sslcert", it) }
        p.clientKeyPath?.let { setProperty("sslkey", it) }
        if (!p.allowWrites) {
            // Enforce read-only on both the driver and the server session.
            setProperty("readOnly", "true")
            setProperty("readOnlyMode", "always")
            setProperty("options", "-c default_transaction_read_only=on")
        }
    }
}
