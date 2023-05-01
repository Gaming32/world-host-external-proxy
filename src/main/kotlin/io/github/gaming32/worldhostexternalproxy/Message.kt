package io.github.gaming32.worldhostexternalproxy

import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import java.net.InetSocketAddress

sealed class Message(val connectionId: Long, val type: Byte) {
    class Open(connectionId: Long, address: SocketAddress) : Message(connectionId, 0) {
        private val addrBytes = address.toJavaAddress().cast<InetSocketAddress>().address.address

        override suspend fun ByteWriteChannel.write() {
            writeByte(addrBytes.size.toByte())
            writeFully(addrBytes)
        }
    }

    class Packet(connectionId: Long, val buffer: ByteArray) : Message(connectionId, 1) {
        init {
            require(buffer.size <= UShort.MAX_VALUE.toInt()) { "Packet exceeds max packet size" }
        }

        override suspend fun ByteWriteChannel.write() {
            writeShort(buffer.size)
            writeFully(buffer)
        }
    }

    class Close(connectionId: Long) : Message(connectionId, 2)

    companion object {
        suspend fun read(channel: ByteReadChannel): Message {
            val connectionId = channel.readLong()
            return when (val packetId = channel.readByte()) {
                0.toByte() -> throw IllegalArgumentException("Cannot read Open message on server")
                1.toByte() -> Packet(
                    connectionId,
                    ByteArray(channel.readShort().toUShort().toInt())
                        .also { channel.readFully(it) }
                )
                2.toByte() -> Close(connectionId)
                else -> throw IllegalArgumentException("Unknown packet ID $packetId")
            }
        }
    }

    open suspend fun ByteWriteChannel.write() = Unit
}
