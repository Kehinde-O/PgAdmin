package dev.pgapp.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection

/** An open connection plus the server version string. */
class ConnectedServer(val profile: ConnectionProfile, val version: String, val connection: Connection)

class ConnectionManager(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {

    /** Opens a connection off the main thread. Failures are mapped to [ConnectionFailure]. */
    suspend fun connect(profile: ConnectionProfile, password: String): Result<ConnectedServer> =
        withContext(dispatcher) {
            runCatching {
                val problems = profile.validate()
                if (problems.isNotEmpty()) throw ConnectionFailure(FailureKind.OTHER, problems.first())
                // Use the driver directly: DriverManager service discovery is unreliable on Android.
                val conn = org.postgresql.Driver().connect(JdbcConfig.url(profile), JdbcConfig.properties(profile, password))
                    ?: throw ConnectionFailure(FailureKind.OTHER, "Driver rejected the connection URL")
                try {
                    if (!profile.allowWrites) conn.isReadOnly = true
                    ConnectedServer(profile, conn.metaData.databaseProductVersion ?: "unknown", conn)
                } catch (e: Throwable) {
                    runCatching { conn.close() }
                    throw e
                }
            }.recoverCatching { throw ErrorMapper.map(it, listOf(password)) }
        }

    /** Connects, reports the server version, then closes. */
    suspend fun test(profile: ConnectionProfile, password: String): Result<String> =
        connect(profile, password).map { server ->
            server.connection.close()
            "PostgreSQL ${server.version}"
        }

    suspend fun close(server: ConnectedServer) = withContext(dispatcher) {
        runCatching { server.connection.close() }
        Unit
    }
}
