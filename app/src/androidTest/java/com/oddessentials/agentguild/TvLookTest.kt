package com.oddessentials.agentguild

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import com.oddessentials.agentguild.Harness.Companion.evaluate
import com.oddessentials.agentguild.Harness.Companion.evaluateString
import com.oddessentials.agentguild.model.Computer
import com.oddessentials.agentguild.model.Look
import com.oddessentials.agentguild.model.Looks
import com.oddessentials.agentguild.model.Theme
import com.oddessentials.agentguild.web.TvCheck
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The TV's Skin and Theme panel, with the TV check replaced, so it runs on any device. The fake
 * manager's page applies `&skin=` and `&theme=` as agent-guild's `theme.js` does.
 */
@RunWith(AndroidJUnit4::class)
class TvLookTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val harness = Harness(compose)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val manager = FakeServer(47941)
    private val token = "0123456789abcdef0123456789abcdef"
    private lateinit var computer: Computer
    private val failing = mutableListOf<FailingLooks>()

    @Before
    fun setUp() {
        manager.actAsManager()
        manager.page(
            "/",
            """<!doctype html><title>Fake manager</title>
            <script>
              window.agentGuildSkins = [{ id: 'professional', name: 'Professional' }, { id: 'orbital', name: 'Orbital' }, { id: 'grove', name: 'Grove' }];
              const params = new URLSearchParams(location.hash.slice(1));
              const root = document.documentElement;
              root.dataset.skin = agentGuildSkins.some((s) => s.id === params.get('skin')) ? params.get('skin') : 'professional';
              const asked = params.get('theme');
              root.dataset.theme = asked === 'light' || asked === 'dark' ? asked : matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
              window.agentGuild.postMessage(JSON.stringify({ source: 'agent-guild', type: 'ready', version: '1.9.0' }));
            </script>""",
        )
        computer = harness.add("${manager.origin}/#token=$token", "Fake")
        harness.app.tv = TvCheck { true }
    }

    @After
    fun tearDown() {
        manager.close()
        harness.close()
        failing.forEach { it.close() }
    }

    private fun saveLook(look: Look, key: String = computer.id) = runBlocking { harness.app.looks.set(key, look) }

    private fun savedLook(key: String = computer.id) = runBlocking { harness.app.looks.current()[key] }

    private fun failingLooks() = FailingLooks(harness.app).also {
        failing += it
        harness.app.looks = it.store
    }

    private fun openBoard(): Pair<ActivityScenario<MainActivity>, android.webkit.WebView> {
        val scenario = harness.launch()
        compose.onNodeWithTag("computer:Fake").click()
        harness.next(harness.ready)
        return scenario to harness.webView(scenario)
    }

    private fun key(action: Int, code: Int, repeat: Int = 0) {
        val time = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(time, time, action, code, repeat))
    }

    private fun pressMenu() {
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)
        compose.waitForIdle()
    }

    private fun pressBack() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun panelShown() = compose.onAllNodesWithTagCount("look-panel") == 1

    private fun openPanel() {
        pressMenu()
        compose.waitUntil(10_000) { panelShown() }
    }

    private fun untilPanelCloses() = compose.waitUntil(10_000) { !panelShown() }

    private fun choose(tag: String) {
        compose.onNodeWithTag(tag).click()
        compose.waitForIdle()
    }

    private fun skin(web: android.webkit.WebView) = evaluateString(web, "document.documentElement.dataset.skin")

    private fun theme(web: android.webkit.WebView) = evaluateString(web, "document.documentElement.dataset.theme")

    /** Marks the document, so a later check can tell that the page wasn't loaded again. */
    private fun mark(web: android.webkit.WebView) = evaluate(web, "window.marked = true")

    private fun stillMarked(web: android.webkit.WebView) = evaluate(web, "window.marked === true") == "true"

    /** The page's System theme, decided here instead of by the device's setting. */
    private fun systemIsDark(web: android.webkit.WebView, dark: Boolean) =
        evaluate(web, "window.matchMedia = (query) => ({ matches: $dark && query === '(prefers-color-scheme: dark)' }), true")

    private fun relaunchBoard(scenario: ActivityScenario<MainActivity>): Pair<ActivityScenario<MainActivity>, android.webkit.WebView> {
        scenario.close()
        return openBoard()
    }

    @Test
    fun theSavedLookIsInTheURLAndOnThePage() {
        saveLook(Look("orbital", Theme.Dark))
        val (_, web) = openBoard()
        assertEquals("#token=$token&layout=compact&expand=none&skin=orbital&theme=dark&view=yard", evaluateString(web, "location.hash"))
        assertEquals("orbital", skin(web))
        assertEquals("dark", theme(web))
    }

    @Test
    fun theMenuKeyOpensThePanelWithThePagesSkins() {
        saveLook(Look("grove"))
        openBoard()
        openPanel()
        listOf("skin:professional", "skin:orbital", "skin:grove", "theme:system", "theme:light", "theme:dark").forEach {
            compose.onNodeWithTag(it).assertExists()
        }
        compose.onNodeWithTag("skin:grove").assertIsSelected()
        compose.onNodeWithTag("theme:system").assertIsSelected()
    }

    @Test
    fun aPreviewChangesThePageWithoutLoadingIt() {
        val (_, web) = openBoard()
        mark(web)
        openPanel()
        choose("skin:orbital")
        harness.untilTrue(web, "document.documentElement.dataset.skin === 'orbital'")
        choose("theme:dark")
        harness.untilTrue(web, "document.documentElement.dataset.theme === 'dark'")
        assertTrue(stillMarked(web))
        assertEquals(null, savedLook())
    }

    @Test
    fun saveKeepsTheLookWithoutLoadingThePageAndTheNextLoadUsesIt() {
        val (scenario, web) = openBoard()
        mark(web)
        openPanel()
        choose("skin:orbital")
        choose("theme:dark")
        choose("look-save")
        untilPanelCloses()
        assertEquals(Look("orbital", Theme.Dark), savedLook())
        assertEquals("orbital", skin(web))
        assertEquals("dark", theme(web))
        assertTrue(stillMarked(web))
        compose.waitUntil(5_000) { harness.webViewHasFocus(scenario) }

        val (_, again) = relaunchBoard(scenario)
        assertEquals("#token=$token&layout=compact&expand=none&skin=orbital&theme=dark&view=yard", evaluateString(again, "location.hash"))
    }

    @Test
    fun cancelBackAndMenuPutTheSavedLookBack() {
        saveLook(Look("grove", Theme.Light))
        val (scenario, web) = openBoard()
        for (close in listOf({ choose("look-cancel") }, ::pressBack, ::pressMenu)) {
            openPanel()
            choose("skin:orbital")
            choose("theme:dark")
            harness.untilTrue(web, "document.documentElement.dataset.skin === 'orbital' && document.documentElement.dataset.theme === 'dark'")
            close()
            untilPanelCloses()
            harness.untilTrue(web, "document.documentElement.dataset.skin === 'grove' && document.documentElement.dataset.theme === 'light'")
            assertEquals(Look("grove", Theme.Light), savedLook())
            compose.waitUntil(5_000) { harness.webViewHasFocus(scenario) }
        }
        assertFalse(scenario.state == Lifecycle.State.DESTROYED)
    }

    @Test
    fun lightThenSystemThenCancelRemovesTheOverrideThenPutsLightBack() {
        saveLook(Look(theme = Theme.Light))
        val (scenario, web) = openBoard()
        assertTrue(evaluateString(web, "location.hash")!!.contains("&theme=light&"))
        systemIsDark(web, true)
        openPanel()
        compose.onNodeWithTag("theme:light").assertIsSelected()
        choose("theme:system")
        harness.untilTrue(web, "document.documentElement.dataset.theme === 'dark'")
        choose("look-cancel")
        untilPanelCloses()
        harness.untilTrue(web, "document.documentElement.dataset.theme === 'light'")
        assertEquals(Look(theme = Theme.Light), savedLook())

        val (_, again) = relaunchBoard(scenario)
        assertEquals("#token=$token&layout=compact&expand=none&theme=light&view=yard", evaluateString(again, "location.hash"))
        assertEquals("light", theme(again))
    }

    @Test
    fun darkThenSystemThenSaveRemovesTheThemeFromTheLookAndTheURL() {
        saveLook(Look("orbital", Theme.Dark))
        val (scenario, web) = openBoard()
        assertTrue(evaluateString(web, "location.hash")!!.contains("&theme=dark&"))
        systemIsDark(web, false)
        openPanel()
        choose("theme:system")
        harness.untilTrue(web, "document.documentElement.dataset.theme === 'light'")
        choose("look-save")
        untilPanelCloses()
        assertEquals(Look("orbital", Theme.System), savedLook())
        assertEquals("light", theme(web))

        val (_, again) = relaunchBoard(scenario)
        assertEquals("#token=$token&layout=compact&expand=none&skin=orbital&view=yard", evaluateString(again, "location.hash"))
        val system = evaluate(again, "matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'")
        assertEquals(system, evaluate(again, "document.documentElement.dataset.theme"))
    }

    @Test
    fun aFailedSaveKeepsTheSavedLookAndSaysSo() {
        val writes = failingLooks()
        saveLook(Look("grove", Theme.Light))
        writes.failing = true
        val (_, web) = openBoard()
        openPanel()
        choose("skin:orbital")
        choose("look-save")
        compose.waitUntil(10_000) { compose.onAllNodesWithTagCount("look-error") == 1 }
        assertTrue(panelShown())
        assertEquals(Look("grove", Theme.Light), savedLook())

        // Trying again once writes work saves it.
        writes.failing = false
        choose("look-save")
        untilPanelCloses()
        assertEquals(Look("orbital", Theme.Light), savedLook())
        assertEquals("orbital", skin(web))
    }

    @Test
    fun cancelAfterAFailedSavePutsTheSavedLookBack() {
        val writes = failingLooks()
        saveLook(Look("grove"))
        writes.failing = true
        val (_, web) = openBoard()
        openPanel()
        choose("skin:orbital")
        choose("look-save")
        compose.waitUntil(10_000) { compose.onAllNodesWithTagCount("look-error") == 1 }
        choose("look-cancel")
        untilPanelCloses()
        harness.untilTrue(web, "document.documentElement.dataset.skin === 'grove'")
        assertEquals(Look("grove"), savedLook())
    }

    /** Lets every answer the page owes the app arrive: the page answers scripts in order. */
    private fun settle(web: android.webkit.WebView) {
        evaluate(web, "true")
        compose.waitForIdle()
    }

    /** Whether the activity takes [event], as it does before any view sees it. */
    private fun taken(scenario: ActivityScenario<MainActivity>, event: KeyEvent): Boolean {
        var taken = false
        scenario.onActivity { taken = it.dispatchKeyEvent(event) }
        return taken
    }

    private fun menu(action: Int, repeat: Int = 0) = KeyEvent(0, 0, action, KeyEvent.KEYCODE_MENU, repeat)

    @Test
    fun aHeldMenuKeyActsOnce() {
        val (_, web) = openBoard()

        // Held over the open panel, Menu closes it once: a repeat acting would open it again.
        openPanel()
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU)
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, repeat = 1)
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, repeat = 2)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)
        settle(web)
        assertFalse(panelShown())

        // Held over the Yard, it opens the panel once: a repeat acting would close it.
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU)
        compose.waitUntil(10_000) { panelShown() }
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, repeat = 1)
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, repeat = 2)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)
        settle(web)
        assertTrue(panelShown())
        pressMenu()
        untilPanelCloses()
    }

    /** The page can't see a key the activity takes. Android's WebView doesn't pass Menu on either. */
    @Test
    fun theTvPageTakesEveryMenuEventItActsOn() {
        val (scenario, _) = openBoard()
        assertTrue(taken(scenario, menu(KeyEvent.ACTION_DOWN)))
        assertTrue(taken(scenario, menu(KeyEvent.ACTION_DOWN, repeat = 1)))
        assertTrue(taken(scenario, menu(KeyEvent.ACTION_UP)))
        compose.waitUntil(10_000) { panelShown() }
    }

    @Test
    fun aNewRendererClosesThePanelAndLoadsTheSavedLook() {
        saveLook(Look("grove"))
        val (scenario, first) = openBoard()
        openPanel()
        choose("skin:orbital")
        harness.untilTrue(first, "document.documentElement.dataset.skin === 'orbital'")
        instrumentation.runOnMainSync { assertTrue(WebViewCompat.getWebViewRenderProcess(first)!!.terminate()) }

        val second = harness.webView(scenario, other = first)
        harness.next(harness.ready)
        untilPanelCloses()
        assertEquals("grove", skin(second))
        assertEquals(Look("grove"), savedLook())
        compose.waitUntil(5_000) { harness.webViewHasFocus(scenario) }
        openPanel()
        compose.onNodeWithTag("skin:grove").assertIsSelected()
    }

    @Test
    fun theListOpensThePanelForAComputer() {
        saveLook(Look("orbital"))
        harness.launch()
        compose.onNode(hasContentDescription("More for Fake")).click()
        compose.onNodeWithText("Skin and Theme").click()
        harness.next(harness.ready)
        compose.waitUntil(10_000) { panelShown() }
        compose.onNodeWithTag("skin:orbital").assertIsSelected()
    }

    @Test
    fun theDemoHasItsOwnLookOnATvOnly() {
        harness.launch()
        compose.onNode(hasContentDescription("More for the demo")).click()
        compose.onNodeWithText("Skin and Theme").assertExists()
    }

    @Test
    fun aPhoneHasNoPanel() {
        harness.app.tv = TvCheck { false }
        val scenario = harness.launch()
        assertEquals(0, compose.onAllNodes(hasContentDescription("More for the demo")).fetchSemanticsNodes().size)
        compose.onNode(hasContentDescription("More for Fake")).click()
        assertEquals(0, compose.onAllNodesWithTextCount("Skin and Theme"))
        pressBack()
        compose.onNodeWithTag("computer:Fake").click()
        harness.next(harness.ready)
        val web = harness.webView(scenario)
        pressMenu()
        compose.waitForIdle()
        assertFalse(panelShown())
        scenario.onActivity { assertEquals(null, it.menuKey) }
        assertEquals("#token=$token&view=yard", evaluateString(web, "location.hash"))
    }

    @Test
    fun removingAComputerRemovesItsLook() {
        saveLook(Look("orbital"))
        saveLook(Look("grove"), key = Looks.LOOK_DEMO)
        harness.launch()
        compose.onNode(hasContentDescription("More for Fake")).click()
        compose.onNodeWithText("Remove").click()
        compose.waitUntil(10_000) { savedLook() == null }
        assertEquals(Look("grove"), savedLook(Looks.LOOK_DEMO))
    }

    @Test
    fun theManagersYardTakesTheSavedLookAndItsOwnSkins() {
        val scratch = harness.addManager("Scratch")
        saveLook(Look("orbital", Theme.Dark), key = scratch.id)
        val scenario = harness.launch()
        compose.onNodeWithTag("computer:Scratch").click()
        harness.next(harness.ready)
        val web = harness.webView(scenario)
        assertEquals("orbital", skin(web))
        assertEquals("dark", theme(web))
        mark(web)

        openPanel()
        listOf("guild", "professional", "orbital", "grove", "gnomeland", "goblinville").forEach { compose.onNodeWithTag("skin:$it").assertExists() }
        compose.onNodeWithTag("skin:orbital").assertIsSelected()
        compose.onNodeWithTag("theme:dark").assertIsSelected()
        choose("skin:grove")
        harness.untilTrue(web, "document.documentElement.dataset.skin === 'grove'")
        choose("look-save")
        untilPanelCloses()
        assertTrue(stillMarked(web))
        assertEquals(Look("grove", Theme.Dark), savedLook(scratch.id))
        assertFalse(compose.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("look-panel"))).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun cancelBackAndMenuRestoreASkinThePageDoesntList() {
        val (_, web) = openBoard()
        evaluate(web, "document.documentElement.dataset.skin = 'vapor', true")
        for (close in listOf({ choose("look-cancel") }, ::pressBack, ::pressMenu)) {
            openPanel()
            choose("skin:orbital")
            harness.untilTrue(web, "document.documentElement.dataset.skin === 'orbital'")
            close()
            untilPanelCloses()
            harness.untilTrue(web, "document.documentElement.dataset.skin === 'vapor'")
        }
    }

    @Test
    fun cancelLeavesMissingAttributesMissing() {
        val (_, web) = openBoard()
        evaluate(web, "delete document.documentElement.dataset.skin, delete document.documentElement.dataset.theme, true")
        openPanel()
        choose("skin:orbital")
        choose("theme:dark")
        harness.untilTrue(web, "document.documentElement.dataset.skin === 'orbital'")
        choose("look-cancel")
        untilPanelCloses()
        harness.untilTrue(web, "!document.documentElement.hasAttribute('data-skin') && !document.documentElement.hasAttribute('data-theme')")
    }
}
