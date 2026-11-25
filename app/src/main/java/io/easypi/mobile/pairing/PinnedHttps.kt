package io.easypi.mobile.pairing

import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

object PinnedHttps {
    private const val MAX_PINNED_FACTORIES = 8
    private val pinnedHostnameVerifier = HostnameVerifier { _, _ -> true }
    private val pinnedFactories = object : LinkedHashMap<String, SSLSocketFactory>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SSLSocketFactory>): Boolean =
            size > MAX_PINNED_FACTORIES
    }

    fun open(url: String, expectedSha256: String?): HttpsURLConnection {
        val connection = URL(url).openConnection() as HttpsURLConnection
        configure(connection, expectedSha256)
        return connection
    }

    fun configure(connection: HttpsURLConnection, expectedSha256: String?) {
        if (!expectedSha256.isNullOrBlank()) {
            val expected = normalizeFingerprint(expectedSha256)
            connection.sslSocketFactory = pinnedSocketFactory(expected)
            // Mobile Link uses a per-installation public hostname while TLS is
            // terminated by the exact EasyPi certificate pinned in the QR code.
            // The cryptographic certificate pin replaces hostname-based CA trust.
            connection.hostnameVerifier = pinnedHostnameVerifier
        }
    }

    private fun pinnedSocketFactory(expected: String): SSLSocketFactory = synchronized(pinnedFactories) {
        pinnedFactories[expected] ?: run {
            val expectedBytes = expected.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val trustManager = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    val leaf = chain.firstOrNull() ?: error("EasyPi did not provide a TLS certificate")
                    leaf.checkValidity()
                    val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                    check(MessageDigest.isEqual(actual, expectedBytes)) {
                        "EasyPi TLS certificate does not match the QR code"
                    }
                }
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trustManager), SecureRandom())
            context.socketFactory.also { pinnedFactories[expected] = it }
        }
    }

    fun normalizeFingerprint(value: String): String {
        val normalized = value.lowercase().replace(Regex("[^0-9a-f]"), "")
        require(normalized.length == 64) { "TLS certificate fingerprint is invalid" }
        return normalized
    }
}
