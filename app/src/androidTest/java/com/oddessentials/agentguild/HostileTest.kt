package com.oddessentials.agentguild

import android.Manifest
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oddessentials.agentguild.Harness.Companion.evaluate
import com.oddessentials.agentguild.Harness.Companion.url
import com.oddessentials.agentguild.model.HealthProblem
import com.oddessentials.agentguild.model.Origin
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the real manager can't stage: a redirect elsewhere, a frame from another origin, a form
 * posted elsewhere, and a manager whose answer changes. A fake manager on one port plays the
 * computer, and a server on another port is elsewhere.
 */
@RunWith(AndroidJUnit4::class)
class HostileTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val harness = Harness(compose)
    private val manager = FakeServer(47941)
    private val elsewhere = FakeServer(47942)
    private val status = AtomicInteger(200)
    private val token = "0123456789abcdef0123456789abcdef"

    @Before
    fun setUp() {
        manager.actAsManager { status.get() }
        manager.page(
            "/",
            """<!doctype html><title>Fake manager</title>
            <form id="post" method="post" action="${elsewhere.origin}/landing"><input name="a" value="b"></form>
            <script>window.agentGuild.postMessage(JSON.stringify({ source: 'agent-guild', type: 'ready', version: '1.9.0' }));</script>""",
        )
        manager.route("/redirect") { FakeServer.Response(302, headers = mapOf("Location" to "${elsewhere.origin}/landing")) }
        elsewhere.page("/landing", "<!doctype html><title>Elsewhere</title>")
        harness.add("${manager.origin}/#token=$token", "Fake")
    }

    @After
    fun tearDown() {
        manager.close()
        elsewhere.close()
        harness.close()
    }

    private fun open() = harness.launch().also {
        compose.onNodeWithTag("computer:Fake").click()
        harness.next(harness.ready)
    }

    @Test
    fun aRedirectElsewhereIsCancelled() {
        val web = harness.webView(open())
        evaluate(web, "location.href = '/redirect'; true")
        assertEquals("${elsewhere.origin}/landing", harness.next(harness.blocked))
        assertEquals(1, manager.hits("/redirect"))
        assertEquals(0, elsewhere.hits("/landing"))
        assertEquals(Origin.parse(manager.origin), Origin.parse(url(web)))
    }

    @Test
    fun aPageStartedElsewhereIsStopped() {
        // shouldOverrideUrlLoading never sees a POST, so this reaches the second guard.
        val web = harness.webView(open())
        evaluate(web, "document.getElementById('post').submit(); true")
        assertEquals("${elsewhere.origin}/landing", harness.next(harness.blocked))
        compose.waitUntil(20_000) { compose.onAllNodesWithTagCount("left") == 1 }

        compose.onNodeWithText("Try Again").click()
        assertEquals("1.9.0", harness.next(harness.ready))
        compose.waitUntil(20_000) { compose.onAllNodesWithTagCount("left") == 0 }
        assertEquals(Origin.parse(manager.origin), Origin.parse(url(web)))
    }

    @Test
    fun onlyTheManagerGetsTheMicrophone() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(harness.app.packageName, Manifest.permission.RECORD_AUDIO)
        elsewhere.page(
            "/frame",
            """<!doctype html><script>
            navigator.mediaDevices.getUserMedia({ audio: true })
              .then((stream) => { stream.getTracks().forEach((track) => track.stop()); return 'granted'; }, (error) => error.name)
              .then((outcome) => fetch('/result?' + outcome));
            </script>""",
        )
        val web = harness.webView(open())
        evaluate(
            web,
            """(() => {
                const frame = Object.assign(document.createElement('iframe'), { src: '${elsewhere.origin}/frame' });
                frame.allow = 'microphone';
                document.body.append(frame);
                return true;
            })()""",
        )
        assertEquals("/result?NotAllowedError", elsewhere.await("/result").path)

        evaluate(
            web,
            """navigator.mediaDevices.getUserMedia({ audio: true })
              .then((stream) => { stream.getTracks().forEach((track) => track.stop()); return 'granted'; }, (error) => error.name)
              .then((outcome) => fetch('/result?' + outcome)); true""",
        )
        assertEquals("/result?granted", manager.await("/result").path)
    }

    private fun resume(scenario: androidx.test.core.app.ActivityScenario<MainActivity>) {
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
    }

    @Test
    fun aRejectedTokenOnResumeOffersToSignInAgain() {
        val scenario = open()
        status.set(401)
        resume(scenario)
        compose.waitUntil(20_000) { compose.onAllNodesWithTextCount(HealthProblem.TokenRejected.message) == 1 }
        compose.onNodeWithText("Paste Sign-in Link").click()
        compose.waitUntil(20_000) { compose.onAllNodesWithTextCount("Sign In to Fake") == 1 }
    }

    @Test
    fun aChangedAddressOnResumeOffersToSignInAgain() {
        val scenario = open()
        manager.close()
        resume(scenario)
        compose.waitUntil(20_000) { compose.onAllNodesWithTextCount(HealthProblem.Refused.message) == 1 }
        compose.waitUntil(20_000) { compose.onAllNodesWithTextCount("Paste Sign-in Link") == 1 }
    }

    @Test
    fun anotherFailureOnResumeKeepsThePageWithABanner() {
        val scenario = open()
        val web = harness.webView(scenario)
        status.set(502)
        resume(scenario)
        compose.waitUntil(20_000) { compose.onAllNodesWithTagCount("banner") == 1 }
        compose.onNodeWithTag("banner").assertTextEquals(HealthProblem.Unexpected(502).message)
        assertEquals(web, harness.webView(scenario))

        status.set(200)
        resume(scenario)
        compose.waitUntil(20_000) { compose.onAllNodesWithTagCount("banner") == 0 }
    }
}
