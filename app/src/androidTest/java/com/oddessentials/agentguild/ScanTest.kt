package com.oddessentials.agentguild

import android.Manifest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oddessentials.agentguild.model.Computer
import com.oddessentials.agentguild.model.HealthProblem
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Signing in again by scanning, from the page's recovery screen. [AgentGuildApp.scanResults] stands in
 * for the camera, behind the scanner's own callback wiring.
 */
@RunWith(AndroidJUnit4::class)
class ScanTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val harness = Harness(compose)
    private val manager = FakeServer(47941)
    private val status = AtomicInteger(200)
    private val delivers = LinkedBlockingQueue<(List<String>) -> Unit>()
    private val newToken = "fedcba9876543210fedcba9876543210"
    private lateinit var computer: Computer

    @Before
    fun setUp() {
        manager.actAsManager { status.get() }
        manager.page(
            "/",
            """<!doctype html><title>Fake manager</title>
            <script>window.agentGuild.postMessage(JSON.stringify({ source: 'agent-guild', type: 'ready', version: '1.9.0' }));</script>""",
        )
        computer = harness.add("${manager.origin}/#token=0123456789abcdef0123456789abcdef", "Fake")
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(harness.app.packageName, Manifest.permission.CAMERA)
        harness.app.scanResults = { deliver -> delivers += deliver }
    }

    @After
    fun tearDown() {
        harness.app.scanResults = null
        manager.close()
        harness.close()
    }

    /** Opens the computer's page, has the manager reject its token on resume, and chooses to scan. */
    private fun openScannerFromRecovery(): ActivityScenario<MainActivity> {
        val scenario = harness.launch()
        compose.onNodeWithTag("computer:Fake").click()
        harness.next(harness.ready)
        status.set(401)
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(20_000) { compose.onAllNodesWithTextCount(HealthProblem.TokenRejected.message) == 1 }
        compose.onNodeWithText("Scan Sign-in Code").click()
        return scenario
    }

    private fun deliver(deliver: (List<String>) -> Unit, link: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { deliver(listOf(link)) }
    }

    private fun assertSignedInAgain() {
        assertEquals("1.9.0", harness.next(harness.ready))
        assertEquals(listOf(computer.copy(token = newToken)), runBlocking { harness.app.store.current() })
    }

    @Test
    fun aScanAfterTheComputersLoadSignsInAgain() {
        openScannerFromRecovery()
        val deliver = harness.next(delivers)
        // The scanner started before the screen had read the computers; let it read them now.
        runBlocking { harness.app.store.current() }
        compose.waitForIdle()
        status.set(200)
        deliver(deliver, "${manager.origin}/#token=$newToken")
        assertSignedInAgain()
    }

    @Test
    fun aScanBeforeTheComputersLoadSignsInAgain() {
        harness.app.scanResults = { deliver ->
            status.set(200)
            deliver(listOf("${manager.origin}/#token=$newToken"))
        }
        openScannerFromRecovery()
        assertSignedInAgain()
    }

    @Test
    fun aScanForAComputerRemovedMeanwhileAddsNothing() {
        openScannerFromRecovery()
        val deliver = harness.next(delivers)
        runBlocking { harness.app.store.remove(computer.id) }
        compose.waitForIdle()
        status.set(200)
        deliver(deliver, "${manager.origin}/#token=$newToken")
        compose.waitUntil(20_000) { compose.onAllNodesWithTextCount("This computer was removed.") == 1 }
        assertEquals(emptyList<Computer>(), runBlocking { harness.app.store.current() })
    }

    @Test
    fun aScanThatFinishesAfterCancelIsIgnored() {
        openScannerFromRecovery()
        val deliver = harness.next(delivers)
        val cancel = compose.onNodeWithText("Cancel").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        status.set(200)
        val checks = manager.hits("/api/v1/info")

        // Cancel, then an ML Kit result whose callback was already queued, in one main-thread step:
        // before Compose can draw the form again or dispose of the camera.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            check(cancel())
            deliver(listOf("${manager.origin}/#token=$newToken"))
        }
        compose.waitForIdle()

        // Taking the scan would fill in the link and start the check at once.
        compose.onNodeWithTag("link").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("submit").assertExists()
        assertEquals(checks, manager.hits("/api/v1/info"))
        assertEquals(listOf(computer), runBlocking { harness.app.store.current() })
    }
}
