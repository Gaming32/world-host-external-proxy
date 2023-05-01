package io.github.gaming32.worldhostexternalproxy

import io.github.oshai.KotlinLogging
import io.ktor.utils.io.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val logger = KotlinLogging.logger {}

class ExternalProxyServer(val config: Config) {
    data class Config(
        val port: Int,
        val baseAddr: String,
        val inJavaPort: Int,
        val exJavaPort: Int
    )

    private val connectionsLock = Mutex()
    private val connections = mutableMapOf<ConnectionId, Connection>()

    val proxyConnectionsLock = Mutex()
    val proxyConnections = mutableMapOf<Long, Pair<ConnectionId, ByteWriteChannel>>()

    suspend fun run() = coroutineScope {
        logger.info("Starting world-host-external-proxy $PROXY_VERSION with {}", config)
        launch { proxyServerManager() }
        proxyServer()
    }

    suspend fun addConnection(connection: Connection) = connectionsLock.withLock {
        if (connection.id in connections) {
            return@withLock false
        }
        connections[connection.id] = connection
        true
    }

    suspend fun removeConnection(connection: Connection) = connectionsLock.withLock { connections -= connection.id }

    suspend fun getConnection(connectionId: ConnectionId) = connectionsLock.withLock { connections[connectionId] }

    val connectionCount get() = connections.size
}
