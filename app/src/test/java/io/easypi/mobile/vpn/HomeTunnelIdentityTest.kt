package io.easypi.mobile.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTunnelIdentityTest {
    @Test
    fun enrollmentStatementIsDomainSeparatedAndDeviceBound() {
        val statement = HomeTunnelIdentity.enrollmentStatement(
            "12345678-1234-1234-1234-123456789abc",
            "public-key",
        ).toString(Charsets.US_ASCII)

        assertEquals(
            "easypi/home-tunnel/enroll/v1\n12345678-1234-1234-1234-123456789abc\npublic-key",
            statement,
        )
    }
}
