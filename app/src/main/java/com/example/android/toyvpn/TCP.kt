package com.example.android.toyvpn

import java.lang.StringBuilder
import java.nio.ByteBuffer

class TCP(private val packet: ByteBuffer) {
    private val sourcePort: Int
    private val destinationPort: Int
    private val seq: Long
    private val ack: Long
    private val dataOffset: Byte
    private val NS: Boolean
    private val CWR: Boolean
    private val ECE: Boolean
    private val URG: Boolean
    private val ACK: Boolean
    private val PSH: Boolean
    private val RST: Boolean
    private val SYN: Boolean
    private val FIN: Boolean
    private val windowSize: Int
    private val checksum: Int
    private val urgentPointer: Int

    init {
        sourcePort = get16Bits()
        destinationPort = get16Bits()
        seq = get32Bits()
        ack = get32Bits()
        var aShort = get8Bits()
        dataOffset = ((aShort.toInt() and 0xF0 ushr 4) * 4).toByte()
        NS = aShort.toInt() and 0x01 == 1
        aShort = get8Bits()
        CWR = aShort.toInt() shr 7 and 1 == 1
        ECE = aShort.toInt() shr 6 and 1 == 1
        URG = aShort.toInt() shr 5 and 1 == 1
        ACK = aShort.toInt() shr 4 and 1 == 1
        PSH = aShort.toInt() shr 3 and 1 == 1
        RST = aShort.toInt() shr 2 and 1 == 1
        SYN = aShort.toInt() shr 1 and 1 == 1
        FIN = aShort.toInt() and 1 == 1
        windowSize = get16Bits()
        checksum = get16Bits()
        urgentPointer = get16Bits()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("(sport: ").append(sourcePort).append(", dport: ")
        sb.append(destinationPort).append(" ")
        if (SYN) sb.append("S")
        if (ACK) sb.append("A")
        if (PSH) sb.append("P")
        if (FIN) sb.append("F")
        if (RST) sb.append("R")
        // int(signed) is 32bit, so extend it to long(64bit) for printing
        var unsignedFormat = seq and 0xFFFF
        sb.append(", seq: ").append(unsignedFormat)
        unsignedFormat = ack and 0xFFFF
        sb.append(", ack: ").append(unsignedFormat)
        sb.append(")")
        return sb.toString()
    }

    private fun get8Bits(): Short {
        return (packet.get().toInt() and 0xFF).toShort()
    }

    private fun get16Bits(): Int {
        return packet.short.toInt() and 0xFFFF
    }

    private fun get32Bits(): Long {
        return (packet.int and 0xFFFFFFFFL.toInt()).toLong()
    }

//    companion object {
//        private const val TAG = "TCP"
//    }
}