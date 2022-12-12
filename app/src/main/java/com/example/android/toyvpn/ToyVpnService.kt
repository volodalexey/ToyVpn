/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.android.toyvpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.content.Intent
import android.os.Handler
import android.os.Message
import android.util.Log
import android.widget.Toast
import kotlin.jvm.Synchronized
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.Exception
import java.lang.IllegalArgumentException
import java.lang.IllegalStateException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

class ToyVpnService : VpnService(), Handler.Callback, Runnable {
    private var mServerAddress: String? = null
    private var mServerPort: String? = null
    private lateinit var mSharedSecret: ByteArray
    private var mHandler: Handler? = null
    private var mThread: Thread? = null
    private var mInterface: ParcelFileDescriptor? = null
    private var mParameters: String? = null
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        // The handler is only used to show messages.
        if (mHandler == null) {
            mHandler = Handler(this)
        }

        // Stop the previous session by interrupting the thread.
        if (mThread != null) {
            mThread!!.interrupt()
        }

        // Extract information from the intent.
        val prefix = packageName
        mServerAddress = intent.getStringExtra("$prefix.ADDRESS")
        mServerPort = intent.getStringExtra("$prefix.PORT")
        mSharedSecret = intent.getStringExtra("$prefix.SECRET")!!.toByteArray()

