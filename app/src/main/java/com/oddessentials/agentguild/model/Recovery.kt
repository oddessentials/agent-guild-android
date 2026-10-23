package com.oddessentials.agentguild.model

/** What the page screen does after each check of its computer. */
object Recovery {
    /**
     * A rejected token or a changed address leaves the page for signing in again. Other failures keep
     * a page that is showing, with a banner; they never show one that isn't.
     */
    fun showsPage(after: HealthResult, showing: Boolean) = when (after) {
        is HealthResult.Supported -> true
        is HealthResult.Failed -> if (after.problem.needsSignIn) false else showing
    }
}
