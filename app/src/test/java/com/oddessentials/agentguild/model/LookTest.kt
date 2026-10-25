package com.oddessentials.agentguild.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {
    private val six = """[{"id":"guild","name":"Guild"},{"id":"professional","name":"Professional"},{"id":"orbital","name":"Orbital"},
        {"id":"grove","name":"Grove"},{"id":"gnomeland","name":"Gnomeland"},{"id":"goblinville","name":"Goblinville"}]"""

    @Test
    fun readsThePagesSkinsAndLook() {
        val look = Looks.parse("""{"skins":$six,"skin":"orbital","theme":"dark"}""")!!
        assertEquals(listOf("guild", "professional", "orbital", "grove", "gnomeland", "goblinville"), look.skins.map { it.id })
        assertEquals(Skin("goblinville", "Goblinville"), look.skins.last())
        assertEquals("orbital", look.skin)
        assertEquals(Theme.Dark, look.theme)
    }

    @Test
    fun keepsOnlySkinsWithAValidIdAndName() {
        val skins = """[
            {"id":"guild","name":"Guild"},
            {"id":"Orbital","name":"Upper case"},
            {"id":"or bital","name":"Space"},
            {"id":"x');alert(1);('","name":"Script"},
            {"id":"${"a".repeat(41)}","name":"Long id"},
            {"id":"grove","name":"${"G".repeat(41)}"},
            {"id":"gnomeland","name":"   "},
            {"id":7,"name":"Number"},
            {"name":"No id"},
            "goblinville",
            null,
            {"id":"guild","name":"Second Guild"},
            {"id":"orbital","name":"  Orbital  "}
        ]"""
        val look = Looks.parse("""{"skins":$skins,"skin":"guild","theme":"light"}""")!!
        assertEquals(listOf(Skin("guild", "Guild"), Skin("orbital", "Orbital")), look.skins)
    }

    @Test
    fun keepsAtMostThirtyTwoSkins() {
        val many = (1..40).joinToString(",", "[", "]") { """{"id":"s${"a".repeat(it)}","name":"Skin $it"}""" }
        assertEquals(Looks.MAX_SKINS, Looks.parse("""{"skins":$many}""")!!.skins.size)
    }

    @Test
    fun theShownSkinMustBeListedAndTheThemeLightOrDark() {
        val look = Looks.parse("""{"skins":$six,"skin":"vapor","theme":"sepia"}""")!!
        assertNull(look.skin)
        assertEquals("vapor", look.shownSkin)
        assertNull(look.theme)
        assertNull(Looks.parse("""{"skins":$six,"skin":"x');alert(1);('"}""")!!.shownSkin)
        assertNull(Looks.parse("""{"skins":$six,"skin":7,"theme":null}""")!!.theme)
    }

    @Test
    fun aPageWithoutSkinsListsNone() {
        assertEquals(PageLook(emptyList(), null, Theme.Light, "guild"), Looks.parse("""{"skins":null,"skin":"guild","theme":"light"}"""))
        assertEquals(PageLook(emptyList(), null, null, null), Looks.parse("""{"skins":{"id":"guild"}}"""))
    }

    @Test
    fun anythingButAnObjectIsNoLook() {
        assertNull(Looks.parse(null))
        assertNull(Looks.parse("null"))
        assertNull(Looks.parse("\"text\""))
        assertNull(Looks.parse("[]"))
        assertNull(Looks.parse("{skins:[]}"))
    }

    @Test
    fun skinIdsAreLowerCaseLetters() {
        assertTrue(Looks.isSkinId("goblinville"))
        assertFalse(Looks.isSkinId(""))
        assertFalse(Looks.isSkinId("Guild"))
        assertFalse(Looks.isSkinId("guild\n"))
        assertFalse(Looks.isSkinId("a".repeat(41)))
        assertThrows(IllegalArgumentException::class.java) { Look("x'") }
        assertThrows(IllegalArgumentException::class.java) { Looks.previewScript("x'", Theme.Light) }
    }

    @Test
    fun theAddressTakesTheLookAndKeepsItsOtherParameters() {
        val script = Looks.addressScript(Look("orbital", Theme.Dark))
        assertTrue(script.contains("new URLSearchParams(location.hash.slice(1))"))
        assertTrue(script.contains("params.set('skin', 'orbital');"))
        assertTrue(script.contains("params.set('theme', 'dark');"))
        assertTrue(script.contains("history.replaceState("))
        val system = Looks.addressScript(Look.Default)
        assertTrue(system.contains("params.delete('skin');"))
        assertTrue(system.contains("params.delete('theme');"))
    }

    @Test
    fun aChosenThemeIsSetAsIs() {
        val script = Looks.previewScript("orbital", Theme.Dark)
        assertTrue(script.contains("const skin = 'orbital';"))
        assertTrue(script.contains("const theme = 'dark';"))
        assertFalse(script.contains("matchMedia"))
        assertTrue(Looks.previewScript(null, Theme.Light).contains("const skin = null;"))
    }

    @Test
    fun theSystemThemeIsResolvedAsThePageDoes() {
        val script = Looks.previewScript("orbital", Theme.System)
        assertTrue(script.contains("localStorage.getItem('agentGuild.theme')"))
        assertTrue(script.contains("matchMedia('(prefers-color-scheme: dark)')"))
        assertFalse(script.contains("const theme = 'light'"))
        assertFalse(script.contains("const theme = 'dark'"))
    }

    @Test
    fun savedLooksRoundTrip() {
        val looks = mapOf(
            "id1" to Look("orbital", Theme.Dark),
            "id2" to Look(theme = Theme.Light),
            Looks.LOOK_DEMO to Look("grove"),
            "id3" to Look.Default,
        )
        val text = Looks.encode(looks)
        assertEquals("""{"looks":{"id1":{"skin":"orbital","theme":"dark"},"id2":{"theme":"light"},"demo":{"skin":"grove"},"id3":{}}}""", text)
        assertEquals(looks, Looks.decode(text))
        assertEquals(emptyMap<String, Look>(), Looks.decode(Looks.encode(emptyMap())))
    }

    @Test
    fun anInvalidSavedSkinOrThemeReadsAsThePagesOwn() {
        val looks = Looks.decode("""{"looks":{"id1":{"skin":"Vapor","theme":"sepia"},"id2":"orbital","id3":{"skin":"grove","theme":"dark"}}}""")
        assertEquals(mapOf("id1" to Look.Default, "id3" to Look("grove", Theme.Dark)), looks)
        assertNull(Looks.decode("""{"computers":[]}"""))
        assertNull(Looks.decode("not json"))
    }

    @Test
    fun themesComeFromAFixedList() {
        assertEquals(Theme.Light, Theme.explicit("light"))
        assertEquals(Theme.Dark, Theme.explicit("dark"))
        assertNull(Theme.explicit(null))
        assertNull(Theme.explicit("system"))
        assertNull(Theme.explicit("Dark"))
    }

    @Test
    fun restoringPutsBackAnySafeSkinWithoutCheckingTheList() {
        val script = Looks.restoreScript("vapor", Theme.Dark)
        assertTrue(script.contains("root.dataset.skin = 'vapor';"))
        assertTrue(script.contains("root.dataset.theme = 'dark';"))
        assertFalse(script.contains("agentGuildSkins"))
        assertFalse(script.contains("matchMedia"))
    }

    @Test
    fun restoringLeavesAMissingAttributeMissing() {
        val script = Looks.restoreScript(null, null)
        assertTrue(script.contains("delete root.dataset.skin;"))
        assertTrue(script.contains("delete root.dataset.theme;"))
        assertFalse(script.contains("root.dataset.skin ="))
        assertThrows(IllegalArgumentException::class.java) { Looks.restoreScript("x'", Theme.Light) }
        assertThrows(IllegalArgumentException::class.java) { Looks.restoreScript("guild", Theme.System) }
    }
}
