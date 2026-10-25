package com.oddessentials.agentguild.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTest {
    @Test
    fun theURLCarriesTheTokenAndView() {
        val page = Page("https://mini.tail1234.ts.net", "abc123")
        assertEquals("https://mini.tail1234.ts.net/#token=abc123&view=yard", page.url(PageView.Yard))
        assertEquals("https://mini.tail1234.ts.net/#token=abc123&view=cards", page.url(PageView.Cards))
        assertEquals("https://mini.tail1234.ts.net/#token=abc123", page.url(null))
        assertEquals(Origin("https", "mini.tail1234.ts.net", 443), page.origin)
    }

    @Test
    fun theCompactURLAsksForTheCompactLayout() {
        val page = Page("https://mini.tail1234.ts.net", "abc123")
        assertEquals("https://mini.tail1234.ts.net/#token=abc123&layout=compact&expand=none&view=yard", page.url(PageView.Yard, compact = true))
        assertEquals("https://oddessentials.github.io/agent-guild/#layout=compact&expand=none&view=yard", Page.demo.url(PageView.Yard, compact = true))
    }

    @Test
    fun theCompactURLCarriesTheLook() {
        val page = Page("https://mini.tail1234.ts.net", "abc123")
        val base = "https://mini.tail1234.ts.net/#token=abc123&layout=compact&expand=none"
        assertEquals("$base&view=yard", page.url(PageView.Yard, compact = true, look = Look.Default))
        assertEquals("$base&skin=orbital&view=yard", page.url(PageView.Yard, compact = true, look = Look("orbital")))
        assertEquals("$base&theme=dark&view=yard", page.url(PageView.Yard, compact = true, look = Look(theme = Theme.Dark)))
        assertEquals("$base&skin=grove&theme=light&view=yard", page.url(PageView.Yard, compact = true, look = Look("grove", Theme.Light)))
        assertEquals(
            "https://oddessentials.github.io/agent-guild/#layout=compact&expand=none&skin=gnomeland&theme=dark&view=yard",
            Page.demo.url(PageView.Yard, compact = true, look = Look("gnomeland", Theme.Dark)),
        )
    }

    @Test
    fun theSystemThemeLeavesTheThemeOut() {
        val url = Page("https://mini.tail1234.ts.net", "abc123").url(PageView.Yard, compact = true, look = Look("orbital", Theme.System))
        assertEquals("https://mini.tail1234.ts.net/#token=abc123&layout=compact&expand=none&skin=orbital&view=yard", url)
    }

    @Test
    fun theFullPageTakesNoLook() {
        val page = Page("https://mini.tail1234.ts.net", "abc123")
        assertEquals("https://mini.tail1234.ts.net/#token=abc123&view=yard", page.url(PageView.Yard, look = Look("orbital", Theme.Dark)))
    }

    @Test
    fun aLookIsSavedForAComputerOrTheDemo() {
        assertEquals("id1", Destination.Saved("id1").lookKey)
        assertEquals(Looks.LOOK_DEMO, Destination.Demo.lookKey)
    }

    @Test
    fun theDemoNeedsNoToken() {
        assertEquals("https://oddessentials.github.io/agent-guild/#view=yard", Page.demo.url(PageView.Yard))
        assertEquals("https://oddessentials.github.io/agent-guild/", Page.demo.url(null))
        assertEquals(Origin("https", "oddessentials.github.io", 443), Page.demo.origin)
    }

    @Test
    fun aNewAddressOrTokenIsANewPage() {
        val page = Page("https://mini.tail1234.ts.net", "old")
        assertEquals(page, Page("https://mini.tail1234.ts.net", "old"))
        assertNotEquals(page, Page("https://mini.tail1234.ts.net", "new"))
        assertNotEquals(page, Page("https://mini.tail1234.ts.net:8443", "old"))
    }

    @Test
    fun viewsComeFromAFixedList() {
        assertEquals(PageView.Yard, PageView.of("yard"))
        assertEquals(PageView.Cards, PageView.of("cards"))
        assertNull(PageView.of("Yard"))
        assertNull(PageView.of("'); alert(1); ('"))
    }
}
