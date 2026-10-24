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
        assertEquals("https://mini.tail1234.ts.net/#token=abc123&layout=compact&view=yard", page.url(PageView.Yard, compact = true))
        assertEquals("https://oddessentials.github.io/agent-guild/#layout=compact&view=yard", Page.demo.url(PageView.Yard, compact = true))
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
