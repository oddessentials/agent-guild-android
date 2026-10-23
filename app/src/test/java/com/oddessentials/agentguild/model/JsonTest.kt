package com.oddessentials.agentguild.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonTest {
    @Test
    fun readsValues() {
        val text = """ { "a": [1, -2.5, 3e2], "b": true, "c": null, "d": "x\"\\\/\n\u00e9" } """
        assertEquals(mapOf("a" to listOf(1.0, -2.5, 300.0), "b" to true, "c" to null, "d" to "x\"\\/\né"), Json.parse(text))
        assertEquals(emptyMap<String, Any?>(), Json.parse("{}"))
        assertEquals(emptyList<Any?>(), Json.parse("[ ]"))
    }

    @Test
    fun refusesWhatIsntStrictJson() {
        val refused = listOf(
            "", "{", "{\"a\":1,}", "[1,]", "{'a':1}", "{a:1}", "01", "1.", "+1", "NaN", "\"a\nb\"",
            "{\"a\":1}{}", "/*x*/1", "{\"a\":1,\"a\":2}", "\"\\x\"", "tru", "[".repeat(100) + "]".repeat(100),
        )
        for (text in refused) assertNull(text, Json.parseOrNull(text))
    }

    @Test
    fun quotesStringsBackToTheSameText() {
        val text = "Studio \"Mac\" \\ \n\t\u0001 é"
        assertEquals(text, Json.parse(Json.quote(text)))
    }
}
