package com.oddessentials.agentguild

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import com.oddessentials.agentguild.web.TvCheck
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        assertEquals("cards", evaluateString(web, "document.documentElement.dataset.view"))
        assertEquals("none", evaluateString(web, "document.documentElement.dataset.yard"))

        press(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
    }

    @Test
    fun backWhileTheKeyboardIsOpenOnlyClosesIt() {
        closeKeyboard(startAdding())
    }

    @Test
    fun theListStartsOnAddComputerOrTheFirstComputerWithoutAPress() {
        val empty = harness.launch()
        compose.waitUntil(10_000) { isFocused(hasText("Add Computer")) }
        empty.close()
        harness.addManager("Scratch")
        harness.launch()
        compose.waitUntil(10_000) { isFocused(hasTestTag("computer:Scratch")) }
    }
}
