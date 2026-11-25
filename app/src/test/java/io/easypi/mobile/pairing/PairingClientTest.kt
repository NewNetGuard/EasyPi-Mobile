package io.easypi.mobile.pairing

import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class PairingClientTest {
    @Test
    fun acceptsHttpsOrigins() {
        assertEquals("https://easypi.local", PairingClient.validateServerUrl("https://easypi.local"))
        assertEquals("https://192.168.1.20:8443", PairingClient.validateServerUrl("https://192.168.1.20:8443/"))
    }

    @Test
    fun rejectsCleartextAndInjectedOrigins() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingClient.validateServerUrl("http://easypi.local")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingClient.validateServerUrl("https://user@easypi.local")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingClient.validateServerUrl("https://easypi.local/untrusted/path")
        }
    }

    @Test
    fun validatesCertificatePins() {
        val pin = "AA:".repeat(31) + "AA"
        assertEquals("aa".repeat(32), PinnedHttps.normalizeFingerprint(pin))
        assertThrows(IllegalArgumentException::class.java) {
            PinnedHttps.normalizeFingerprint("too-short")
        }
    }

    @Test
    fun reusesOnlyTheMatchingPinnedTlsFactory() {
        val first = mock<HttpsURLConnection>()
        val second = mock<HttpsURLConnection>()
        val different = mock<HttpsURLConnection>()

        PinnedHttps.configure(first, "aa".repeat(32))
        PinnedHttps.configure(second, "aa".repeat(32))
        PinnedHttps.configure(different, "bb".repeat(32))

        val firstFactory = argumentCaptor<javax.net.ssl.SSLSocketFactory>()
        val secondFactory = argumentCaptor<javax.net.ssl.SSLSocketFactory>()
        val differentFactory = argumentCaptor<javax.net.ssl.SSLSocketFactory>()
        verify(first).sslSocketFactory = firstFactory.capture()
        verify(second).sslSocketFactory = secondFactory.capture()
        verify(different).sslSocketFactory = differentFactory.capture()
        assertSame(firstFactory.firstValue, secondFactory.firstValue)
        assertNotSame(firstFactory.firstValue, differentFactory.firstValue)
    }
}
