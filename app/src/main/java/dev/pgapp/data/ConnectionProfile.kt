package dev.pgapp.data

/**
 * TLS modes. DISABLE and REQUIRE are available but flagged insecure in the UI:
 * REQUIRE encrypts but does not verify the server certificate (MITM possible).
 */
enum class SslMode(val value: String, val verifiesServer: Boolean) {
    DISABLE("disable", false),
    REQUIRE("require", false),
    VERIFY_CA("verify-ca", true),
    VERIFY_FULL("verify-full", true);

    companion object {
        /** Unknown or missing values fall back to the safest mode, never a weaker one. */
        fun parse(value: String?): SslMode = entries.firstOrNull { it.value == value } ?: VERIFY_FULL
    }
}

/** Saved server registration. Never holds the password; that lives encrypted in [ProfileStore]. */
data class ConnectionProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 5432,
    val database: String = "postgres",
    val user: String,
    val sslMode: SslMode = SslMode.VERIFY_FULL,
    /** Path (inside app-private storage) of a PEM CA certificate to trust, or null for system CAs. */
    val caCertPath: String? = null,
    val clientCertPath: String? = null,
    val clientKeyPath: String? = null,
    /** Default false: sessions are read-only until explicitly enabled per connection. */
    val allowWrites: Boolean = false,
    val connectTimeoutSec: Int = 10,
) {
    /** Human-readable problems; empty list means valid. */
    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("Name is required")
        if (host.isBlank()) add("Host is required")
        if (port !in 1..65535) add("Port must be between 1 and 65535")
        if (database.isBlank()) add("Database is required")
        if (user.isBlank()) add("User is required")
        if (connectTimeoutSec !in 1..120) add("Timeout must be between 1 and 120 seconds")
        if ((clientCertPath == null) != (clientKeyPath == null)) {
            add("Client certificate and key must be set together")
        }
    }
}
