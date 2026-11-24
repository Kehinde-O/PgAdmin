package dev.pgapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException

/** Human-readable message for any failure; never includes credentials. */
fun userMessage(t: Throwable): String = when (t) {
    is ConnectionFailure -> t.message ?: "Connection failed"
    is SQLException -> (t.message ?: "SQL error").trim()
    else -> t.message ?: t.javaClass.simpleName
}

/**
 * One connected server. Holds the password in memory only for the life of the session, a pool of
 * shared per-database connections (used by the object browser), and opens dedicated connections
 * for query tools. Write mode is a session-wide switch; changing it reconnects everything.
 */
class ServerSession(
    val profile: ConnectionProfile,
    private val password: String,
    private val manager: ConnectionManager = ConnectionManager(),
) {
    @Volatile var writes: Boolean = profile.allowWrites
        private set

    /** Bumped when write mode changes so tabs know to reopen their connections. */
    @Volatile var generation: Int = 0
        private set

    private val shared = HashMap<String, Connection>()
    private val mutex = Mutex()

    private fun profileFor(db: String) = profile.copy(database = db, allowWrites = writes)

    /** A new dedicated connection; the caller owns and must close it. */
    suspend fun open(db: String): Connection = manager.connect(profileFor(db), password).getOrThrow().connection

    /** Shared connection for browsing [db]; reopened if it died. */
    suspend fun shared(db: String): Connection = mutex.withLock {
        val existing = shared[db]
        if (existing != null && withContext(Dispatchers.IO) { runCatching { !existing.isClosed && existing.isValid(3) }.getOrDefault(false) }) {
            return@withLock existing
        }
        existing?.let { runCatching { it.close() } }
        val fresh = open(db)
        shared[db] = fresh
        fresh
    }

    suspend fun setWrites(on: Boolean) = mutex.withLock {
        writes = on
        generation++
        withContext(Dispatchers.IO) { shared.values.forEach { runCatching { it.close() } } }
        shared.clear()
    }

    suspend fun close() = mutex.withLock {
        withContext(Dispatchers.IO) { shared.values.forEach { runCatching { it.close() } } }
        shared.clear()
    }
}
