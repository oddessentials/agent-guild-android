package com.oddessentials.agentguild.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginTest {
    @Test
    fun fillsInTheDefaultPort() {
        assertEquals(Origin("https", "mini.tail1234.ts.net", 443), Origin.parse("HTTPS://Mini.tail1234.ts.net/api?x#y"))
        assertEquals(Origin("https", "mini.tail1234.ts.net", 443), Origin.parse("https://mini.tail1234.ts.net:443"))
        assertEquals(Origin("http", "127.0.0.1", 47931), Origin.parse("http://127.0.0.1:47931/"))
        assertEquals(Origin("http", "example.com", 80), Origin.parse("http://example.com"))
        assertEquals(Origin("https", "[::1]", 8443), Origin.parse("https://[::1]:8443/"))
    }

    @Test
    fun theRuleLeavesOutTheDefaultPort() {
        assertEquals("https://mini.tail1234.ts.net", Origin.parse("https://mini.tail1234.ts.net:443/")!!.rule)
        assertEquals("https://mini.tail1234.ts.net:8443", Origin.parse("https://mini.tail1234.ts.net:8443/")!!.rule)
        assertEquals("http://127.0.0.1:47931", Origin.parse("http://127.0.0.1:47931")!!.rule)
    }

    @Test
    fun failsClosed() {
        val refused = listOf(
            null, "", "mini.tail1234.ts.net", "file:///etc/passwd", "javascript:alert(1)", "data:text/html,x",
            "intent://x#Intent;end", "https://", "https:///x", "https://user@mini.tail1234.ts.net/",
            "https://mini.tail1234.ts.net:/", "https://mini.tail1234.ts.net:99999/",
            "https://mini.tail1234.ts.net:0/", "https://mini tail.ts.net/", "about:blank",
        )
        for (url in refused) assertNull(url, Origin.parse(url))
    }

    @Test
    fun aBackslashEndsTheHostAsInChromium() {
        assertEquals(Origin("https", "evil.example", 443), Origin.parse("https://evil.example\\mini.tail1234.ts.net/"))
        assertEquals(Origin("https", "evil.example", 443), Origin.parse("https://evil.example\\@mini.tail1234.ts.net/"))
    }
}
