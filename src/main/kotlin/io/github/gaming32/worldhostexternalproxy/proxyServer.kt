package io.github.gaming32.worldhostexternalproxy

import io.github.gaming32.worldhostexternalproxy.ConnectionId.Companion.toConnectionId
import io.github.oshai.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.errors.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.intellij.lang.annotations.Language
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private val logger = KotlinLogging.logger {}

suspend fun ExternalProxyServer.proxyServer() = coroutineScope {
    logger.info("Starting proxy server")
    aSocket(SelectorManager(Dispatchers.IO)).tcp().bind(port = config.inJavaPort).use { serverSocket ->
        var nextConnectionId = 0L
        logger.info("Started proxy server on {}", serverSocket.localAddress)
        while (true) {
            val proxySocket = serverSocket.accept()
            logger.info("Accepted proxy connection from {}", proxySocket.remoteAddress)
            val connectionId = nextConnectionId++
            launch {
                var connection: Connection? = null
                try {
                    val receiveChannel = proxySocket.openReadChannel()
                    val sendChannel = proxySocket.openWriteChannel()

                    val handshakeData = ByteArray(receiveChannel.readVarInt()).also { receiveChannel.readFully(it) }
                    val inp = ByteArrayInputStream(handshakeData)
                    inp.readVarInt() // Packet ID
                    inp.readVarInt() // Protocol version
                    val thisAddr = inp.readString(255)
                    inp.skip(2) // Port
                    val nextState = inp.readVarInt()

                    val cidStr = thisAddr.substringBefore('.')
                    val destCid = try {
                        cidStr.toConnectionId()
                    } catch (e: Exception) {
                        if (thisAddr == config.baseAddr) {
                            // Star Trek humor
                            return@launch disconnect(sendChannel, nextState, "I'm a proxy server, not an engineer!")
                        }
                        return@launch disconnect(sendChannel, nextState, "Invalid ConnectionId: ${e.localizedMessage}")
                    }

                    connection = getConnection(destCid) ?:
                        return@launch disconnect(sendChannel, nextState, "Couldn't find that server")

                    proxyConnectionsLock.withLock {
                        proxyConnections[connectionId] = Pair(connection!!.id, sendChannel)
                    }
                    connection.send(Message.Open(connectionId, proxySocket.remoteAddress))
                    connection.send(Message.Packet(
                        connectionId,
                        ByteArrayOutputStream().apply {
                            writeVarInt(handshakeData.size)
                            write(handshakeData)
                        }.toByteArray()
                    ))
                    val buffer = ByteArray(64 * 1024)
                    proxyLoop@ while (!sendChannel.isClosedForWrite) {
                        if (!connection!!.open) {
                            sendChannel.close()
                            break
                        }
                        val n = receiveChannel.readAvailable(buffer)
                        if (n == 0) continue
                        if (n == -1) {
                            sendChannel.close()
                            break
                        }
                        if (!connection.open) {
                            val failureStart = System.currentTimeMillis()
                            do {
                                if ((System.currentTimeMillis() - failureStart) > 5000) {
                                    sendChannel.close()
                                    break@proxyLoop
                                }
                                yield()
                                connection = getConnection(destCid)
                            } while (connection == null || !connection.open)
                        }
                        connection.send(Message.Packet(connectionId, buffer.copyOf(n)))
                    }
                } catch (_: ClosedReceiveChannelException) {
                } catch (e: Exception) {
                    if (
                        e !is IOException ||
                        e.message != "An existing connection was forcibly closed by the remote host"
                    ) {
                        logger.error("An error occurred in proxy client handling", e)
                    }
                } finally {
                    proxyConnectionsLock.withLock {
                        proxyConnections -= connectionId
                    }
                    if (connection?.open == true) {
                        connection.send(Message.Close(connectionId))
                    }
                    logger.info("Proxy connection closed")
                }
            }
        }
    }
}

private suspend fun disconnect(sendChannel: ByteWriteChannel, nextState: Int, message: String) {
    @Language("JSON") val jsonMessage = """{"text":"$message","color":"red"}"""
    val out = ByteArrayOutputStream()
    out.writeVarInt(0x00)
    if (nextState == 1) {
        //language=JSON
        out.writeString("""{"description":$jsonMessage}""")
    } else if (nextState == 2) {
        out.writeString(jsonMessage, 262144)
    }
    val out2 = ByteArrayOutputStream()
    out2.writeVarInt(out.size())
    @Suppress("BlockingMethodInNonBlockingContext")
    out2.write(out.toByteArray())
    sendChannel.writeFully(out2.toByteArray())
    sendChannel.flush()

    if (nextState == 1) {
        out.reset()
        out.writeVarInt(0x01)
        repeat(8) {
            out.write(0)
        }
        out2.reset()
        out2.writeVarInt(out.size())
        @Suppress("BlockingMethodInNonBlockingContext")
        out2.write(out.toByteArray())
        sendChannel.writeFully(out2.toByteArray())
        sendChannel.flush()
    }

    sendChannel.close()
}
