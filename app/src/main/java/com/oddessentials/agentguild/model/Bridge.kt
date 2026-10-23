package com.oddessentials.agentguild.model

sealed interface BridgeMessage {
    data class Ready(val version: String) : BridgeMessage
    data class Open(val url: String) : BridgeMessage
}

/** The page's messages to the app, as agent-guild's docs/api.md describes them for Android. */
object Bridge {
    const val LISTENER_NAME = "agentGuild"

    /** A message counts only from the main frame at exactly the manager's origin: the page embeds other frames. */
    fun accepts(isMainFrame: Boolean, sourceOrigin: String?, manager: Origin) =
        isMainFrame && Origin.parse(sourceOrigin) == manager

    fun parse(text: String?): BridgeMessage? {
        val message = Json.parseOrNull(text ?: return null) as? Map<*, *> ?: return null
        if (message["source"] != "agent-guild") return null
        return when (message["type"]) {
            "ready" -> (message["version"] as? String)?.let { BridgeMessage.Ready(it) }
            "open" -> (message["url"] as? String)?.takeIf(Navigation::isWeb)?.let { BridgeMessage.Open(it) }
            else -> null
        }
    }

    /** Waits for the page's load event, in case the page's own scripts aren't listening yet. */
    fun viewScript(view: PageView) = """
        (() => {
          const send = () => window.postMessage({ source: 'agent-guild-app', type: 'view', view: '${view.value}' }, location.origin);
          if (document.readyState === 'complete') send(); else addEventListener('load', send, { once: true });
        })()
    """.trimIndent()
}
