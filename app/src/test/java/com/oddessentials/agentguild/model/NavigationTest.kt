package com.oddessentials.agentguild.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    private val manager = Origin("https", "mini.tail1234.ts.net", 443)

    private fun decide(url: String?, mainFrame: Boolean = true, gesture: Boolean = true, redirect: Boolean = false) =
        Navigation.decide(url, mainFrame, gesture, redirect, manager)

    @Test
    fun keepsTheMainFrameOnTheManager() {
        assertEquals(NavigationDecision.Allow, decide("https://mini.tail1234.ts.net:443/api/v1/info"))
        assertEquals(NavigationDecision.Allow, decide("https://mini.tail1234.ts.net/#view=yard", gesture = false))
        assertEquals(NavigationDecision.Allow, decide("https://mini.tail1234.ts.net/", redirect = true))
    }

    @Test
    fun aFollowedLinkElsewhereOpensOutside() {
        assertEquals(NavigationDecision.OpenOutside("https://github.com/oddessentials"), decide("https://github.com/oddessentials"))
        assertEquals(NavigationDecision.OpenOutside("https://mini.tail1234.ts.net:8443/"), decide("https://mini.tail1234.ts.net:8443/"))
        assertEquals(NavigationDecision.OpenOutside("http://mini.tail1234.ts.net/"), decide("http://mini.tail1234.ts.net/"))
    }

    @Test
    fun anythingElseElsewhereIsCancelled() {
        assertEquals(NavigationDecision.Cancel, decide("https://evil.example/", gesture = false))
        assertEquals(NavigationDecision.Cancel, decide("https://evil.example/", redirect = true))
        assertEquals(NavigationDecision.Cancel, decide("https://evil.example/", gesture = false, redirect = true))
        assertEquals(NavigationDecision.Cancel, decide("tel:5551234"))
        assertEquals(NavigationDecision.Cancel, decide("intent://scan/#Intent;scheme=zxing;end"))
        assertEquals(NavigationDecision.Cancel, decide("file:///sdcard/x.html"))
        assertEquals(NavigationDecision.Cancel, decide("javascript:alert(1)"))
        assertEquals(NavigationDecision.Cancel, decide(null))
    }

    @Test
    fun letsSubframesLoad() {
        assertEquals(NavigationDecision.Allow, decide("https://www.youtube-nocookie.com/embed/x", mainFrame = false, gesture = false))
    }

    @Test
    fun theSecondGuardStopsAPageElsewhere() {
        assertTrue(Navigation.staysOnManager("https://mini.tail1234.ts.net/", manager))
        assertFalse(Navigation.staysOnManager("https://evil.example/", manager))
        assertFalse(Navigation.staysOnManager("about:blank", manager))
        assertFalse(Navigation.staysOnManager("data:text/html,x", manager))
        assertFalse(Navigation.staysOnManager(null, manager))
    }

    @Test
    fun opensOutsideOnlyWebLinksWithoutTheToken() {
        val token = "0123456789abcdef0123456789abcdef"
        assertTrue(Navigation.mayOpenOutside("https://github.com/oddessentials", token))
        assertTrue(Navigation.mayOpenOutside("https://github.com/oddessentials", null))
        assertFalse(Navigation.mayOpenOutside("https://mini.tail1234.ts.net/#token=$token", token))
        assertFalse(Navigation.mayOpenOutside("https://evil.example/?t=${token.uppercase()}", token))
        assertFalse(Navigation.mayOpenOutside("javascript:alert(1)", token))
        assertFalse(Navigation.mayOpenOutside("file:///etc/passwd", null))
    }
}
