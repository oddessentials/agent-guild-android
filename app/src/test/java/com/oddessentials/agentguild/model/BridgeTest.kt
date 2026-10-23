package com.oddessentials.agentguild.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeTest {
    private val manager = Origin("https", "mini.tail1234.ts.net", 443)

    @Test
    fun acceptsOnlyTheManagersMainFrame() {
        assertTrue(Bridge.accepts(true, "https://mini.tail1234.ts.net", manager))
        assertTrue(Bridge.accepts(true, "HTTPS://Mini.tail1234.ts.net:443", manager))
        assertFalse(Bridge.accepts(false, "https://mini.tail1234.ts.net", manager))
        assertFalse(Bridge.accepts(true, "https://www.youtube-nocookie.com", manager))
        assertFalse(Bridge.accepts(true, "https://mini.tail1234.ts.net:8443", manager))
        assertFalse(Bridge.accepts(true, "http://mini.tail1234.ts.net", manager))
        assertFalse(Bridge.accepts(true, "https://mini.tail1234.ts.net.evil.example", manager))
        assertFalse(Bridge.accepts(true, "null", manager))
        assertFalse(Bridge.accepts(true, null, manager))
    }

    @Test
    fun parsesReadyAndOpen() {
        assertEquals(BridgeMessage.Ready("1.9.0"), Bridge.parse("""{"source":"agent-guild","type":"ready","version":"1.9.0"}"""))
        assertEquals(
            BridgeMessage.Open("https://github.com/oddessentials"),
            Bridge.parse("""{"source":"agent-guild","type":"open","url":"https://github.com/oddessentials"}"""),
        )
        assertEquals(
            BridgeMessage.Open("http://example.com/a?b#c"),
            Bridge.parse("""{"source":"agent-guild","type":"open","url":"http://example.com/a?b#c","extra":1}"""),
        )
    }

    @Test
    fun rejectsEverythingElse() {
        val rejected = listOf(
            null,
            "",
            "ready",
            "null",
            "[\"ready\"]",
            """{"source":"agent-guild","type":"open","url":"file:///etc/passwd"}""",
            """{"source":"agent-guild","type":"open","url":"javascript:alert(1)"}""",
            """{"source":"agent-guild","type":"open","url":"intent://x#Intent;end"}""",
            """{"source":"agent-guild","type":"open","url":7}""",
            """{"source":"agent-guild","type":"open"}""",
            """{"source":"agent-guild","type":"ready"}""",
            """{"source":"agent-guild","type":"ready","version":null}""",
            """{"source":"agent-guild-vscode","type":"ready","version":"1.9.0"}""",
            """{"source":"agent-guild","type":"view","view":"yard"}""",
            """{"source":"agent-guild","type":"expand"}""",
            """{"source":"agent-guild","type":"look","skin":"guild","theme":"dark"}""",
            """{"source":"agent-guild","type":["ready"],"version":"1.9.0"}""",
            """{"type":"ready","version":"1.9.0"}""",
            """{"source":"agent-guild","type":"ready","version":"1.9.0"} trailing""",
            """{'source':'agent-guild','type':'ready','version':'1.9.0'}""",
            """{"source":"agent-guild","type":"ready","type":"open","version":"1.9.0"}""",
        )
        for (text in rejected) assertNull(text, Bridge.parse(text))
    }

    @Test
    fun theViewScriptPostsAFixedMessageToThePagesOwnOrigin() {
        val script = Bridge.viewScript(PageView.Cards)
        assertTrue(script.contains("window.postMessage({ source: 'agent-guild-app', type: 'view', view: 'cards' }, location.origin)"))
        assertTrue(script.contains("document.readyState === 'complete'"))
        assertTrue(Bridge.viewScript(PageView.Yard).contains("view: 'yard'"))
    }
}
