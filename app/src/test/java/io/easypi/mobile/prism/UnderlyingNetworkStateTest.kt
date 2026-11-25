package io.easypi.mobile.prism

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnderlyingNetworkStateTest {
    @Test
    fun newDefaultNetworkRequestsRestart() {
        val state = UnderlyingNetworkState()
        state.seed(10L, "wlan0|192.168.88.28")

        assertFalse(state.update(10L, "wlan0|192.168.88.28"))
        assertTrue(state.update(12L, "wlan0|10.62.210.11"))
    }

    @Test
    fun addressChangeOnSameNetworkRequestsRestart() {
        val state = UnderlyingNetworkState()
        state.seed(10L, "wlan0|192.168.88.28")

        assertTrue(state.update(10L, "wlan0|192.168.88.29"))
        assertFalse(state.update(10L, "wlan0|192.168.88.29"))
    }

    @Test
    fun lossOfCurrentNetworkRequestsRestart() {
        val state = UnderlyingNetworkState()
        state.seed(10L, "wlan0|192.168.88.28")

        assertTrue(state.update(null, null))
        assertFalse(state.update(null, null))
        assertTrue(state.update(12L, "wlan0|10.62.210.11"))
    }
}
