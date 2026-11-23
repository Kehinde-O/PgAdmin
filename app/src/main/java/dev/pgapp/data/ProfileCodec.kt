package dev.pgapp.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** A profile plus its encrypted password blob (null = no password stored). */
data class StoredProfile(val profile: ConnectionProfile, val encryptedPassword: ByteArray?) {
    override fun equals(other: Any?) = other is StoredProfile &&
        profile == other.profile && encryptedPassword.contentEquals(other.encryptedPassword)

    override fun hashCode() = 31 * profile.hashCode() + (encryptedPassword?.contentHashCode() ?: 0)
}

/** JSON (de)serialization of the profile file. Passwords only ever appear as ciphertext. */
object ProfileCodec {
    private const val VERSION = 1

    fun encode(items: List<StoredProfile>): String {
        val arr = JSONArray()
        items.forEach { (p, pw) ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("host", p.host)
                    put("port", p.port)
                    put("database", p.database)
                    put("user", p.user)
                    put("sslMode", p.sslMode.value)
                    put("caCertPath", p.caCertPath ?: JSONObject.NULL)
                    put("clientCertPath", p.clientCertPath ?: JSONObject.NULL)
                    put("clientKeyPath", p.clientKeyPath ?: JSONObject.NULL)
                    put("allowWrites", p.allowWrites)
                    put("connectTimeoutSec", p.connectTimeoutSec)
                    put("pw", pw?.let { Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL)
                },
            )
        }
        return JSONObject().put("version", VERSION).put("profiles", arr).toString()
    }

    fun decode(json: String): List<StoredProfile> {
        val arr = JSONObject(json).optJSONArray("profiles") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            StoredProfile(
                ConnectionProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    port = o.optInt("port", 5432),
                    database = o.optString("database", "postgres"),
                    user = o.getString("user"),
                    sslMode = SslMode.parse(o.optString("sslMode")),
                    caCertPath = o.optNullable("caCertPath"),
                    clientCertPath = o.optNullable("clientCertPath"),
                    clientKeyPath = o.optNullable("clientKeyPath"),
                    allowWrites = o.optBoolean("allowWrites", false),
                    connectTimeoutSec = o.optInt("connectTimeoutSec", 10),
                ),
                o.optNullable("pw")?.let { Base64.getDecoder().decode(it) },
            )
        }
    }

    private fun JSONObject.optNullable(key: String): String? =
        if (isNull(key)) null else getString(key)
}
