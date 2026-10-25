package com.oddessentials.agentguild

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oddessentials.agentguild.Harness.Companion.evaluateString
import com.oddessentials.agentguild.model.Look
import com.oddessentials.agentguild.model.Theme
import com.oddessentials.agentguild.web.TvCheck
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app driven by remote keys on an Android TV device, such as the agtv emulator; skipped elsewhere. */
@RunWith(AndroidJUnit4::class)
class TvRemoteTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val harness = Harness(compose)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        assumeTrue(TvCheck.System.isTv(harness.app))
    }

    @After
    fun tearDown() = harness.close()

    /**
     * Compose's text fields move focus only for keys from a D-pad device, and from a virtual one only
     * through HDMI-CEC, so the keys arrive as a CEC remote's.
     */
    private fun press(key: Int) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            instrumentation.sendKeySync(KeyEvent(time, time, action, key, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_HDMI))
        }
        compose.waitForIdle()
    }

    private fun isFocused(matcher: SemanticsMatcher) = compose.onAllNodes(matcher and isFocused()).fetchSemanticsNodes().isNotEmpty()

    /** Presses [key] until [matcher]'s node has focus. */
    private fun moveTo(key: Int, matcher: SemanticsMatcher) {
        repeat(10) {
            if (isFocused(matcher)) return
            press(key)
        }
        check(isFocused(matcher)) {
            "The remote never reached ${matcher.description}; focused: ${compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.TestTag) ?: it.config.getOrNull(SemanticsProperties.Text) }}"
        }
    }

    private fun imeShown(scenario: ActivityScenario<MainActivity>): Boolean {
        var shown = false
        scenario.onActivity { activity ->
            shown = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        return shown
    }

    private fun startAdding(): ActivityScenario<MainActivity> {
        val scenario = harness.launch()
        moveTo(KeyEvent.KEYCODE_DPAD_UP, hasText("Add Computer"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, hasTestTag("link"))
        compose.waitUntil(10_000) { imeShown(scenario) }
        return scenario
    }

    private fun closeKeyboard(scenario: ActivityScenario<MainActivity>) {
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { !imeShown(scenario) }
        compose.onNodeWithTag("link").assertExists()
        assertNotEquals(Lifecycle.State.DESTROYED, scenario.state)
    }

    @Test
    fun theRemoteAddsAComputerAndOpensTheBoard() {
        val scenario = startAdding()
        instrumentation.sendStringSync(Harness.managerLink)
        closeKeyboard(scenario)
        compose.onNodeWithTag("link").assert(hasText(Harness.managerLink))
        moveTo(KeyEvent.KEYCODE_DPAD_UP, hasText("Cancel"))
        moveTo(KeyEvent.KEYCODE_DPAD_RIGHT, hasTestTag("submit"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        compose.waitUntil(20_000) { runBlocking { harness.app.store.current() }.isNotEmpty() }
        val name = runBlocking { harness.app.store.current() }.single().name
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, hasTestTag("computer:$name"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        harness.next(harness.ready)
        val web = harness.webView(scenario)
        assertEquals("compact", evaluateString(web, "document.documentElement.dataset.layout"))

        listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP)
            .forEach(::press)
        assertEquals("true", evaluateString(web, "String(document.getElementById('terminal-panel').hidden)"))
        assertEquals("false", evaluateString(web, "String(Boolean(document.activeElement?.matches('input, textarea, [contenteditable]:not([contenteditable=false])')))"))

        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
    }

    @Test
    fun backWhileTheKeyboardIsOpenOnlyClosesIt() {
        closeKeyboard(startAdding())
    }

    private val inPanel = hasAnyAncestor(hasTestTag("look-panel"))

    private fun focusInPanel() = compose.onAllNodes(inPanel and isFocused()).fetchSemanticsNodes().isNotEmpty()

    private fun openScratch(): Pair<ActivityScenario<MainActivity>, android.webkit.WebView> {
        harness.addManager("Scratch")
        val scenario = harness.launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithTagCount("computer:Scratch") == 1 }
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, hasTestTag("computer:Scratch"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        harness.next(harness.ready)
        return scenario to harness.webView(scenario)
    }

    private fun openPanelFromTheList(): ActivityScenario<MainActivity> {
        val scenario = harness.launch()
        compose.waitUntil(10_000) { compose.onAllNodesWithTagCount("computer:Scratch") == 1 }
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, hasTestTag("computer:Scratch"))
        moveTo(KeyEvent.KEYCODE_DPAD_RIGHT, hasContentDescription("More for Scratch"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, hasText("Skin and Theme"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        harness.next(harness.ready)
        // The panel opens with the focus on the skin the page shows.
        compose.waitUntil(10_000) { focusInPanel() }
        return scenario
    }

    /** Back on the list, where the remote reaches the computer's ⋮ menu again. */
    private fun backOnTheList(scenario: ActivityScenario<MainActivity>) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTagCount("computer:Scratch") == 1 }
        assertEquals(0, compose.onAllNodesWithTagCount("look-panel"))
        assertNotEquals(Lifecycle.State.DESTROYED, scenario.state)
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, hasTestTag("computer:Scratch"))
        moveTo(KeyEvent.KEYCODE_DPAD_RIGHT, hasContentDescription("More for Scratch"))
    }

    @Test
    fun theRemoteChoosesASkinFromTheListAndGoesBackToIt() {
        val scratch = harness.addManager("Scratch")
        val scenario = openPanelFromTheList()
        assertTrue(isFocused(hasTestTag("skin:professional")))

        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, hasTestTag("skin:grove"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithTag("skin:grove").assertIsSelected()
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, hasTestTag("theme:dark"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, hasTestTag("look-cancel"))
        moveTo(KeyEvent.KEYCODE_DPAD_RIGHT, hasTestTag("look-save"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        backOnTheList(scenario)
        assertEquals(Look("grove", Theme.Dark), runBlocking { harness.app.looks.current()[scratch.id] })
    }

    @Test
    fun backFromThePanelOpenedFromTheListGoesBackToTheList() {
        val scratch = harness.addManager("Scratch")
        val scenario = openPanelFromTheList()
        moveTo(KeyEvent.KEYCODE_DPAD_DOWN, hasTestTag("skin:grove"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        press(KeyEvent.KEYCODE_BACK)

        backOnTheList(scenario)
        assertEquals(null, runBlocking { harness.app.looks.current()[scratch.id] })
    }

    @Test
    fun theMenuKeyOpensThePanelAndTheFocusStaysInsideIt() {
        val (scenario, web) = openScratch()
        press(KeyEvent.KEYCODE_MENU)
        compose.waitUntil(10_000) { focusInPanel() }

        listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)
            .plus(List(12) { KeyEvent.KEYCODE_DPAD_UP })
            .plus(List(16) { KeyEvent.KEYCODE_DPAD_DOWN })
            .forEach {
                press(it)
                assertTrue("The focus left the panel after key $it", focusInPanel())
            }
        assertFalse(harness.webViewHasFocus(scenario))

        // Back closes the panel, not the app, and the page has the focus again.
        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { compose.onAllNodesWithTagCount("look-panel") == 0 }
        compose.waitUntil(5_000) { harness.webViewHasFocus(scenario) }
        assertNotEquals(Lifecycle.State.DESTROYED, scenario.state)

        // Menu opens it again, and closes it.
        press(KeyEvent.KEYCODE_MENU)
        compose.waitUntil(10_000) { focusInPanel() }
        press(KeyEvent.KEYCODE_MENU)
        compose.waitUntil(10_000) { compose.onAllNodesWithTagCount("look-panel") == 0 }
        compose.waitUntil(5_000) { harness.webViewHasFocus(scenario) }

        // The D-pad still reaches only the page, which opens nothing from it.
        listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP).forEach(::press)
        assertEquals("true", evaluateString(web, "String(document.getElementById('terminal-panel').hidden)"))

        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
    }

    @Test
    fun aFailedSaveKeepsTheFocusOnSave() {
        val looks = FailingLooks(harness.app)
        try {
            harness.app.looks = looks.store
            looks.failing = true
            val (scenario, _) = openScratch()
            press(KeyEvent.KEYCODE_MENU)
            compose.waitUntil(10_000) { focusInPanel() }
            moveTo(KeyEvent.KEYCODE_DPAD_DOWN, hasTestTag("look-cancel"))
            moveTo(KeyEvent.KEYCODE_DPAD_RIGHT, hasTestTag("look-save"))
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) { compose.onAllNodesWithTagCount("look-error") == 1 }
            assertTrue(isFocused(hasTestTag("look-save")))

            press(KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10_000) { compose.onAllNodesWithTagCount("look-panel") == 0 }
            compose.waitUntil(5_000) { harness.webViewHasFocus(scenario) }
            assertNotEquals(Lifecycle.State.DESTROYED, scenario.state)
        } finally {
            looks.close()
        }
    }
}
