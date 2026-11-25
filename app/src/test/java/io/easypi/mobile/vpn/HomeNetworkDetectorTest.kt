package io.easypi.mobile.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeNetworkDetectorTest {
    @Test fun pinnedHomeOriginIsTriedBeforeTheDhcpGateway() {
        assertEquals(
            linkedSetOf("https://192.168.50.10", "https://192.168.50.1"),
            HomeNetworkDetector.candidateOrigins(
                "https://192.168.50.10",
                listOf("https://192.168.50.1"),
            ),
        )
    }

    @Test fun duplicateHomeAndGatewayOriginsAreCollapsed() {
        assertEquals(
            setOf("https://192.168.50.10"),
            HomeNetworkDetector.candidateOrigins(
                "https://192.168.50.10",
                listOf("https://192.168.50.10"),
            ),
        )
    }
}
