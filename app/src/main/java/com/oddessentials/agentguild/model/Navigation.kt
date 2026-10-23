package com.oddessentials.agentguild.model

sealed interface NavigationDecision {
    data object Allow : NavigationDecision
    data class OpenOutside(val url: String) : NavigationDecision
    data object Cancel : NavigationDecision
}

/** Keeps the `WebView`'s main frame on the manager's origin. */
object Navigation {
    fun isWeb(url: String?) = Origin.parse(url) != null

    /**
     * For `shouldOverrideUrlLoading`. Subframes, such as the page's video player, may load. A main-frame
     * load elsewhere is cancelled, and a link the user followed opens outside instead; a redirect never does.
     */
    fun decide(url: String?, isMainFrame: Boolean, hasGesture: Boolean, isRedirect: Boolean, manager: Origin): NavigationDecision {
        if (url == null) return NavigationDecision.Cancel
        if (!isMainFrame) return NavigationDecision.Allow
        if (Origin.parse(url) == manager) return NavigationDecision.Allow
        return if (isWeb(url) && hasGesture && !isRedirect) NavigationDecision.OpenOutside(url) else NavigationDecision.Cancel
    }

    /** For `onPageStarted`, the second guard: is this main-frame page still the manager's? */
    fun staysOnManager(url: String?, manager: Origin) = Origin.parse(url) == manager

    /** A Custom Tab gets only `http` and `https` URLs, and never one that carries the token. */
    fun mayOpenOutside(url: String, token: String?) = isWeb(url) && (token == null || !url.contains(token, ignoreCase = true))
}
