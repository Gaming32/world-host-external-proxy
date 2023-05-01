package io.github.gaming32.worldhostexternalproxy

import io.ktor.network.sockets.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Connection(socket: Socket) {
    var id: ConnectionId = ConnectionId(0)
        private set
    val readChannel = socket.openReadChannel()
    val writeChannel = socket.openWriteChannel()
    private val sendLock = Mutex()
    private val recvLock = Mutex()
    var open = true

    suspend fun handshake() {
        id = ConnectionId(readChannel.readLong())
    }

    suspend fun send(message: Message) = sendLock.withLock {
        writeChannel.writeLong(message.connectionId)
        writeChannel.writeByte(message.type)
        with(message) {
            writeChannel.write()
        }
        writeChannel.flush()
    }

    suspend fun recv() = recvLock.withLock { Message.read(readChannel) }
}
