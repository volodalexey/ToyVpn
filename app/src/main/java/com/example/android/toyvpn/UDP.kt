package com.example.android.toyvpn

import java.lang.StringBuilder
import java.nio.ByteBuffer

class UDP(private val packet: ByteBuffer) {
    private val sourcePort: Int
    private val destinationPort: Int
    private val length: Int
    private val checksum: Int
    private var dns: DNS? = null

    init {
        sourcePort = get16Bits()
        destinationPort = get16Bits()
        length = get16Bits()
        checksum = get16Bits()
        if (isDNS) {
            dns = DNS(packet)
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("(sport: ").append(sourcePort.toString())
        sb.append(", dport: ").append(destinationPort.toString()).append(")")
        if (sourcePort == 53 || destinationPort == 53) sb.append(dns.toString())
        return sb.toString()
    }

    private fun get16Bits(): Int {
        return packet.short.toInt() and 0xFFFF
    }

    private val isDNS: Boolean
        get() = sourcePort == 53 || destinationPort == 53

//    companion object {
//        private const val TAG = "UDP"
//    }
}