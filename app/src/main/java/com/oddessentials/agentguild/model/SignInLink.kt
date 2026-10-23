package com.oddessentials.agentguild.model

import java.net.URI
import java.net.URISyntaxException

/** A sign-in link from Agent Guild's Remote access → Connect another device: an address and its token. */
data class SignInLink(val address: String, val token: String) {
    val origin: Origin get() = Origin.parse(address)!!

    val defaultName: String
        get() = origin.host.split('.').first().ifEmpty { "Agent Guild" }

    /** Signing in again is allowed only to the same computer: the same host, whatever the port. */
    fun isFor(computer: Computer) = origin.host == Origin.parse(computer.address)?.host

    // Only the address: the token never goes in a log line.
    override fun toString() = "SignInLink($address)"

    companion object {
        private val TOKEN = Regex("^token=([a-f0-9]{32,})$")

        /**
         * The link [text] holds, or null when it isn't an Agent Guild sign-in link. [allowLoopbackHttp]
         * also accepts `http://127.0.0.1:<port>`, for a scratch manager in debug builds.
         */
        fun parse(text: String, allowLoopbackHttp: Boolean): SignInLink? {
            val uri = try {
                URI(text.trim())
            } catch (_: URISyntaxException) {
                return null
            }
            if (!uri.isAbsolute || uri.isOpaque) return null
            val scheme = uri.scheme.lowercase()
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val loopback = allowLoopbackHttp && scheme == "http" && host == "127.0.0.1" && uri.port != -1
            if (scheme != "https" && !loopback) return null
            if (uri.rawUserInfo != null) return null
            if (uri.rawPath !in setOf("", "/") || uri.rawQuery != null) return null
            val token = TOKEN.matchEntire(uri.rawFragment ?: return null)?.groupValues?.get(1) ?: return null
            val port = if (uri.port == -1 || (scheme == "https" && uri.port == 443)) "" else ":${uri.port}"
            val address = "$scheme://$host$port"
            Origin.parse(address) ?: return null
            return SignInLink(address, token)
        }
    }
}
