package com.example.android.toyvpn

import android.util.Log
import java.lang.StringBuilder
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer

class IPv4(private val packet: ByteBuffer) {
    private val version: Byte
    private val iHL: Byte
    private val dscp: Byte
    private val ecn: Byte
    private val totalLength: Int
    private val id: Int
    private val reserved: Boolean
    private val df: Boolean
    private val mf: Boolean
    private val fragmentOffset: Int
    private val ttl: Short
    private val protocol: Short
    private val headerChecksum: Int
    private var source: InetAddress? = null
    private var destination: InetAddress? = null
    private var tcp: TCP? = null
    private var udp: UDP? = null

    init {
        var aShort: Short = (packet.get().toInt() and 0xFF).toShort()
        version = (aShort.toInt() shr 4 and 0xF).toByte()
        // iHL unit is number of 32bit words, so multiplied by 4
        iHL = ((aShort.toInt() and 0x0F) * 4).toByte()
        aShort = (packet.get().toInt() and 0xFF).toShort()
        dscp = (aShort.toInt() shr 2 and 0x3F).toByte()
        ecn = (aShort.toInt() and 0x03).toByte()
        totalLength = get16Bits()
        id = get16Bits()
        val aInteger = get16Bits()
        reserved = aInteger shr 15 and 1 == 1
        df = aInteger shr 14 and 1 == 1
        mf = aInteger shr 13 and 1 == 1
        // fragment offset unit is 8 byte blocks
        fragmentOffset = (aInteger and 0x1FFF) * 8
        ttl = get8Bits()
        protocol = get8Bits()
        headerChecksum = get16Bits()
        try {
            source = iPAddress
            destination = iPAddress
        } catch (e: UnknownHostException) {
            Log.e(TAG, e.toString())
        }
        if (protocol.toInt() == TCP) {
            tcp = TCP(packet)
        } else if (protocol.toInt() == UDP) {
            udp = UDP(packet)
        }
    }

    override fun toString(): String {
        val string = StringBuilder("S: " + source.toString())
        string.append(", D: ").append(destination.toString())
        string.append(", ")
        if (protocol.toInt() == 6) string.append("TCP") else if (protocol.toInt() == 17) string.append(
            "UDP"
        ) else string.append("Unknown(").append(protocol).append(")")
        string.append(", hdr: ").append(iHL)
        if (protocol.toInt() == 6) string.append(tcp.toString()) else if (protocol.toInt() == 17) string.append(
            udp.toString()
        )
        return string.toString()
    }

    private fun get8Bits(): Short {
        return (packet.get().toInt() and 0xFF).toShort()
    }

    private fun get16Bits(): Int {
        return packet.short.toInt() and 0xFFFF
    }

    @get:Throws(UnknownHostException::class)
    private val iPAddress: InetAddress
        get() {
            val fourBytes = ByteArray(4)
            for (i in fourBytes.indices) fourBytes[i] = (packet.get().toInt() and 0xFF).toByte()
            return try {
                InetAddress.getByAddress(fourBytes)
            } catch (e: UnknownHostException) {
                throw UnknownHostException("IP address is not valid.")
            }
        }

    companion object {
        private const val TAG = "IPv4"
        const val TCP = 6
        const val UDP = 17
    }
}