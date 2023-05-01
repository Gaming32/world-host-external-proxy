package io.github.gaming32.worldhostexternalproxy

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

private val logger = KotlinLogging.logger {}

suspend fun ExternalProxyServer.proxyServerManager() = coroutineScope {
    logger.info("Starting WH proxy server manager on port {}", config.port)
    aSocket(SelectorManager(Dispatchers.IO)).tcp().bind(port = config.port).use { serverSocket ->
        logger.info("Started WH proxy server manager on {}", serverSocket.localAddress)
        while (true) {
            val clientSocket = serverSocket.accept()
            launch {
                var connection: Connection? = null
                try {
                    connection = Connection(clientSocket)
                    try {
                        connection.handshake()
                    } catch (_: ClosedReceiveChannelException) {
                        logger.info("Received a ping connection (immediate disconnect)")
                        return@launch
                    } catch (e: Exception) {
                        logger.warn("Invalid handshake from {}", clientSocket.remoteAddress, e)
                        return@launch clientSocket.close()
                    }

                    logger.info("Management connection opened: {}", connection.id)

                    val start = System.currentTimeMillis()
                    while (!addConnection(connection)) {
                        val time = System.currentTimeMillis()
                        if (time - start > 500) {
                            logger.warn("ID {} used twice. Disconnecting {}.", connection.id, connection)
                            return@launch clientSocket.close()
                        }
                    }

                    logger.info("There are {} open management connections.", connectionCount)

                    while (true) {
                        when (val message = connection.recv()) {
                            is Message.Open -> throw AssertionError(
                                "Shouldn't receive Open message on server (this should've been caught already)"
                            )
                            is Message.Packet -> proxyConnectionsLock.withLock {
                                proxyConnections[message.connectionId]?.let { (cid, channel) ->
                                    if (cid == connection.id) {
                                        channel.writeFully(message.buffer)
                                        channel.flush()
                                    }
                                }
                            }
                            is Message.Close -> proxyConnectionsLock.withLock {
                                proxyConnections[message.connectionId]?.let { (cid, channel) ->
                                    if (cid == connection.id) {
                                        channel.close()
                                    }
                                }
                            }
                        }
                    }
                } catch (_: ClosedReceiveChannelException) {
                } catch (e: Exception) {
                    if (
                        e !is IOException ||
                        e.message != "An existing connection was forcibly closed by the remote host"
                    ) {
                        logger.error("An error occurred in management connection handling", e)
                    }
                } finally {
                    clientSocket.close()
                    if (connection != null && connection.id.id != 0L) {
                        connection.open = false
                        logger.info("Management connection closed: {}", connection.id)
                        removeConnection(connection)
                        logger.info("There are {} open management connections", connectionCount)
                    }
                }
            }
        }
    }
}
