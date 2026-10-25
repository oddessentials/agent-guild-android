package com.oddessentials.agentguild

import android.content.Intent
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oddessentials.agentguild.Harness.Companion.evaluate
import com.oddessentials.agentguild.Harness.Companion.evaluateString
import com.oddessentials.agentguild.model.HealthProblem
import com.oddessentials.agentguild.web.TvCheck
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The TV board, with the TV check replaced, so it runs on any device. */
@RunWith(AndroidJUnit4::class)
class TvTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val harness = Harness(compose)
    private val manager = FakeServer(47941)
    private val status = AtomicInteger(200)
    private val token = "0123456789abcdef0123456789abcdef"

    @Before
    fun setUp() {
        manager.actAsManager { status.get() }
        manager.page(
            "/",
            """<!doctype html><title>Fake manager</title>
            <script>
              const post = (message) => window.agentGuild.postMessage(JSON.stringify({ source: 'agent-guild', ...message }));
              post({ type: 'open', url: 'https://example.com/docs' });
              post({ type: 'ready', version: '1.9.0' });
            </script>""",
        )
        harness.add("${manager.origin}/#token=$token", "Fake")
    }

    @After
    fun tearDown() {
        manager.close()
        harness.close()
    }

    private fun open(tv: Boolean, name: String = "Fake"): ActivityScenario<MainActivity> {
        harness.app.tv = TvCheck { tv }
        return harness.launch().also { harness.openComputer("$name") }
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun keepsScreenOn(scenario: ActivityScenario<MainActivity>): Boolean {
        fun anyKeepsOn(view: View): Boolean =
            view.keepScreenOn || (view is ViewGroup && (0 until view.childCount).any { anyKeepsOn(view.getChildAt(it)) })
        var on = false
        scenario.onActivity { on = anyKeepsOn(it.window.decorView) }
        return on
    }

    @Test
    fun theBoardIsTheCompactYardFullScreenAndAwake() {
        val scenario = open(tv = true)
        harness.next(harness.ready)
        val web = harness.webView(scenario)
        assertEquals("#token=$token&layout=compact&expand=none&view=yard", evaluateString(web, "location.hash"))
        assertEquals(0, compose.onAllNodesWithTagCount("view:cards"))
        assertEquals(0, compose.onAllNodesWithTextCount("Back"))
        assertTrue(keepsScreenOn(scenario))
    }

    @Test
    fun theBoardOpensNoLinksAndDeniesTheMicrophone() {
        val scenario = open(tv = true)
        harness.next(harness.ready)
        assertEquals(null, harness.opened.poll())
        val web = harness.webView(scenario)
        evaluate(web, "navigator.mediaDevices.getUserMedia({ audio: true }).then(() => window.mic = 'granted', (e) => window.mic = e.name), true")
        harness.untilTrue(web, "window.mic === 'NotAllowedError'")
    }

    @Test
    fun backOnTheBoardClosesTheApp() {
        val scenario = open(tv = true)
        harness.next(harness.ready)
        pressBack()
        compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
    }

    @Test
    fun backBeforeTheBoardShowsReturnsToTheList() {
        status.set(401)
        val scenario = open(tv = true)
        compose.waitUntil(20_000) { compose.onAllNodesWithTextCount(HealthProblem.TokenRejected.message) == 1 }
        pressBack()
        compose.onNodeWithTag("computer:Fake").assertExists()
        assertNotEquals(Lifecycle.State.DESTROYED, scenario.state)
    }

    @Test
    fun theManagersPageIsTheCompactYard() {
        harness.addManager("Scratch")
        val scenario = open(tv = true, name = "Scratch")
        harness.next(harness.ready)
        val web = harness.webView(scenario)
        assertEquals("compact", evaluateString(web, "document.documentElement.dataset.layout"))
        assertEquals("yard", evaluateString(web, "document.documentElement.dataset.view"))
        assertFalse(evaluateString(web, "location.href")!!.contains(Harness.managerToken))
    }

    @Test
    fun aPhoneKeepsThePageAndItsControls() {
        val scenario = open(tv = false)
        harness.next(harness.ready)
        assertEquals("https://example.com/docs", harness.next(harness.opened))
        val web = harness.webView(scenario)
        assertEquals("#token=$token&view=yard", evaluateString(web, "location.hash"))
        compose.onNodeWithTag("view:cards").assertExists()
        assertFalse(keepsScreenOn(scenario))
        pressBack()
        compose.onNodeWithTag("computer:Fake").assertExists()
        assertNotEquals(Lifecycle.State.DESTROYED, scenario.state)
    }

    @Test
    fun theLaunchersFindTheApp() {
        val context = harness.app
        fun launchers(category: String) = context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(context.packageName), 0)
            .map { it.activityInfo.name }
        assertEquals(listOf(MainActivity::class.java.name), launchers(Intent.CATEGORY_LAUNCHER))
        assertEquals(listOf(MainActivity::class.java.name), launchers(Intent.CATEGORY_LEANBACK_LAUNCHER))
        assertNotEquals(0, context.applicationInfo.banner)
        val icon = context.packageManager.getApplicationIcon(context.applicationInfo)
        assertTrue(icon is AdaptiveIconDrawable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertNotNull((icon as AdaptiveIconDrawable).monochrome)
        }
    }
}
