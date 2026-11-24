package dev.pgapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PgHostnameVerifierTest {
    @Test fun cnParsed() = assertEquals("db.example.com", PgHostnameVerifier.commonName("CN=db.example.com,O=Acme,C=US"))
    @Test fun cnEscapedComma() = assertEquals("a,b", PgHostnameVerifier.commonName("O=X,CN=a\\,b"))
    @Test fun noCn() = assertNull(PgHostnameVerifier.commonName("O=Acme,C=US"))
}
