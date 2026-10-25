package com.oddessentials.agentguild

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.FilterInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FakeServerTest {
    @Test
    fun anIdleConnectionCannotServeAfterTeardown() {
        ControlledListener(holdAccept = false).use { listener ->
            FakeServer(listener).use { old ->
                old.page("/frame", "retired")
                Socket("127.0.0.1", old.port).use { peer ->
                    val accepted = requireNotNull(listener.accepted.poll(20, TimeUnit.SECONDS))
                    // The handler owns the connection and is waiting for its first request.
                    assertTrue(accepted.reading.await(20, TimeUnit.SECONDS))
                    old.close()
                    assertRetiredConnectionIsClosed(old.port, peer)
                }
            }
        }
    }

    @Test
    fun aConnectionAcceptedDuringShutdownCannotSurviveTeardown() {
        ControlledListener(holdAccept = true).use { listener ->
            FakeServer(listener).use { old ->
                old.page("/frame", "retired")
                Socket("127.0.0.1", old.port).use { peer ->
                    requireNotNull(listener.accepted.poll(20, TimeUnit.SECONDS))
                    // accept() has obtained a real socket but has not returned it to FakeServer.
                    val closing = FutureTask { old.close() }
                    thread { closing.run() }
                    try {
                        assertTrue(listener.closed.await(20, TimeUnit.SECONDS))
                    } finally {
                        listener.releaseAccept.countDown()
                    }
                    closing.get(20, TimeUnit.SECONDS)
                    assertRetiredConnectionIsClosed(old.port, peer)
                }
            }
        }
    }

    @Test
    fun closeTerminatesAnActiveHandlerBeforeReturning() {
        FakeServer(0).use { server ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            server.route("/frame") {
                entered.countDown()
                try {
                    release.await()
                    FakeServer.Response(200, "retired")
                } finally {
                    finished.countDown()
                }
            }
            Socket("127.0.0.1", server.port).use { peer ->
                try {
                    request(peer)
                    assertTrue(entered.await(20, TimeUnit.SECONDS))
                    server.close()
                    assertEquals("The handler must finish before close returns", 0L, finished.count)
                    assertDisconnected(peer)
                } finally {
                    release.countDown()
                }
            }
        }
    }

    private fun assertRetiredConnectionIsClosed(port: Int, peer: Socket) {
        // Probe the stale connection before rebinding, so a retained socket cannot obscure the
        // failure with EADDRINUSE on platforms that keep its port reserved.
        try {
            request(peer)
        } catch (_: SocketException) {
            // A reset while writing also proves the connection was closed.
        }
        assertDisconnected(peer)
        FakeServer(port).use { replacement ->
            replacement.page("/frame", "current")
            Socket("127.0.0.1", port).use { fresh ->
                fresh.soTimeout = 20_000
                request(fresh)
                assertTrue(fresh.getInputStream().bufferedReader().readText().endsWith("current"))
                assertEquals(1, replacement.hits("/frame"))
            }
        }
    }

    private fun request(peer: Socket) {
        peer.getOutputStream().write("GET /frame HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".encodeToByteArray())
    }

    private fun assertDisconnected(peer: Socket) {
        peer.soTimeout = 20_000
        val firstByte = try {
            peer.getInputStream().read()
        } catch (_: SocketException) {
            -1 // A peer reset is equivalent to EOF; a timeout is deliberately not caught.
        }
        assertEquals("A retired connection must not serve an HTTP response", -1, firstByte)
    }

    /** Holds the OS accept boundary independently of the server's worker startup. */
    private class ControlledListener(holdAccept: Boolean) : ServerSocket() {
        val accepted = LinkedBlockingQueue<ObservedSocket>()
        val releaseAccept = CountDownLatch(if (holdAccept) 1 else 0)
        val closed = CountDownLatch(1)

        init {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        }

        override fun accept(): Socket {
            val client = ObservedSocket()
            implAccept(client)
            accepted += client
            releaseAccept.await()
            return client
        }

        override fun close() {
            super.close()
            closed.countDown()
        }
    }

    private class ObservedSocket : Socket() {
        val reading = CountDownLatch(1)

        override fun getInputStream() = object : FilterInputStream(super.getInputStream()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reading.countDown()
                return super.read(bytes, offset, length)
            }
        }
    }
}
