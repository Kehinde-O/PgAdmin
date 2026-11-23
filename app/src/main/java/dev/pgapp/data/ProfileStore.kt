package dev.pgapp.data

import java.io.File

/** Encrypts/decrypts small secrets. Production impl is Keystore-backed; tests use a fake. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/**
 * Persists server profiles in app-private storage. Passwords are encrypted with [cipher]
 * before touching disk. Writes are atomic (temp file + rename).
 */
class ProfileStore(private val file: File, private val cipher: SecretCipher) {

    @Synchronized
    fun list(): List<ConnectionProfile> = load().map { it.profile }

    /** [password] null keeps the existing stored password (or none for a new profile). */
    @Synchronized
    fun save(profile: ConnectionProfile, password: String?) {
        val problems = profile.validate()
        require(problems.isEmpty()) { problems.joinToString("; ") }
        val items = load().toMutableList()
        val idx = items.indexOfFirst { it.profile.id == profile.id }
        val encrypted = when {
            password != null -> cipher.encrypt(password.toByteArray(Charsets.UTF_8))
            idx >= 0 -> items[idx].encryptedPassword
            else -> null
        }
        val updated = StoredProfile(profile, encrypted)
        if (idx >= 0) items[idx] = updated else items += updated
        write(items)
    }

    @Synchronized
    fun delete(id: String) = write(load().filterNot { it.profile.id == id })

    /** Decrypts the stored password. May throw if the user is not authenticated. */
    @Synchronized
    fun password(id: String): String? =
        load().firstOrNull { it.profile.id == id }?.encryptedPassword
            ?.let { String(cipher.decrypt(it), Charsets.UTF_8) }

    private fun load(): List<StoredProfile> =
        if (file.exists()) ProfileCodec.decode(file.readText()) else emptyList()

    private fun write(items: List<StoredProfile>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(ProfileCodec.encode(items))
        check(tmp.renameTo(file)) { "Could not write profile store" }
    }
}
