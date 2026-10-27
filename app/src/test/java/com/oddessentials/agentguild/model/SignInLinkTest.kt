package com.oddessentials.agentguild.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInLinkTest {
    private val token = "0123456789abcdef0123456789abcdef0123456789abcdef"

    private fun parse(text: String, debug: Boolean = false) = SignInLink.parse(text, allowLoopbackHttp = debug)

    @Test
    fun readsTheAddressAndToken() {
        val link = parse("  https://Mini.Tail1234.ts.net/#token=$token\n")!!
        assertEquals("https://mini.tail1234.ts.net", link.address)
        assertEquals(token, link.token)
        assertEquals("mini", link.defaultName)
    }

    @Test
    fun keepsAPortButNotTheDefaultOne() {
        assertEquals("https://proxy.example.com:8443", parse("https://proxy.example.com:8443/#token=$token")!!.address)
        assertEquals("https://proxy.example.com", parse("https://proxy.example.com:443/#token=$token")!!.address)
        assertEquals("https://proxy.example.com", parse("HTTPS://proxy.example.com#token=$token")!!.address)
    }

    @Test
    fun acceptsTheShortestToken() {
        assertEquals("a".repeat(32), parse("https://mini.tail1234.ts.net/#token=${"a".repeat(32)}")!!.token)
    }

    @Test
    fun rejectsEverythingElse() {
        val rejected = listOf(
            "",
            "   ",
            "not a link",
            "mini.tail1234.ts.net/#token=$token",
            "http://mini.tail1234.ts.net/#token=$token",
            "ftp://mini.tail1234.ts.net/#token=$token",
            "https:mini.tail1234.ts.net#token=$token",
            "https:///#token=$token",
            "https://user@mini.tail1234.ts.net/#token=$token",
            "https://user:pass@mini.tail1234.ts.net/#token=$token",
            "https://mini.tail1234.ts.net/guild/#token=$token",
            "https://mini.tail1234.ts.net//#token=$token",
            "https://mini.tail1234.ts.net/?a=b#token=$token",
            "https://mini.tail1234.ts.net/?#token=$token",
            "https://mini.tail1234.ts.net/",
            "https://mini.tail1234.ts.net/#",
            "https://mini.tail1234.ts.net/#token=",
            "https://mini.tail1234.ts.net/#token=${"a".repeat(31)}",
            "https://mini.tail1234.ts.net/#token=${token.uppercase()}",
            "https://mini.tail1234.ts.net/#token=${token}g",
            "https://mini.tail1234.ts.net/#token=%61$token",
            "https://mini.tail1234.ts.net/#token=$token&view=yard",
            "https://mini.tail1234.ts.net/#view=yard&token=$token",
            "https://mini.tail1234.ts.net/#token=$token&token=$token",
            "https://mini.tail1234.ts.net/#Token=$token",
            "https://mini.tail1234.ts.net/# token=$token",
            "https://mini.tail1234.ts.net/#token=$token https://other.example/",
            "https://mini.tail1234.ts.net/#token=$token\nhttps://other.example/",
            "https://mini_tail.ts.net/#token=$token",
        )
        for (text in rejected) assertNull(text, parse(text, debug = true))
    }

    @Test
    fun debugBuildsAlsoAcceptTheScratchManager() {
        val link = parse("http://127.0.0.1:47931/#token=$token", debug = true)!!
        assertEquals("http://127.0.0.1:47931", link.address)
        assertNull(parse("http://127.0.0.1:47931/#token=$token", debug = false))
        assertNull(parse("http://127.0.0.1/#token=$token", debug = true))
        assertNull(parse("http://localhost:47931/#token=$token", debug = true))
        assertNull(parse("http://10.0.2.2:47931/#token=$token", debug = true))
    }

    private fun address(text: String, debug: Boolean = false) = SignInLink.parseAddress(text, allowLoopbackHttp = debug)

    @Test
    fun aTypedAddressSavesAsTheLinkWouldSaveIt() {
        assertEquals("https://mini.tail1234.ts.net", address("  Mini.Tail1234.ts.net\n"))
        assertEquals("https://mini.tail1234.ts.net", address("https://mini.tail1234.ts.net/"))
        assertEquals("https://proxy.example.com:8443", address("proxy.example.com:8443"))
        assertEquals("https://proxy.example.com", address("proxy.example.com:443"))
        assertEquals(parse("https://Proxy.Example.com:8443/#token=$token")!!.address, address("PROXY.example.com:8443"))
    }

    @Test
    fun rejectsAnythingMoreThanAnAddress() {
        val rejected = listOf(
            "",
            "   ",
            "not an address",
            "http://mini.tail1234.ts.net",
            "ftp://mini.tail1234.ts.net",
            "https:mini.tail1234.ts.net",
            "user@mini.tail1234.ts.net",
            "mini.tail1234.ts.net/guild",
            "mini.tail1234.ts.net/?a=b",
            "mini.tail1234.ts.net/#token=$token",
            "https://mini.tail1234.ts.net/#token=$token",
            "mini.tail1234.ts.net:99999",
            "mini_tail.ts.net",
            "mini.tail1234.ts.net other.example",
        )
        for (text in rejected) assertNull(text, address(text, debug = true))
    }

    @Test
    fun debugBuildsAlsoAcceptTheScratchManagerAddress() {
        assertEquals("http://127.0.0.1:47931", address("http://127.0.0.1:47931", debug = true))
        assertNull(address("http://127.0.0.1:47931", debug = false))
        assertNull(address("http://127.0.0.1", debug = true))
        assertNull(address("http://10.0.2.2:47931", debug = true))
    }

    @Test
    fun signsInAgainOnlyToTheSameComputer() {
        val studio = Computer("1", "Studio Mac", "https://mini.tail1234.ts.net:8443", "old")
        assertTrue(parse("https://mini.tail1234.ts.net/#token=$token")!!.isFor(studio))
        assertTrue(parse("https://MINI.tail1234.ts.net:8444/#token=$token")!!.isFor(studio))
        assertFalse(parse("https://workbench.tail1234.ts.net/#token=$token")!!.isFor(studio))
        assertFalse(parse("https://mini.other.ts.net/#token=$token")!!.isFor(studio))
    }

    @Test
    fun neverPrintsTheToken() {
        val link = parse("https://mini.tail1234.ts.net/#token=$token")!!
        assertFalse(link.toString().contains(token))
        assertFalse(Computer("1", "Studio", link.address, link.token).toString().contains(token))
        assertFalse(Page(link.address, link.token).toString().contains(token))
    }
}
