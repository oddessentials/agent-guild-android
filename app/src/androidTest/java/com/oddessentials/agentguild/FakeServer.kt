package com.oddessentials.agentguild

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A small HTTP/1.1 server on the device's loopback interface, in the test process, for the cases the
 * real manager can't stage: another origin, a redirect, a frame from elsewhere.
 */
class FakeServer(val port: Int) : AutoCloseable {
    data class Request(val method: String, val path: String, val body: String)
    data class Response(val status: Int, val body: String = "", val type: String = "text/html", val headers: Map<String, String> = emptyMap())

    val origin = "http://127.0.0.1:$port"
    val requests = CopyOnWriteArrayList<Request>()
    private val arrivals = LinkedBlockingQueue<Request>()
    private val routes = ConcurrentHashMap<String, (Request) -> Response>()
    private val socket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
    }

    init {
        thread(isDaemon = true, name = "FakeServer:$port") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    fun route(path: String, response: (Request) -> Response) {
        routes[path] = response
    }

    fun page(path: String, html: String) = route(path) { Response(200, html) }

    /** Answers `/api/v1/info` as an Agent Guild that supports the app. */
    fun actAsManager(status: () -> Int = { 200 }) = route("/api/v1/info") {
        val code = status()
        if (code == 200) Response(200, """{"name":"agent-guild","version":"${com.oddessentials.agentguild.model.Health.MINIMUM_VERSION}"}""", "application/json")
        else Response(code, "{}", "application/json")
    }

    fun hits(path: String) = requests.count { it.path.substringBefore('?') == path }

    /** Waits for a request to [path], and returns it. */
    fun await(path: String, timeoutSeconds: Long = 20): Request {
        requests.firstOrNull { it.path.substringBefore('?') == path }?.let { return it }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            val left = deadline - System.nanoTime()
            check(left > 0) { "No request to $path on $origin; saw ${requests.map { it.path }}" }
            val request = arrivals.poll(left, TimeUnit.NANOSECONDS) ?: continue
            if (request.path.substringBefore('?') == path) return request
        }
    }

    private fun serve(client: Socket): Unit = client.use {
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return@use
        val parts = requestLine.split(' ')
        if (parts.size < 2) return@use
        var length = 0
        while (true) {
            val header = readLine(input) ?: return@use
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
        }
        val body = String(input.readNBytes(length))
        val request = Request(parts[0], parts[1], body)
        requests += request
        arrivals += request
        val handler = routes[parts[1].substringBefore('?')]
        val response = handler?.invoke(request) ?: Response(404, "not found", "text/plain")
        val bytes = response.body.encodeToByteArray()
        val head = buildString {
            append("HTTP/1.1 ${response.status} X\r\n")
            append("Content-Type: ${response.type}; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        client.getOutputStream().apply {
            write(head.encodeToByteArray())
            write(bytes)
            flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
        }
    }

    override fun close() = socket.close()
}
