package io.easypi.mobile.prism

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrismTransportTest {
    @Test
    fun `protection filters on the phone and sends traffic straight out`() {
        assertEquals(PrismTransport.DIRECT, prismTransport(protection = true, chameleon = false, strict = false))
    }

    @Test
    fun `strict protection routes through the home EasyPi tunnel`() {
        assertEquals(PrismTransport.HOME, prismTransport(protection = true, chameleon = false, strict = true))
    }

    @Test
    fun `chameleon remains an independent explicit transport`() {
        for (strict in listOf(false, true)) {
            assertEquals(PrismTransport.CHAMELEON, prismTransport(protection = false, chameleon = true, strict = strict))
            assertEquals(PrismTransport.CHAMELEON, prismTransport(protection = true, chameleon = true, strict = strict))
        }
    }

    @Test
    fun `a switch action that creates the service forgets switches left by a killed instance`() {
        PrismDnsVpnService.SWITCH_ACTIONS.forEach { action ->
            assertTrue(action, discardsPreviousRunState(firstCommand = true, action = action))
        }
    }

    @Test
    fun `android restoring a killed VPN keeps both switches`() {
        assertFalse(discardsPreviousRunState(firstCommand = true, action = null))
        assertFalse(discardsPreviousRunState(firstCommand = true, action = "android.net.VpnService"))
    }

    @Test
    fun `switches stay independent while the service is running`() {
        assertFalse(discardsPreviousRunState(firstCommand = false, action = PrismDnsVpnService.ACTION_ENABLE_CHAMELEON))
        assertFalse(discardsPreviousRunState(firstCommand = true, action = PrismDnsVpnService.ACTION_RESTART_TRANSPORT))
    }

    @Test
    fun `no transport exists while both switches are off`() {
        assertThrows(IllegalArgumentException::class.java) {
            prismTransport(protection = false, chameleon = false, strict = false)
        }
    }

    @Test
    fun `the LAN path is offered only where the paired EasyPi was just verified`() {
        val direct = "192.168.1.30:9083"
        assertEquals(direct, verifiedDirectAddress(direct, "https://192.168.1.30"))
        assertEquals(direct, verifiedDirectAddress(direct, "https://192.168.1.30:443"))
        // Away from home, or another network that reuses the same subnet.
        assertEquals("", verifiedDirectAddress(direct, null))
        assertEquals("", verifiedDirectAddress(direct, "https://192.168.1.1"))
        assertEquals("", verifiedDirectAddress("", "https://192.168.1.30"))
    }
}
