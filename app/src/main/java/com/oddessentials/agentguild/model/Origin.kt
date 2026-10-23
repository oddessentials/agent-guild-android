package com.oddessentials.agentguild.model

/**
 * A web origin: scheme, host and port, with the scheme's default port filled in.
 *
 * [parse] fails closed: it refuses anything it can't read the way Chromium would, so an unusual
 * URL never counts as the manager's origin. URLs that reach the app from the `WebView` are
 * already in Chromium's canonical form.
 */
data class Origin(val scheme: String, val host: String, val port: Int) {
    /** The form `addWebMessageListener`'s origin rules take, without a default port. */
    val rule: String
        get() = if (port == defaultPort(scheme)) "$scheme://$host" else "$scheme://$host:$port"

    override fun toString() = rule

    companion object {
        private val URL = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/\\?#]*)""")
        private val AUTHORITY = Regex("""^([A-Za-z0-9.\-]+|\[[0-9A-Fa-f:.]+])(?::([0-9]{1,5}))?$""")

        /** The origin of an `http` or `https` URL with a host, or null. A URL with a user name or password has none. */
        fun parse(url: String?): Origin? {
            val match = URL.find(url ?: return null) ?: return null
            val scheme = match.groupValues[1].lowercase()
            if (scheme != "http" && scheme != "https") return null
            val authority = AUTHORITY.matchEntire(match.groupValues[2]) ?: return null
            val host = authority.groupValues[1].lowercase()
            val port = authority.groupValues[2].takeIf { it.isNotEmpty() }?.toInt() ?: defaultPort(scheme)
            if (port !in 1..65535) return null
            return Origin(scheme, host, port)
        }

        fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80
    }
}
