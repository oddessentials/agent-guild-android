package com.oddessentials.agentguild.model

import com.oddessentials.agentguild.model.DeviceSignIn.Answer
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DeviceSignIn] against a server on the loopback interface that answers as agent-guild's `/sign-in` routes. */
class DeviceSignInTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val address get() = "http://127.0.0.1:${server.address.port}"
    private val token = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val device = "fedcba9876543210fedcba9876543210fedcba9876543210"
    private val started = """{"signIn":{"code":"ABCD-EFGH","device":"$device","expiresAt":"2026-10-27T12:05:00.000Z","interval":3}}"""
    private val polls = ConcurrentLinkedQueue<String>()
    private val authorizations = ConcurrentLinkedQueue<String>()
    private val elsewhere = AtomicInteger()

    /** Answers `POST /sign-in` with [start], and each poll with the next of [answers], the last one repeating. */
    private fun serve(start: Pair<Int, String> = 201 to started, answers: List<Pair<Int, String>> = emptyList(), headers: Map<String, String> = emptyMap()) {
        val next = AtomicInteger()
        fun reply(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
            exchange.requestHeaders.getFirst("Authorization")?.let { authorizations += it }
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = body.encodeToByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/v1/sign-in") { exchange ->
            if (exchange.requestURI.path == "/api/v1/sign-in/poll") {
                polls += exchange.requestBody.readBytes().decodeToString()
                val (status, body) = answers[minOf(next.getAndIncrement(), answers.lastIndex)]
                reply(exchange, status, body)
            } else {
                reply(exchange, start.first, start.second)
            }
        }
        server.createContext("/elsewhere") { exchange ->
            elsewhere.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
    }

    private fun status(value: String, extra: String = "") = 200 to """{"signIn":{"status":"$value"$extra}}"""

    private suspend fun code() = DeviceSignIn.start(address) { true } as Answer.Code

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun showsTheCodeAndPollsUntilApproved() = runBlocking {
        serve(answers = listOf(status("pending"), status("pending"), status("approved", ""","token":"$token"""")))
        val code = code()
        assertEquals("ABCD-EFGH", code.code)
        assertEquals(Instant.parse("2026-10-27T12:05:00Z"), code.expiresAt)
        val waits = mutableListOf<Long>()
        assertEquals(Answer.Approved(SignInLink(address, token)), DeviceSignIn.await(address, code, { true }) { waits += it })
        assertEquals(listOf(3000L, 3000L, 3000L), waits)
        assertEquals(List(3) { """{"device":"$device"}""" }, polls.toList())
        assertTrue(authorizations.isEmpty())
    }

    @Test
    fun aDeclinedOrExpiredSignInEnds() = runBlocking {
        serve(answers = listOf(status("pending"), status("denied"), 404 to """{"error":{"code":"sign_in_unknown"}}"""))
        val code = code()
        assertEquals(Answer.Denied, DeviceSignIn.await(address, code, { true }) {})
        assertEquals(Answer.Expired, DeviceSignIn.await(address, code, { true }) {})
    }

    @Test
    fun anOlderManagerAsksForAnUpdate() = runBlocking {
        serve(start = 401 to """{"error":{"code":"unauthorized"}}""")
        assertEquals(Answer.Unsupported, DeviceSignIn.start(address) { true })
        assertEquals(Answer.Unsupported, DeviceSignIn.started(404, """{"error":{"code":"not_found"}}"""))
    }

    @Test
    fun aBusyManagerAsksToTryAgain() = runBlocking {
        serve(start = 429 to """{"error":{"code":"sign_in_busy"}}""")
        assertEquals(Answer.Busy, DeviceSignIn.start(address) { true })
    }

    @Test
    fun refusesATokenTheManagerDoesNotMake() = runBlocking {
        serve(answers = listOf(status("approved", ""","token":"${token.uppercase()}"""")))
        assertEquals(Answer.Failed(HealthProblem.NotAgentGuild), DeviceSignIn.await(address, code(), { true }) {})
    }

    @Test
    fun neverFollowsARedirectWithTheSecret() = runBlocking {
        serve(answers = listOf(302 to ""), headers = mapOf("Location" to "/elsewhere"))
        assertEquals(Answer.Failed(HealthProblem.Unexpected(302)), DeviceSignIn.poll(address, device) { true })
        assertEquals(0, elsewhere.get())
    }

    @Test
    fun cancellingStopsPolling() = runBlocking {
        serve(answers = listOf(status("pending")))
        val code = code()
        val waiting = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            DeviceSignIn.await(address, code, { true }) {
                if (polls.isNotEmpty()) {
                    waiting.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        waiting.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, polls.size)
    }

    @Test
    fun neverPrintsTheSecretOrToken() {
        val code = DeviceSignIn.started(201, started) as Answer.Code
        assertFalse(code.toString().contains(device))
        assertFalse(Answer.Approved(SignInLink(address, token)).toString().contains(token))
        assertNull(SignInLink.of(address, "short"))
    }
}
