package com.oddessentials.agentguild.model

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** [Health.check] against a server on the loopback interface. */
class HealthCheckTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val address get() = "http://127.0.0.1:${server.address.port}"
    private val token = "0123456789abcdef0123456789abcdef"
    private val authorization = AtomicReference<String?>()
    private val elsewhere = AtomicInteger()

    private fun answer(status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        server.createContext("/api/v1/info") { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = body.encodeToByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/elsewhere") { exchange ->
            elsewhere.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
    }

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun sendsTheTokenAndReadsTheAnswer() = runBlocking {
        answer(200, """{"name":"agent-guild","version":"1.12.0"}""")
        assertEquals(HealthResult.Supported("1.12.0"), Health.check(address, token) { true })
        assertEquals("Bearer $token", authorization.get())
    }

    @Test
    fun aRejectedTokenAsksToSignInAgain() = runBlocking {
        answer(401, """{"error":"missing or invalid API token"}""")
        assertEquals(HealthResult.Failed(HealthProblem.TokenRejected), Health.check(address, token) { true })
    }

    @Test
    fun neverFollowsARedirect() = runBlocking {
        answer(302, "", mapOf("Location" to "/elsewhere"))
        assertEquals(HealthResult.Failed(HealthProblem.Unexpected(302)), Health.check(address, token) { true })
        assertEquals(0, elsewhere.get())
    }

    @Test
    fun anAnswerTooLargeToReadIsNotAgentGuild() = runBlocking {
        val padding = " ".repeat(Health.MAX_BODY)
        answer(200, """{"name":"agent-guild","version":"1.12.0"}$padding""")
        assertEquals(HealthResult.Failed(HealthProblem.NotAgentGuild), Health.check(address, token) { true })
    }

    @Test
    fun anAnswerOfExactlyTheLimitIsRead() = runBlocking {
        val json = """{"name":"agent-guild","version":"1.12.0"}"""
        answer(200, json + " ".repeat(Health.MAX_BODY - json.length))
        assertEquals(HealthResult.Supported("1.12.0"), Health.check(address, token) { true })
    }

    @Test
    fun aLargeErrorBodyKeepsItsStatus() = runBlocking {
        answer(401, "x".repeat(Health.MAX_BODY * 2))
        assertEquals(HealthResult.Failed(HealthProblem.TokenRejected), Health.check(address, token) { true })
    }

    @Test
    fun aClosedPortIsRefused() = runBlocking {
        answer(200, "")
        val closed = address
        server.stop(0)
        assertEquals(HealthResult.Failed(HealthProblem.Refused), Health.check(closed, token) { true })
    }
}
