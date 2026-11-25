package io.easypi.mobile.prism

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DnsQueryPacket(
    val raw: ByteArray,
    val ipHeaderLength: Int,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val dnsPayload: ByteArray,
    val domain: String,
)

object DnsPacket {
    fun parse(packet: ByteArray, length: Int): DnsQueryPacket? {
        if (length < 28 || (packet[0].toInt() ushr 4) != 4) return null
        val headerLength = (packet[0].toInt() and 0x0f) * 4
        if (headerLength < 20 || length < headerLength + 8 || packet[9].toInt() and 0xff != 17) return null
        val udpLength = unsignedShort(packet, headerLength + 4)
        if (udpLength < 8 || headerLength + udpLength > length) return null
        val destinationPort = unsignedShort(packet, headerLength + 2)
        if (destinationPort != 53) return null
        val dns = packet.copyOfRange(headerLength + 8, headerLength + udpLength)
        val domain = readQuestionName(dns) ?: return null
        return DnsQueryPacket(
            raw = packet.copyOf(length),
            ipHeaderLength = headerLength,
            sourceAddress = packet.copyOfRange(12, 16),
            destinationAddress = packet.copyOfRange(16, 20),
            sourcePort = unsignedShort(packet, headerLength),
            destinationPort = destinationPort,
            dnsPayload = dns,
            domain = domain,
        )
    }

    fun response(query: DnsQueryPacket, dnsResponse: ByteArray): ByteArray {
        val result = ByteArray(query.ipHeaderLength + 8 + dnsResponse.size)
        query.raw.copyInto(result, endIndex = query.ipHeaderLength)
        result[2] = (result.size ushr 8).toByte()
        result[3] = result.size.toByte()
        query.destinationAddress.copyInto(result, 12)
        query.sourceAddress.copyInto(result, 16)
        val udp = query.ipHeaderLength
        putShort(result, udp, query.destinationPort)
        putShort(result, udp + 2, query.sourcePort)
        putShort(result, udp + 4, 8 + dnsResponse.size)
        putShort(result, udp + 6, 0) // IPv4 permits a zero UDP checksum.
        dnsResponse.copyInto(result, udp + 8)
        putShort(result, 10, 0)
        putShort(result, 10, ipv4Checksum(result, query.ipHeaderLength))
        return result
    }

    fun nxdomain(query: ByteArray): ByteArray {
        require(query.size >= 12)
        val result = query.copyOf()
        val requestFlags = unsignedShort(query, 2)
        val responseFlags = 0x8000 or (requestFlags and 0x0100) or 0x0080 or 0x0003
        putShort(result, 2, responseFlags)
        putShort(result, 6, 0)
        putShort(result, 8, 0)
        putShort(result, 10, 0)
        return result
    }

    private fun readQuestionName(dns: ByteArray): String? {
        if (dns.size < 17 || unsignedShort(dns, 4) != 1) return null
        var offset = 12
        val labels = mutableListOf<String>()
        while (offset < dns.size) {
            val size = dns[offset].toInt() and 0xff
            offset++
            if (size == 0) break
            if (size > 63 || offset + size > dns.size) return null
            val label = dns.copyOfRange(offset, offset + size).toString(Charsets.US_ASCII)
            if (label.any { !it.isLetterOrDigit() && it != '-' && it != '_' }) return null
            labels += label
            offset += size
        }
        if (labels.isEmpty() || offset + 4 > dns.size) return null
        return labels.joinToString(".").lowercase()
    }

    private fun ipv4Checksum(packet: ByteArray, headerLength: Int): Int {
        var sum = 0L
        var index = 0
        while (index < headerLength) {
            sum += unsignedShort(packet, index)
            while (sum > 0xffff) sum = (sum and 0xffff) + (sum ushr 16)
            index += 2
        }
        return sum.inv().toInt() and 0xffff
    }

    private fun unsignedShort(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff

    private fun putShort(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }
}
