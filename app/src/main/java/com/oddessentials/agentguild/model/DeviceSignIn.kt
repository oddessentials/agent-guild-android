package com.oddessentials.agentguild.model

import com.oddessentials.agentguild.model.Health.Reply
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.delay

/**
 * Device sign-in, agent-guild's `POST /sign-in` and `POST /sign-in/poll`: the device shows a code, the
 * person approves it on the page, and the next poll answers the token.
 */
object DeviceSignIn {
    sealed interface Answer {
        /** A code to show until [expiresAt], polled with [device] every [interval] seconds. */
        class Code(val code: String, val device: String, val expiresAt: Instant, val interval: Long) : Answer {
            // Only the code: the device secret never goes in a log line.
            override fun toString() = "Code($code)"
        }

        data object Pending : Answer
        data class Approved(val link: SignInLink) : Answer
        data object Denied : Answer
        data object Expired : Answer
        data object Busy : Answer

        /** A manager from before device sign-in. */
        data object Unsupported : Answer
        data class Failed(val problem: HealthProblem) : Answer
    }

    fun signInUrl(address: String) = address.trimEnd('/') + "/api/v1/sign-in"

    suspend fun start(address: String, online: () -> Boolean): Answer =
        when (val reply = Health.request(signInUrl(address), online, post = "")) {
            is Reply.Answered -> started(reply.status, reply.body)
            is Reply.Failed -> Answer.Failed(reply.problem)
        }

    suspend fun poll(address: String, device: String, online: () -> Boolean): Answer =
        when (val reply = Health.request(signInUrl(address) + "/poll", online, post = """{"device":${Json.quote(device)}}""")) {
            is Reply.Answered -> polled(address, reply.status, reply.body)
            is Reply.Failed -> Answer.Failed(reply.problem)
        }

    /** Polls every [Answer.Code.interval] seconds until the sign-in is no longer pending. */
    suspend fun await(address: String, code: Answer.Code, online: () -> Boolean, wait: suspend (Long) -> Unit = { delay(it) }): Answer {
        while (true) {
            wait(code.interval * 1000)
            val answer = poll(address, code.device, online)
            if (answer != Answer.Pending) return answer
        }
    }

    fun started(status: Int, body: String): Answer {
        when (status) {
            201 -> Unit
            // A manager without the route checks the token first, so it answers 401 rather than 404.
            401, 404 -> return Answer.Unsupported
            403 -> return Answer.Failed(HealthProblem.Forbidden)
            429 -> return Answer.Busy
            else -> return Answer.Failed(HealthProblem.Unexpected(status))
        }
        val signIn = signIn(body)
        val code = signIn?.get("code") as? String
        val device = signIn?.get("device") as? String
        val expiresAt = (signIn?.get("expiresAt") as? String)?.let(::instant)
        val interval = (signIn?.get("interval") as? Double)?.toLong()
        if (code.isNullOrEmpty() || device.isNullOrEmpty() || expiresAt == null || interval == null) {
            return Answer.Failed(HealthProblem.NotAgentGuild)
        }
        return Answer.Code(code, device, expiresAt, interval.coerceIn(1, 60))
    }

    fun polled(address: String, status: Int, body: String): Answer {
        when (status) {
            200 -> Unit
            403 -> return Answer.Failed(HealthProblem.Forbidden)
            404 -> return Answer.Expired
            else -> return Answer.Failed(HealthProblem.Unexpected(status))
        }
        val signIn = signIn(body)
        return when (signIn?.get("status")) {
            "pending" -> Answer.Pending
            "approved" -> (signIn["token"] as? String)?.let { SignInLink.of(address, it) }?.let(Answer::Approved)
                ?: Answer.Failed(HealthProblem.NotAgentGuild)
            "denied" -> Answer.Denied
            else -> Answer.Failed(HealthProblem.NotAgentGuild)
        }
    }

    private fun signIn(body: String) = (Json.parseOrNull(body) as? Map<*, *>)?.get("signIn") as? Map<*, *>

    private fun instant(text: String) = try {
        Instant.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }
}
