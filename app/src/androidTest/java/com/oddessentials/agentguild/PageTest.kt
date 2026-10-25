package com.oddessentials.agentguild

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import com.oddessentials.agentguild.Harness.Companion.evaluate
import com.oddessentials.agentguild.Harness.Companion.evaluateString
import com.oddessentials.agentguild.Harness.Companion.url
import com.oddessentials.agentguild.model.Health
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
        harness.openComputer("Scratch")
        val version = harness.next(harness.ready)
        assertTrue(version, Health.supports(version))
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

    /** The page's dialogs and menus cap their height with vh and dvh, which are 0 if the WebView wraps its content. */
    @Test
    fun viewportUnitsAreTheWebViewsHeight() {
        val web = open()
        val heights = evaluateString(
            web,
            """(() => {
                const probe = (height) => Object.assign(document.createElement('div'), { style: `position: fixed; top: 0; height: ${'$'}{height}` });
                const vh = probe('100vh'), dvh = probe('100dvh');
                document.body.append(vh, dvh);
                const heights = [vh, dvh].map((element) => Math.round(element.getBoundingClientRect().height)).join(' ');
                vh.remove();
                dvh.remove();
                return heights;
            })()""",
        )
        val height = evaluate(web, "Math.round(visualViewport.height)").toInt()
        assertTrue(height > 0)
        assertEquals("$height $height", heights)
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
            // Without a user's tap, the popup blocker stops window.open before onCreateWindow.
            evaluate(
                web,
                """(() => {
                    const cover = document.createElement('button');
                    cover.style.cssText = 'position:fixed;inset:0;z-index:2147483647;opacity:0';
                    cover.addEventListener('click', () => { window.popup = window.open('${elsewhere.origin}/popup'); cover.remove(); });
                    document.body.append(cover);
                    return true;
                })()""",
            )
            tap(web)
            // A refused window comes back already closed.
            harness.untilTrue(web, "window.popup && window.popup.closed === true")
            assertEquals(manager, Origin.parse(url(web)))
            assertEquals(0, elsewhere.hits("/popup"))
            assertTrue(harness.opened.isEmpty())
        }
    }

    /** A real tap in the middle of [web], which counts as the user's gesture. */
    private fun tap(web: android.webkit.WebView) {
        val location = IntArray(2)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { web.getLocationOnScreen(location) }
        val x = location[0] + web.width / 2f
        val y = location[1] + web.height / 2f
        val time = SystemClock.uptimeMillis()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test
    fun aNewWebViewLoadsThePageAfterTheRendererIsGone() {
        val scenario = harness.launch()
        harness.openComputer("Scratch")
        val version = harness.next(harness.ready)
        val first = harness.webView(scenario)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(WebViewCompat.getWebViewRenderProcess(first)!!.terminate())
        }

        val second = harness.webView(scenario, other = first)
        assertEquals(version, harness.next(harness.ready))
        assertEquals(manager, Origin.parse(url(second)))
        assertEquals("yard", evaluateString(second, "document.documentElement.dataset.view"))
    }
}
