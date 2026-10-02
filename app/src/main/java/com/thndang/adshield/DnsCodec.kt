package com.thndang.adshield

data class CapturedDnsRequest(
    val sourceIp: ByteArray,
    val destinationIp: ByteArray,
    val sourcePort: Int,
    val dnsPayload: ByteArray,
    val domain: String
)

object DnsCodec {

    fun parseIpv4UdpDns(packet: ByteArray, length: Int): CapturedDnsRequest? {
        if (length < 40) return null

        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null

        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        if (ipHeaderLength < 20 || length < ipHeaderLength + 20) return null

        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return null

        val flagsAndOffset = u16(packet, 6)
        if ((flagsAndOffset and 0x1FFF) != 0) return null

        val udpOffset = ipHeaderLength
        val sourcePort = u16(packet, udpOffset)
        val destinationPort = u16(packet, udpOffset + 2)
        if (destinationPort != 53) return null

        val udpLength = u16(packet, udpOffset + 4)
        val dnsLength = udpLength - 8
        val dnsOffset = udpOffset + 8
        if (dnsLength < 12 || dnsOffset + dnsLength > length) return null

        val dnsPayload = packet.copyOfRange(dnsOffset, dnsOffset + dnsLength)
        val domain = readQueryName(dnsPayload) ?: return null

        return CapturedDnsRequest(
            sourceIp = packet.copyOfRange(12, 16),
            destinationIp = packet.copyOfRange(16, 20),
            sourcePort = sourcePort,
            dnsPayload = dnsPayload,
            domain = domain
        )
    }

    fun buildBlockedNxdomain(query: ByteArray): ByteArray {
        val response = query.copyOf()
        if (response.size < 12) return response

        val requestFlags = u16(response, 2)
        val responseFlags =
            0x8000 or
            (requestFlags and 0x0100) or
            0x0080 or
            0x0003

        putU16(response, 2, responseFlags)
        putU16(response, 6, 0)
        putU16(response, 8, 0)
        putU16(response, 10, 0)
        return response
    }

    fun buildIpv4UdpResponse(
        request: CapturedDnsRequest,
        dnsResponse: ByteArray
    ): ByteArray {
        val ipHeaderLength = 20
        val udpHeaderLength = 8
        val totalLength = ipHeaderLength + udpHeaderLength + dnsResponse.size
        val packet = ByteArray(totalLength)

        packet[0] = 0x45
        packet[1] = 0
        putU16(packet, 2, totalLength)
        putU16(packet, 4, 0)
        putU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = 17
        putU16(packet, 10, 0)

        System.arraycopy(request.destinationIp, 0, packet, 12, 4)
        System.arraycopy(request.sourceIp, 0, packet, 16, 4)

        val udpOffset = 20
        putU16(packet, udpOffset, 53)
        putU16(packet, udpOffset + 2, request.sourcePort)
        putU16(packet, udpOffset + 4, udpHeaderLength + dnsResponse.size)
        putU16(packet, udpOffset + 6, 0)

        System.arraycopy(
            dnsResponse,
            0,
            packet,
            udpOffset + udpHeaderLength,
            dnsResponse.size
        )

        putU16(packet, 10, ipv4Checksum(packet, 0, ipHeaderLength))
        return packet
    }

    private fun readQueryName(dns: ByteArray): String? {
        if (dns.size < 13) return null
        if (u16(dns, 4) < 1) return null

        val labels = mutableListOf<String>()
        var position = 12
        var guard = 0

        while (position < dns.size && guard++ < 128) {
            val size = dns[position].toInt() and 0xFF
            if (size == 0) return labels.joinToString(".")

            if ((size and 0xC0) != 0) return null

            position++
            if (size > 63 || position + size > dns.size) return null

            labels += dns.copyOfRange(position, position + size)
                .toString(Charsets.US_ASCII)
            position += size
        }

        return null
    }

    private fun ipv4Checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length

        while (i + 1 < end) {
            sum += u16(data, i).toLong()
            i += 2
        }

        if (i < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
        }

        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }

        return sum.inv().toInt() and 0xFFFF
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)

    private fun putU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }
}
