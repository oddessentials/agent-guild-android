package com.oddessentials.agentguild.model

import java.net.URI
import java.net.URISyntaxException

/** A sign-in link from Agent Guild's Remote access → Connect another device: an address and its token. */
data class SignInLink(val address: String, val token: String) {
    val origin: Origin get() = Origin.parse(address)!!

    val defaultName: String
        get() = origin.host.split('.').first().ifEmpty { "Agent Guild" }

    fun isFor(computer: Computer) = isFor(address, computer)

    // Only the address: the token never goes in a log line.
    override fun toString() = "SignInLink($address)"

    companion object {
        private const val HEX = "[a-f0-9]{32,}"
        private val TOKEN = Regex("^token=($HEX)$")
        private val TOKEN_VALUE = Regex(HEX)

        /** Signing in again is allowed only to the same computer: the same host, whatever the port. */
        fun isFor(address: String, computer: Computer) = Origin.parse(address)!!.host == Origin.parse(computer.address)?.host

        /** [address] with a [token] from device sign-in, or null when the token isn't one the manager makes. */
        fun of(address: String, token: String) = SignInLink(address, token).takeIf { TOKEN_VALUE.matches(token) }

        /**
         * The link [text] holds, or null when it isn't an Agent Guild sign-in link. [allowLoopbackHttp]
         * also accepts `http://127.0.0.1:<port>`, for a scratch manager in debug builds.
         */
        fun parse(text: String, allowLoopbackHttp: Boolean): SignInLink? {
            val uri = uri(text.trim()) ?: return null
            val token = TOKEN.matchEntire(uri.rawFragment ?: return null)?.groupValues?.get(1) ?: return null
            return SignInLink(address(uri, allowLoopbackHttp) ?: return null, token)
        }

        /**
         * The saved form of a typed computer address, `host` or `host:port`, or null. HTTPS is assumed;
         * a leading `https://` and a trailing `/` are allowed. [allowLoopbackHttp] also accepts
         * `http://127.0.0.1:<port>`, written out in full.
         */
        fun parseAddress(text: String, allowLoopbackHttp: Boolean): String? {
            val trimmed = text.trim()
            val uri = uri(if ("://" in trimmed) trimmed else "https://$trimmed") ?: return null
            if (uri.rawFragment != null) return null
            return address(uri, allowLoopbackHttp)
        }

        private fun uri(text: String) = try {
            URI(text)
        } catch (_: URISyntaxException) {
            null
        }

        /** `https://<host>[:<port>]` with the host in lowercase and no `:443`, or null when [uri] is anything more. */
        private fun address(uri: URI, allowLoopbackHttp: Boolean): String? {
            if (!uri.isAbsolute || uri.isOpaque) return null
            val scheme = uri.scheme.lowercase()
            val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val loopback = allowLoopbackHttp && scheme == "http" && host == "127.0.0.1" && uri.port != -1
            if (scheme != "https" && !loopback) return null
            if (uri.rawUserInfo != null) return null
            if (uri.rawPath !in setOf("", "/") || uri.rawQuery != null) return null
            val port = if (uri.port == -1 || (scheme == "https" && uri.port == 443)) "" else ":${uri.port}"
            val address = "$scheme://$host$port"
            return address.takeIf { Origin.parse(it) != null }
        }
    }
}
