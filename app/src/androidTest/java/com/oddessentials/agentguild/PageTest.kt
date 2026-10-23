package com.oddessentials.agentguild

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import com.oddessentials.agentguild.Harness.Companion.evaluate
import com.oddessentials.agentguild.Harness.Companion.evaluateString
import com.oddessentials.agentguild.Harness.Companion.url
import com.oddessentials.agentguild.model.Origin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The scratch manager's own page in the app. */
@RunWith(AndroidJUnit4::class)
class PageTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val harness = Harness(compose)
    private val manager = Origin.parse(Harness.managerAddress)!!

    @Before
    fun setUp() {
        harness.addManager("Scratch")
    }

    @After
    fun tearDown() = harness.close()

    private fun open(): android.webkit.WebView {
        val scenario = harness.launch()
        compose.onNodeWithTag("computer:Scratch").click()
        val version = harness.next(harness.ready)
        assertEquals("0.0.0-development", version)
        return harness.webView(scenario)
    }

    @Test
    fun thePageLoadsSaysReadyAndDropsTheToken() {
        val web = open()
        assertEquals(manager, Origin.parse(url(web)))
        assertFalse(evaluateString(web, "location.href")!!.contains(Harness.managerToken))
        assertEquals("yard", evaluateString(web, "document.documentElement.dataset.view"))
        assertEquals("function", evaluateString(web, "typeof window.agentGuild.postMessage"))
    }

    @Test
    fun theSwitchShowsCardsAndTheYard() {
        val web = open()
        compose.onNodeWithTag("view:cards").click()
        harness.untilTrue(web, "document.documentElement.dataset.view === 'cards'")
        compose.onNodeWithTag("view:yard").click()
        harness.untilTrue(web, "document.documentElement.dataset.view === 'yard'")
    }

    @Test
    fun aLinkTheUserFollowsOpensOutside() {
        val web = open()
        evaluate(
            web,
            """(() => {
                const link = Object.assign(document.createElement('a'), { href: 'https://example.com/docs', target: '_blank' });
                document.body.append(link);
                link.click();
                link.remove();
                return true;
            })()""",
        )
        assertEquals("https://example.com/docs", harness.next(harness.opened))
    }

    @Test
    fun aNavigationElsewhereIsCancelled() {
        val web = open()
        FakeServer(47942).use { elsewhere ->
            evaluate(web, "location.href = '${elsewhere.origin}/elsewhere'; true")
            assertEquals("${elsewhere.origin}/elsewhere", harness.next(harness.blocked))
            assertEquals(manager, Origin.parse(url(web)))
            assertEquals(0, elsewhere.hits("/elsewhere"))
            assertTrue(harness.opened.isEmpty())
        }
    }

    @Test
    fun aNewWindowIsRefused() {
        val web = open()
        FakeServer(47942).use { elsewhere ->
            assertEquals("\"null\"", evaluate(web, "String(window.open('${elsewhere.origin}/popup'))"))
            assertEquals(manager, Origin.parse(url(web)))
            assertEquals(0, elsewhere.hits("/popup"))
            assertTrue(harness.opened.isEmpty())
        }
    }

    @Test
    fun aNewWebViewLoadsThePageAfterTheRendererIsGone() {
        val scenario = harness.launch()
        compose.onNodeWithTag("computer:Scratch").click()
        harness.next(harness.ready)
        val first = harness.webView(scenario)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(WebViewCompat.getWebViewRenderProcess(first)!!.terminate())
        }

        val second = harness.webView(scenario, other = first)
        assertEquals("0.0.0-development", harness.next(harness.ready))
        assertEquals(manager, Origin.parse(url(second)))
        assertEquals("yard", evaluateString(second, "document.documentElement.dataset.view"))
    }
}
