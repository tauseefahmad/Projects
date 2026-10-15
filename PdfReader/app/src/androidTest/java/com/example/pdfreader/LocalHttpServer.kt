package com.example.pdfreader

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** A tiny HTTP server on 127.0.0.1 for download tests. [routes] maps a path to (status, type, body). */
class LocalHttpServer(private val routes: Map<String, Triple<Int, String, ByteArray>>) : Closeable {

    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    val port: Int get() = server.localPort

    fun url(path: String) = "http://127.0.0.1:$port$path"

    init {
        Thread {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                Thread { handle(client) }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(client: Socket) = client.use { socket ->
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        val path = requestLine.split(' ').getOrNull(1) ?: "/"
        val (status, type, body) = routes[path] ?: Triple(404, "text/html", "<html>Not found</html>".toByteArray())
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    override fun close() = server.close()
}
