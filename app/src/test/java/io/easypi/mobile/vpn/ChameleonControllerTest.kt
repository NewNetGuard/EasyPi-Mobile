package io.easypi.mobile.vpn

import io.easypi.mobile.ChameleonProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ChameleonControllerTest {
    @Test
    fun configUsesOnlyTheEnrolledChameleonProfile() {
        val json = Json.parseToJsonElement(
            ChameleonController.config(
                ChameleonProfile(
                    address = "10.10.0.3/32",
                    serverAddress = "vpn.example:443",
                    serverDialAddress = "192.0.2.3:443",
                    pskKey = "11".repeat(32),
                    realityServerPublicKey = "22".repeat(32),
                    dohDomain = "",
                    bufferSize = 1000,
                    reconnectInterval = 5,
                    mtu = 1420,
                ),
            ),
        ).jsonObject
        assertEquals("vpn.example:443", json.getValue("server_address").jsonPrimitive.content)
        assertEquals("mobile", json.getValue("fingerprint_mode").jsonPrimitive.content)
        assertEquals("ip", json.getValue("reality_shield_preset").jsonPrimitive.content)
        assertEquals(false, json.containsKey("private_key"))
        assertEquals(false, json.containsKey("server_public_key"))
        // No IPv6 endpoint known: the client gets only the IPv4 one.
        assertEquals(false, json.containsKey("server_dial_address6"))
    }

    @Test
    fun configHandsTheIpv6EndpointToTheClient() {
        val json = Json.parseToJsonElement(
            ChameleonController.config(
                ChameleonProfile(
                    address = "10.10.0.3/32",
                    serverAddress = "vpn.example:443",
                    serverDialAddress = "192.0.2.3:443",
                    serverDialAddress6 = "[2001:db8::3]:443",
                    pskKey = "11".repeat(32),
                    realityServerPublicKey = "22".repeat(32),
                    dohDomain = "",
                    bufferSize = 1000,
                    reconnectInterval = 5,
                    mtu = 1420,
                ),
            ),
        ).jsonObject
        assertEquals("192.0.2.3:443", json.getValue("server_dial_address").jsonPrimitive.content)
        assertEquals("[2001:db8::3]:443", json.getValue("server_dial_address6").jsonPrimitive.content)
    }
}
