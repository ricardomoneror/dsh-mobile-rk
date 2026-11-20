package com.labteto.dshmobile.runtime

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * fork: expone el harness al LAN sin tocar el bind del guest.
 *
 * `dsh web` se niega a atar 0.0.0.0 (guardia contra RCE a la red) y atar una
 * IP concreta deja el loopback sin servicio — y el loopback es como la app se
 * firma y se conecta. Este forwarder atiende en la IPv4 del dispositivo y
 * reenvía byte a byte a 127.0.0.1:port, así que loopback sigue intacto y el
 * resto de la red puede llegar con el token de `dsh web`.
 */
internal class LanForwarder(private val log: (String) -> Unit) {

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var boundAddress: String? = null

    /** The address the LAN listener actually bound to, or null when it is off. */
    val address: String? get() = boundAddress

    /** Start forwarding [port] on [host]; a no-op when already running on the same pair. */
    @Synchronized
    fun start(host: String, port: Int) {
        if (running) return
        val socket = try {
            ServerSocket(port, 50, InetAddress.getByName(host))
        } catch (e: Throwable) {
            // El puerto puede estar tomado por la propia interfaz o el host no ser local.
            log("lan-forward: no se pudo atar $host:$port (${e.message})")
            return
        }
        server = socket
        boundAddress = host
        running = true
        thread(name = "lan-forward", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    break
                }
                thread(name = "lan-forward-conn", isDaemon = true) { bridge(client, port) }
            }
        }
        log("lan-forward: $host:$port -> 127.0.0.1:$port")
    }

    @Synchronized
    fun stop() {
        running = false
        boundAddress = null
        try { server?.close() } catch (_: Throwable) { }
        server = null
    }

    private fun bridge(client: Socket, port: Int) {
        try {
            client.use { c ->
                Socket(InetAddress.getByName("127.0.0.1"), port).use { up ->
                    val a = thread(name = "lan-up", isDaemon = true) {
                        try { c.getInputStream().copyTo(up.getOutputStream()) } catch (_: Throwable) { }
                        try { up.shutdownOutput() } catch (_: Throwable) { }
                    }
                    try { up.getInputStream().copyTo(c.getOutputStream()) } catch (_: Throwable) { }
                    try { c.shutdownOutput() } catch (_: Throwable) { }
                    a.join(2000)
                }
            }
        } catch (_: Throwable) {
            // Una conexión que cae no afecta al listener.
        }
    }

    companion object {
        /** First non-loopback IPv4, or null when the device is offline. */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
