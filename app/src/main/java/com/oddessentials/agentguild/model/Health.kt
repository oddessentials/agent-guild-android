package com.oddessentials.agentguild.model

import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

sealed interface HealthProblem {
    val message: String
    val needsSignIn: Boolean get() = false

    data object Forbidden : HealthProblem {
        override val message = "The computer refused this address. In Agent Guild, open Settings → Remote access and turn it on, or add this address to your reverse proxy settings."
    }

    data object TokenRejected : HealthProblem {
        override val message = "The computer didn't accept this sign-in. Scan its current sign-in code."
        override val needsSignIn = true
    }

    data object Refused : HealthProblem {
        override val message = "The computer isn't accepting connections at this address. Remote access may be off, or its address changed. Turn it on, or scan its sign-in code again."
        override val needsSignIn = true
    }

    data object Offline : HealthProblem {
        override val message = "This device is offline."
    }

    data object Unreachable : HealthProblem {
        override val message = "Agent Guild can't be reached. Check that the computer is awake, that Agent Guild is running, and that this device is on the same tailnet."
    }

    data object NotAgentGuild : HealthProblem {
        override val message = "This address doesn't answer as Agent Guild."
    }

    data class TooOld(val version: String) : HealthProblem {
        override val message = "This computer runs Agent Guild $version. Update it to ${Health.MINIMUM_VERSION} or later."
    }

    data class Unexpected(val status: Int) : HealthProblem {
        override val message = "Agent Guild answered with an unexpected error ($status)."
    }
}

sealed interface HealthResult {
    data class Supported(val version: String) : HealthResult
    data class Failed(val problem: HealthProblem) : HealthResult
}

/** `GET <address>/api/v1/info` with the token: is this a supported Agent Guild, and does it accept the token? */
object Health {
    /** The first release with agent-guild's change "feat: support Agent Guild in the Android app web view". */
    const val MINIMUM_VERSION = "1.9.0"
    const val TIMEOUT_MILLIS = 10_000L

    fun infoUrl(address: String) = address.trimEnd('/') + "/api/v1/info"

    fun evaluate(status: Int, body: String): HealthResult {
        when (status) {
            200 -> Unit
            401 -> return HealthResult.Failed(HealthProblem.TokenRejected)
            403 -> return HealthResult.Failed(HealthProblem.Forbidden)
            else -> return HealthResult.Failed(HealthProblem.Unexpected(status))
        }
        val info = Json.parseOrNull(body) as? Map<*, *>
        val version = info?.get("version") as? String
        if (info?.get("name") != "agent-guild" || version == null) return HealthResult.Failed(HealthProblem.NotAgentGuild)
        if (!supports(version)) return HealthResult.Failed(HealthProblem.TooOld(version))
        return HealthResult.Supported(version)
    }

    /** Development builds report 0.0.0 whatever their age, so they pass. */
    fun supports(version: String): Boolean {
        val found = numbers(version) ?: return false
        val minimum = numbers(MINIMUM_VERSION)!!
        if (found == listOf(0, 0, 0)) return true
        for (i in 0..2) if (found[i] != minimum[i]) return found[i] > minimum[i]
        return true
    }

    private fun numbers(version: String): List<Int>? {
        val parts = version.substringBefore('-').split('.')
        if (parts.size != 3) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** The problem a failed request shows. */
    fun problem(error: IOException, online: Boolean): HealthProblem = when {
        !online -> HealthProblem.Offline
        error is ConnectException -> HealthProblem.Refused
        else -> HealthProblem.Unreachable
    }

    /**
     * Never follows a redirect, because the request carries the token. A request that takes longer
     * than [TIMEOUT_MILLIS] is [HealthProblem.Unreachable]. Cancelling the coroutine closes the connection.
     */
    suspend fun check(address: String, token: String, online: () -> Boolean): HealthResult = coroutineScope {
        val connection = (URL(infoUrl(address)).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_MILLIS.toInt()
            readTimeout = TIMEOUT_MILLIS.toInt()
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        val request = async(Dispatchers.IO) {
            try {
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                // A body too large to read whole is never taken as Agent Guild's answer.
                val body = stream?.use { readBody(it) }?.decodeToString().orEmpty()
                evaluate(status, body)
            } catch (error: IOException) {
                HealthResult.Failed(problem(error, online()))
            }
        }
        try {
            withTimeoutOrNull(TIMEOUT_MILLIS) { request.await() } ?: HealthResult.Failed(HealthProblem.Unreachable)
        } finally {
            // Ends a request still blocked on the network, after a timeout or a cancellation.
            connection.disconnect()
        }
    }

    const val MAX_BODY = 64 * 1024

    /**
     * The whole of [input], or null when it is longer than [limit] bytes. InputStream.readNBytes needs
     * API 33, and the app supports API 29.
     */
    fun readBody(input: InputStream, limit: Int = MAX_BODY): ByteArray? {
        val buffer = ByteArray(limit + 1)
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count == -1) return buffer.copyOf(read)
            read += count
        }
        return null
    }
}
