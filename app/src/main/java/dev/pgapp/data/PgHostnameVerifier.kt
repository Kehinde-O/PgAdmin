package dev.pgapp.data

import java.net.InetAddress
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession

/**
 * Hostname check for sslmode=verify-full. pgjdbc's built-in verifier needs javax.naming.ldap, which
 * does not exist on Android and crashes with SQLSTATE 99999. This one follows RFC 6125: match the
 * subjectAltName (DNS or IP); only fall back to the CN when the certificate has no SAN of that kind.
 * Instantiated by pgjdbc by class name, so it needs a public no-arg constructor.
 */
class PgHostnameVerifier : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean = try {
        matches(hostname, session.peerCertificates.first() as X509Certificate)
    } catch (_: Exception) {
        false
    }

    companion object {
        fun matches(rawHost: String, cert: X509Certificate): Boolean {
            val host = rawHost.trim('[', ']').trimEnd('.').lowercase()
            val isIp = host.contains(':') || host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
            val sans = cert.subjectAlternativeNames.orEmpty()
            if (isIp) {
                val want = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
                return sans.any { it[0] == 7 && runCatching { InetAddress.getByName(it[1] as String) == want }.getOrDefault(false) }
            }
            val dns = sans.filter { it[0] == 2 }.map { (it[1] as String).lowercase().trimEnd('.') }
            if (dns.isNotEmpty()) return dns.any { nameMatches(host, it) }
            val cn = commonName(cert.subjectX500Principal.getName("RFC2253")) ?: return false
            return nameMatches(host, cn.lowercase())
        }

        private fun nameMatches(host: String, pattern: String): Boolean {
            if (!pattern.startsWith("*.")) return host == pattern
            val suffix = pattern.substring(1) // ".example.com"
            val first = host.indexOf('.')
            return first > 0 && host.substring(first) == suffix && suffix.count { it == '.' } >= 2
        }

        /** Extracts the CN value from an RFC 2253 DN, honouring backslash escapes. */
        fun commonName(dn: String): String? {
            var i = 0
            while (i < dn.length) {
                val sb = StringBuilder()
                while (i < dn.length && dn[i] != ',') {
                    if (dn[i] == '\\' && i + 1 < dn.length) i++
                    sb.append(dn[i]); i++
                }
                i++
                val part = sb.toString()
                if (part.startsWith("CN=", ignoreCase = true)) return part.substring(3)
            }
            return null
        }
    }
}
