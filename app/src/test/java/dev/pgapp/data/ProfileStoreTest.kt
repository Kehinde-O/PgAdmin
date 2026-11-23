package dev.pgapp.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProfileStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Reversible stand-in for the Keystore cipher. */
    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = "ENC".toByteArray() + plain.reversedArray()
        override fun decrypt(blob: ByteArray) = blob.copyOfRange(3, blob.size).reversedArray()
    }

    private fun store(file: File = File(tmp.root, "profiles.json")) = ProfileStore(file, FakeCipher())
    private val p = ConnectionProfile(id = "a", name = "Prod", host = "h", user = "u", caCertPath = "/ca.crt")

    @Test fun roundTripsProfileAndPassword() {
        val s = store()
        s.save(p, "s3cret")
        assertEquals(listOf(p), store().list())
        assertEquals("s3cret", store().password("a"))
    }

    @Test fun passwordNeverStoredInPlaintext() {
        val file = File(tmp.root, "profiles.json")
        store(file).save(p, "s3cret-plaintext")
        assertFalse(file.readText().contains("s3cret-plaintext"))
    }

    @Test fun nullPasswordKeepsExisting() {
        val s = store()
        s.save(p, "keepme")
        s.save(p.copy(name = "Renamed"), null)
        assertEquals("Renamed", s.list().single().name)
        assertEquals("keepme", s.password("a"))
    }

    @Test fun deleteRemoves() {
        val s = store()
        s.save(p, "x")
        s.delete("a")
        assertTrue(s.list().isEmpty())
        assertNull(s.password("a"))
    }

    @Test fun rejectsInvalidProfile() {
        assertThrows(IllegalArgumentException::class.java) { store().save(p.copy(host = ""), "x") }
    }

    @Test fun codecDefaultsAreSafeForMissingFields() {
        val json = """{"version":1,"profiles":[{"id":"z","name":"n","host":"h","user":"u"}]}"""
        val d = ProfileCodec.decode(json).single().profile
        assertEquals(SslMode.VERIFY_FULL, d.sslMode)
        assertFalse(d.allowWrites)
        assertEquals(5432, d.port)
    }
}
