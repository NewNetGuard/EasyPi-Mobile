package io.easypi.mobile.prism

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsPacketTest {
    @Test
    fun parsesDnsDomainAndBuildsNxdomainResponse() {
        val dns = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x03, 'a'.code.toByte(), 'd'.code.toByte(), 's'.code.toByte(),
            0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0x00, 0x00, 0x01, 0x00, 0x01,
        )
        val packet = ipv4UdpPacket(dns)
        val parsed = DnsPacket.parse(packet, packet.size)

        assertNotNull(parsed)
        assertEquals("ads.example.com", parsed!!.domain)
        val refused = DnsPacket.nxdomain(parsed.dnsPayload)
        assertEquals(0x12, refused[0].toInt() and 0xff)
        assertEquals(0x34, refused[1].toInt() and 0xff)
        assertEquals(3, refused[3].toInt() and 0x0f)
        val response = DnsPacket.response(parsed, refused)
        assertEquals(parsed.destinationPort, unsignedShort(response, parsed.ipHeaderLength))
        assertEquals(parsed.sourcePort, unsignedShort(response, parsed.ipHeaderLength + 2))
    }

    @Test
    fun rejectsNonDnsUdpPacket() {
        val packet = ipv4UdpPacket(ByteArray(20), destinationPort = 123)
        assertNull(DnsPacket.parse(packet, packet.size))
    }

    private fun ipv4UdpPacket(payload: ByteArray, destinationPort: Int = 53): ByteArray {
        val result = ByteArray(28 + payload.size)
        result[0] = 0x45
        result[2] = (result.size ushr 8).toByte()
        result[3] = result.size.toByte()
        result[8] = 64
        result[9] = 17
        byteArrayOf(10, 111, 0, 1).copyInto(result, 12)
        byteArrayOf(10, 111, 0, 2).copyInto(result, 16)
        putShort(result, 20, 41000)
        putShort(result, 22, destinationPort)
        putShort(result, 24, 8 + payload.size)
        payload.copyInto(result, 28)
        return result
    }

    private fun putShort(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun unsignedShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
}
