package com.pesacast.android.webhook

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal HTTP/1.1 server for tests, built on plain sockets (com.sun.net.httpserver is not on
 * the Android unit-test classpath). Reads one request per connection and answers with whatever
 * [handler] returns, then closes the connection.
 */
class MiniHttpServer(private val handler: (path: String, headers: Map<String, String>, body: ByteArray) -> Int) {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    @Volatile private var running = true
    @Volatile var responseBody: String = ""
    val port: Int get() = socket.localPort

    init {
        thread(isDaemon = true, name = "mini-http") {
            while (running) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    fun stop() {
        running = false
        socket.close()
    }

    private fun serve(client: Socket) {
        client.use {
        try {
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return@use
            val path = requestLine.split(" ").getOrElse(1) { "/" }
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val code = handler(path, headers, body)
            val location = if (code in 300..399) "Location: http://127.0.0.1/elsewhere\r\n" else ""
            client.getOutputStream().apply {
                val payload = responseBody.toByteArray()
                write("HTTP/1.1 $code X\r\n${location}Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(payload)
                flush()
            }
        } catch (_: Exception) {
            // The client went away; nothing to do in a test double.
        }
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) return out.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            out.write(b)
        }
    }
}