        // Start a new session by creating a new thread.
        mThread = Thread(this, "ToyVpnThread")
        mThread!!.start()
        return START_STICKY
    }

    override fun onDestroy() {
        if (mThread != null) {
            mThread!!.interrupt()
        }
    }

    override fun handleMessage(message: Message): Boolean {
        Toast.makeText(this, message.what, Toast.LENGTH_SHORT).show()
        return true
    }

    @Synchronized
    override fun run() {
        try {
            Log.i(TAG, "Starting")

            // If anything needs to be obtained using the network, get it now.
            // This greatly reduces the complexity of seamless handover, which
            // tries to recreate the tunnel without shutting down everything.
            // In this demo, all we need to know is the server address.
            val server = InetSocketAddress(
                mServerAddress, mServerPort!!.toInt()
            )

            // We try to create the tunnel for several times. The better way
            // is to work with ConnectivityManager, such as trying only when
            // the network is avaiable. Here we just use a counter to keep
            // things simple.
            var attempt = 0
            while (attempt < 10) {
                mHandler!!.sendEmptyMessage(R.string.connecting)

                // Reset the counter if we were connected.
                if (run(server)) {
                    attempt = 0
                }

                // Sleep for a while. This also checks if we got interrupted.
                Thread.sleep(3000)
                ++attempt
            }
            Log.i(TAG, "Giving up")
        } catch (e: Exception) {
            Log.e(TAG, "Got $e")
        } finally {
            try {
                mInterface!!.close()
            } catch (e: Exception) {
                // ignore
            }
            mInterface = null
            mParameters = null
            mHandler!!.sendEmptyMessage(R.string.disconnected)
            Log.i(TAG, "Exiting")
        }
    }

    @Throws(Exception::class)
    private fun run(server: InetSocketAddress): Boolean {
        var tunnel: DatagramChannel? = null
        var connected = false
        try {
            // Create a DatagramChannel as the VPN tunnel.
            tunnel = DatagramChannel.open()

            // Protect the tunnel before connecting to avoid loopback.
            check(protect(tunnel.socket())) { "Cannot protect the tunnel" }

            // Connect to the server.
            tunnel.connect(server)

            // For simplicity, we use the same thread for both reading and
            // writing. Here we put the tunnel into non-blocking mode.
            tunnel.configureBlocking(false)

            // Authenticate and configure the virtual network interface.
            handshake(tunnel)

            // Now we are connected. Set the flag and show the message.
            connected = true
            mHandler!!.sendEmptyMessage(R.string.connected)

            // Packets to be sent are queued in this input stream.
            val `in` = FileInputStream(mInterface!!.fileDescriptor)

            // Packets received need to be written to this output stream.
            val out = FileOutputStream(mInterface!!.fileDescriptor)

            // Allocate the buffer for a single packet.
            val packet = ByteBuffer.allocate(32767)

            // We use a timer to determine the status of the tunnel. It
            // works on both sides. A positive value means sending, and
            // any other means receiving. We start with receiving.
            var timer = 0

            // We keep forwarding packets till something goes wrong.
            while (true) {
                // Assume that we did not make any progress in this iteration.
                var idle = true

                // Read the outgoing packet from the input stream.
                var length = `in`.read(packet.array())
                if (length > 0) {
                    // Write the outgoing packet to the tunnel.
                    packet.limit(length)
                    val iPv4 = IPv4(packet)
                    Log.v(TAG, "[TX] $iPv4")
                    // The position of IPv4 instance must move to 0
                    packet.rewind()
                    tunnel.write(packet)
                    packet.clear()

                    // There might be more outgoing packets.
                    idle = false

                    // If we were receiving, switch to sending.
                    if (timer < 1) {
                        timer = 1
                    }
                }

                // Read the incoming packet from the tunnel.
                length = tunnel.read(packet)
                if (length > 0) {
                    // Ignore control messages, which start with zero.
                    if (packet[0].toInt() != 0) {
                        // Write the incoming packet to the output stream.

                        // The position of IPv4 instance must move to 0
                        packet.rewind()
                        val iPv4 = IPv4(packet)
                        Log.v(TAG, "[RX] $iPv4")
                        out.write(packet.array(), 0, length)
                    }
                    packet.clear()

                    // There might be more incoming packets.
                    idle = false

                    // If we were sending, switch to receiving.
                    if (timer > 0) {
                        timer = 0
                    }
                }

                // If we are idle or waiting for the network, sleep for a
                // fraction of time to avoid busy looping.
                if (idle) {
                    Thread.sleep(100)

                    // Increase the timer. This is inaccurate but good enough,
                    // since everything is operated in non-blocking mode.
                    timer += if (timer > 0) 100 else -100

                    // We are receiving for a long time but not sending.
                    if (timer < -15000) {
                        // Send empty control messages.
                        packet.put(0.toByte()).limit(1)
                        for (i in 0..2) {
                            packet.position(0)
                            tunnel.write(packet)
                        }
                        packet.clear()

                        // Switch to sending.
                        timer = 1
                    }

                    // We are sending for a long time but not receiving.
                    check(timer <= 20000) { "Timed out" }
                }
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Got $e")
        } finally {
            try {
                tunnel?.close()
            } catch (e: Exception) {
                Log.v(TAG, e.toString())
            }
        }
        return connected
    }

    @Throws(Exception::class)
    private fun handshake(tunnel: DatagramChannel?) {
        // To build a secured tunnel, we should perform mutual authentication
        // and exchange session keys for encryption. To keep things simple in
        // this demo, we just send the shared secret in plaintext and wait
        // for the server to send the parameters.

        // Allocate the buffer for handshaking.
        val packet = ByteBuffer.allocate(1024)

        // Control messages always start with zero.
        packet.put(0.toByte()).put(mSharedSecret).flip()

        // Send the secret several times in case of packet loss.
        for (i in 0..2) {
            packet.position(0)
            tunnel!!.write(packet)
        }
        packet.clear()

        // Wait for the parameters within a limited time.
        for (i in 0..49) {
            Thread.sleep(100)

            // Normally we should not receive random packets.
            val length = tunnel!!.read(packet)
            if (length > 0 && packet[0].toInt() == 0) {
                configure(String(packet.array(), 1, length - 1).trim { it <= ' ' })
                return
            }
        }
        throw IllegalStateException("Timed out")
    }

    @Throws(Exception::class)
    private fun configure(parameters: String) {
        // If the old interface has exactly the same parameters, use it!
        if (mInterface != null && parameters == mParameters) {
            Log.i(TAG, "Using the previous interface")
            return
        }

        // Configure a builder while parsing the parameters.
        val builder = Builder()
        for (parameter in parameters.split(" ").toTypedArray()) {
            val fields = parameter.split(",").toTypedArray()
            try {
                when (fields[0][0]) {
                    'm' -> builder.setMtu(fields[1].toShort().toInt())
                    'a' -> builder.addAddress(fields[1], fields[2].toInt())
                    'r' -> builder.addRoute(fields[1], fields[2].toInt())
                    'd' -> builder.addDnsServer(fields[1])
                    's' -> builder.addSearchDomain(fields[1])
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Bad parameter: $parameter")
            }
        }

        // Close the old interface since the parameters have been changed.
        try {
            mInterface!!.close()
        } catch (e: Exception) {
            // ignore
        }

        // Create a new interface using the builder and save the parameters.
        mInterface = builder.setSession(mServerAddress!!)
            .establish()
        mParameters = parameters
        Log.i(TAG, "New interface: $parameters")
    }

    //    String printPacket(ByteBuffer packet, int length) {
    //        // Network Layer, IPv4 only
    //        // TODO should support IPv6
    //        byte[] fourBytes = new byte[4];
    //        fourBytes[0] = packet.get(12);
    //        fourBytes[1] = packet.get(13);
    //        fourBytes[2] = packet.get(14);
    //        fourBytes[3] = packet.get(15);
    //
    //        fourBytes[0] = packet.get(16);
    //        fourBytes[1] = packet.get(17);
    //        fourBytes[2] = packet.get(18);
    //        fourBytes[3] = packet.get(19);
    //
    //
    //
    //
    //        StringBuilder sb = new StringBuilder();
    //        sb.append(ipHeader.toString());
    //        int headerLength = ipHeader.getiHL();
    //
    //        // Transport layer(TCP, UDP)
    //        byte[] twoBytes = new byte[2];
    //        twoBytes[0] = packet.get(headerLength);
    //        twoBytes[1] = packet.get(headerLength + 1);
    //        int sourcePort = convertBytesToInt(twoBytes);
    //
    //        twoBytes[0] = packet.get(headerLength + 2);
    //        twoBytes[1] = packet.get(headerLength + 3);
    //        int destinationPort = convertBytesToInt(twoBytes);
    //        if (ipHeader.getProtocol() == 6) {
    //            TCP tcp = new TCP();
    //            tcp.setSourcePort(sourcePort);
    //            tcp.setDestinationPort(destinationPort);
    //            tcp.setSeq(packet.get(headerLength+4));
    //
    //            fourBytes[0] = packet.get(headerLength + 4);
    //            fourBytes[1] = packet.get(headerLength + 5);
    //            fourBytes[2] = packet.get(headerLength + 6);
    //            fourBytes[3] = packet.get(headerLength + 7);
    //            tcp.setSeq(convertBytesToInt(fourBytes));
    //
    //            fourBytes[0] = packet.get(headerLength + 8);
    //            fourBytes[1] = packet.get(headerLength + 9);
    //            fourBytes[2] = packet.get(headerLength + 10);
    //            fourBytes[3] = packet.get(headerLength + 11);
    //            tcp.setAck(convertBytesToInt(fourBytes));
    //
    //            int tcpHeaderSize = getDataOffset(packet.get(headerLength + 12)) * 4;
    //            tcp.setDataOffset(tcpHeaderSize);
    //
    //            tcp.setFIN(getFINinfo(packet.get(headerLength + 13)));
    //            tcp.setSYN(getSYNinfo(packet.get(headerLength + 13)));
    //            tcp.setRST(getRSTinfo(packet.get(headerLength + 13)));
    //            tcp.setPSH(getPSHinfo(packet.get(headerLength + 13)));
    //            tcp.setACK(getACKinfo(packet.get(headerLength + 13)));
    //
    //            sb.append(tcp.toString());
    //
    //            ByteBuffer payload = ByteBuffer.allocate(length - headerLength -
    //                    tcpHeaderSize);
    //
    //            for(int i = headerLength + tcpHeaderSize; i < length; i++) {
    //                if (isPrintable(packet.get(i)))
    //                    payload.put(packet.get(i));
    //                else
    //                  // print '.'
    //                   payload.put((byte)0x2E);
    //            }
    //            sb.append(new String(payload.array()));
    //        }
    //        else if(ipHeader.getProtocol() == 17) {
    //            UDP udp = new UDP();
    //            udp.setSourcePort(sourcePort);
    //            udp.setDestinationPort(destinationPort);
    //
    //            sb.append(udp.toString());
    //
    //            // TODO DNS check
    //            if (udp.getSourcePort() == 53 || udp.getDestinationPort() == 53) {
    //                twoBytes[0] = packet.get(headerLength + 8);
    //                twoBytes[1] = packet.get(headerLength + 9);
    //                DNS dns = new DNS();
    //                dns.setTransactionID(twoBytes);
    //                dns.setResponse(getBitValue(packet.get(headerLength + 10), 0));
    //
    //                twoBytes[0] = packet.get(headerLength + 12);
    //                twoBytes[1] = packet.get(headerLength + 13);
    //                dns.setNoOfQuestion(convertBytesToInt(twoBytes));
    //
    //                twoBytes[0] = packet.get(headerLength + 14);
    //                twoBytes[1] = packet.get(headerLength + 15);
    //                dns.setNoOfAnswer(convertBytesToInt(twoBytes));
    //
    //                twoBytes[0] = packet.get(headerLength + 16);
    //                twoBytes[1] = packet.get(headerLength + 17);
    //                dns.setNoOfAuthority(convertBytesToInt(twoBytes));
    //
    //                twoBytes[0] = packet.get(headerLength + 18);
    //                twoBytes[1] = packet.get(headerLength + 19);
    //                dns.setNoOfAdditional(convertBytesToInt(twoBytes));
    //
    //                //int questions = dns.getNoOfQuestion();
    //                StringBuilder domainName = new StringBuilder();
    //
    //                for (int i = 0, size = -1; packet.get(headerLength + 20 + i) != 0; i++) {
    //                    if (size == 0 || size == -1) {
    //                        size = packet.get(headerLength + 20 + i);
    //                        if (size == 0)
    //                            domainName.append(".");
    //                    }
    //                    else {
    //                        domainName.append(packet.get(headerLength + 20 + i));
    //                        size--;
    //                    }
    //                }
    //                dns.setHostName(domainName.toString());
    //
    //                //getHostnames(headerLength);
    //                if (dns.isResponse()) {
    //
    //                } else {
    //
    //                }
    //
    //            } else {
    //                ByteBuffer payload = ByteBuffer.allocate(length - headerLength - 8);
    //                for (int i = headerLength + 8; i < length; i++) {
    //                    if (isPrintable(packet.get(i)))
    //                        payload.put(packet.get(i));
    //                    else
    //                        payload.put((byte) 0x2E);
    //                }
    //                sb.append(new String(payload.array()));
    //            }
    //        }
    //
    //        return sb.toString();
    //    }
    fun convertBytesToInt(digit: ByteArray): Int {
        if (digit.size == 2) {
            return digit[0].toInt() and 0xFF shl 8 or (digit[1].toInt() and 0xFF)
        } else if (digit.size == 4) {
            return digit[0].toInt() and 0xFF shl 24 or (digit[1].toInt() and 0xFF shl 16) or
                    (digit[2].toInt() and 0xFF shl 8) or (digit[3].toInt() and 0xFF)
        }
        throw IllegalArgumentException("NOT supported byte length")
    }

    fun getFINinfo(value: Byte): Boolean {
        return value.toInt() and 1 == 1
    }

    fun getSYNinfo(value: Byte): Boolean {
        return value.toInt() shr 1 and 1 == 1
    }

    fun getRSTinfo(value: Byte): Boolean {
        return value.toInt() shr 2 and 1 == 1
    }

    fun getPSHinfo(value: Byte): Boolean {
        return value.toInt() shr 3 and 1 == 1
    }

    fun getACKinfo(value: Byte): Boolean {
        return value.toInt() shr 4 and 1 == 1
    }

    fun getDataOffset(value: Byte): Int {
        return value.toInt() shr 4 and 0xF
    }

    fun isPrintable(value: Byte): Boolean {
        //in ASCII table
        return value in 0x20..0x7E
    }

    fun getBitValue(value: Byte, index: Int): Boolean {
        return value.toInt() shr index - 7 and 1 == 1
    }

    companion object {
        private const val TAG = "ToyVpnService"
    }
}