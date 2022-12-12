package com.example.android.toyvpn

import android.util.Log
import java.lang.StringBuilder
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.util.ArrayList

class DNS(private val packet: ByteBuffer) {
    private val transactionID: Int
    private val response: Boolean
    private val opCode: Short
    private val truncated: Boolean
    private val recursionDesired: Boolean
    private val nonAuthenticatedData: Boolean
    private val noOfQuestion: Int
    private val noOfAnswer: Int
    private val noOfAuthority: Int
    private val noOfAdditional: Int
    private val queries: MutableList<Question>
    private val answers: MutableList<Answer>

    /**
     * Get the value in ByteBuffer deciding whether its position is moved or not
     */
    private var indexMode = false
    private var indexPacket = 0

    internal open inner class Question {
        var hostname: String? = null
        var type: Int? = null
        var queryClass: Int? = null
    }

    internal inner class Answer : Question() {
        var ttl: Long? = null
        var length: Int? = null
        var address: InetAddress? = null
        var canonicalName: String? = null
    }

    init {
        transactionID = get16Bits()
        val flags = get16Bits()
        response = flags shr 15 and 1 == 1
        opCode = (flags and 0x7800 ushr 11).toShort()
        truncated = flags shr 9 and 1 == 1
        recursionDesired = flags shr 8 and 1 == 1
        nonAuthenticatedData = flags shr 4 and 1 == 1
        noOfQuestion = get16Bits()
        noOfAnswer = get16Bits()
        noOfAuthority = get16Bits()
        noOfAdditional = get16Bits()
        queries = ArrayList()
        for (i in 0 until noOfQuestion) {
            val temp = Question()
            temp.hostname = hostname
            temp.type = get16Bits()
            temp.queryClass = get16Bits()
            queries.add(temp)
        }
        answers = ArrayList()
        if (noOfAnswer > 0) {
            for (i in 0 until noOfAnswer) {
                val temp = Answer()
                temp.hostname = hostname
                temp.type = get16Bits()
                temp.queryClass = get16Bits()
                temp.ttl = get32Bits()
                temp.length = get16Bits()
                when (temp.type!!.toInt()) {
                    1 -> try {
                        temp.address = iPAddress
                    } catch (e: UnknownHostException) {
                        Log.e(TAG, e.toString())
                    }
                    5 -> temp.canonicalName = hostname
                    28 -> try {
                        temp.address = iPv6Address
                    } catch (e: UnknownHostException) {
                        Log.e(TAG, e.toString())
                    }
                }
                answers.add(temp)
            }
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("DNS{")
        for (query in queries) {
            val type = query.type!!.toInt()
            sb.append(query.hostname + "(Type: " + type + ")")
        }
        sb.append(" ")
        for (answer in answers) {
            val type = answer.type!!.toInt()
            if (type == 1 || type == 28) {
                sb.append(answer.address?.hostAddress + " ")
            } else {
                sb.append("Type: $type, ")
            }
        }
        sb.append("}")
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

    private val hostname: String
        get() {
            var c: Short
            var len = -1
            indexMode = false
            val sb = StringBuilder()
            do {
                c = get8BitsStream()
                if (len == -1 || len == 0) {
                    if (c.toInt() == 0) break else {
                        if (isPointer(c)) {
                            val ch = get8BitsStream()
                            val pointer: Int = (c.toInt() and 0x3F shl 8) + ch
                            indexPacket = 20 + 8 + pointer
                            if (!indexMode) indexMode = true
                        } else {
                            if (len == 0) sb.append(".")
                            len = c.toInt()
                        }
                    }
                } else {
                    sb.append(Char(c.toUShort()))
                    len--
                }
            } while (true)
            return sb.toString()
        }

    private fun get8BitsStream(): Short {
        val ch: Short
        if (indexMode) {
            ch = packet[indexPacket].toShort()
            indexPacket++
        } else {
            ch = get8Bits()
        }
        return ch
    }

    private fun isPointer(value: Short): Boolean {
        return value.toInt() and 0xFF shr 6 and 3 == 3
    }

    private val iPAddress: InetAddress
        get() {
            val address = ByteArray(4)
            for (i in address.indices) address[i] = (packet.get().toInt() and 0xFF).toByte()
            return try {
                InetAddress.getByAddress(address)
            } catch (e: UnknownHostException) {
                throw UnknownHostException("IP address is not valid.")
            }
        }

    private val iPv6Address: InetAddress
        get() {
            val address = ByteArray(16)
            for (i in address.indices) address[i] = (packet.get().toInt() and 0xFF).toByte()
            return try {
                InetAddress.getByAddress(address)
            } catch (e: UnknownHostException) {
                throw UnknownHostException("IP address is not valid.")
            }
        }

    companion object {
        private const val TAG = "DNS"
    }
}