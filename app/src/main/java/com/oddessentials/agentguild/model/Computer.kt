package com.oddessentials.agentguild.model

/** A computer running Agent Guild: its saved, validated address (`https://<host>[:<port>]`) and token. */
data class Computer(val id: String, val name: String, val address: String, val token: String) {
    // Leaves the token out of anything that prints a computer.
    override fun toString() = "Computer(id=$id, name=$name, address=$address)"
}

enum class PageView(val value: String, val title: String) {
    Yard("yard", "Yard"),
    Cards("cards", "Cards");

    companion object {
        fun of(value: String?) = entries.firstOrNull { it.value == value }
    }
}

/** What a page shows: a saved computer, or the public demo. */
sealed interface Destination {
    data class Saved(val id: String) : Destination
    data object Demo : Destination
}

/** The page the `WebView` may load, and the only origin it may stay on. */
data class Page(val address: String, val token: String?) {
    val origin: Origin = requireNotNull(Origin.parse(address)) { "not a web address" }

    /** `<address>/#token=<token>[&layout=compact&expand=none]&view=<view>`; the demo has no token. */
    fun url(view: PageView?, compact: Boolean = false): String {
        val fragment = listOfNotNull(token?.let { "token=$it" }, "layout=compact&expand=none".takeIf { compact }, view?.let { "view=${it.value}" })
        val base = if (address.endsWith("/")) address else "$address/"
        return if (fragment.isEmpty()) base else base + "#" + fragment.joinToString("&")
    }

    override fun toString() = "Page($address)"

    companion object {
        const val DEMO_ADDRESS = "https://oddessentials.github.io/agent-guild/"
        val demo = Page(DEMO_ADDRESS, null)

        fun of(computer: Computer) = Page(computer.address, computer.token)
    }
}
