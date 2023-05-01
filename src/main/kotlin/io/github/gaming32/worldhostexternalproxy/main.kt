package io.github.gaming32.worldhostexternalproxy

import io.github.oshai.KotlinLogging
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.coroutines.*

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    val parser = ArgParser("world-host-external-proxy")

    val port by parser.option(ArgType.Int, shortName = "p", description = "Port to bind to").default(9656)
    val baseAddr by parser.option(ArgType.String, shortName = "a", description = "Base address to use for proxy connections").required()
    val inJavaPort by parser.option(ArgType.Int, shortName = "j", description = "Port to use for Java Edition proxy connections").default(25565)
    val exJavaPort by parser.option(ArgType.Int, shortName = "J", description = "External port to use for Java Edition proxy connections")

    parser.parse(args)

    runBlocking {
        ExternalProxyServer(ExternalProxyServer.Config(
            port,
            baseAddr,
            inJavaPort,
            exJavaPort ?: inJavaPort
        )).run()
    }
}
