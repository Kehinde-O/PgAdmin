package dev.pgapp.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pgapp.data.ConnectedServer
import dev.pgapp.data.ConnectionManager
import dev.pgapp.data.ConnectionProfile
import dev.pgapp.data.ProfileStore
import dev.pgapp.security.KeystoreCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class ServersViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProfileStore(File(app.filesDir, "profiles.json"), KeystoreCipher())
    private val manager = ConnectionManager()

    private val _profiles = MutableStateFlow(safeList())
    val profiles: StateFlow<List<ConnectionProfile>> = _profiles

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private val _connected = MutableStateFlow<ConnectedServer?>(null)
    val connected: StateFlow<ConnectedServer?> = _connected

    fun clearStatus() { _status.value = null }

    /** [password] null keeps the stored one. Call only after the user authenticated. */
    fun save(profile: ConnectionProfile, password: String?) {
        runCatching { store.save(profile, password) }
            .onSuccess { _profiles.value = safeList() }
            .onFailure { _status.value = "Could not save: ${it.message ?: it.javaClass.simpleName}" }
    }

    fun delete(id: String) {
        if (_connected.value?.profile?.id == id) disconnect()
        runCatching { store.delete(id) }
        _profiles.value = safeList()
    }

    /** Stored password for [id]; null if none or unreadable. Requires recent authentication. */
    fun storedPassword(id: String): String? =
        runCatching { store.password(id) }.getOrElse {
            _status.value = "Could not read saved password. Authenticate and try again."
            null
        }

    suspend fun test(profile: ConnectionProfile, password: String): String =
        manager.test(profile, password).fold({ "Success: $it" }, { "Failed: ${it.message}" })

    fun connect(profile: ConnectionProfile, password: String) {
        viewModelScope.launch {
            _status.value = "Connecting to ${profile.name}..."
            manager.connect(profile, password).fold(
                onSuccess = { server ->
                    disconnect()
                    _connected.value = server
                    _status.value = "Connected to ${profile.name} (PostgreSQL ${server.version})"
                },
                onFailure = { _status.value = "Failed: ${it.message}" },
            )
        }
    }

    fun disconnect() {
        val old = _connected.value ?: return
        _connected.value = null
        viewModelScope.launch { manager.close(old) }
    }

    override fun onCleared() {
        _connected.value?.let { runCatching { it.connection.close() } }
    }

    private fun safeList() = runCatching { store.list() }.getOrDefault(emptyList())
}
