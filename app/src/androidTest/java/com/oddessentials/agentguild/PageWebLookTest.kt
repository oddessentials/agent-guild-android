package com.oddessentials.agentguild

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewCompat
import com.oddessentials.agentguild.Harness.Companion.evaluate
import com.oddessentials.agentguild.model.Json
import com.oddessentials.agentguild.model.Look
import com.oddessentials.agentguild.model.Page
import com.oddessentials.agentguild.model.PageView
import com.oddessentials.agentguild.model.Theme
import com.oddessentials.agentguild.ui.LookChooser
import com.oddessentials.agentguild.web.LinkOpener
import com.oddessentials.agentguild.web.PageEvents
import com.oddessentials.agentguild.web.PageWeb
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real WebView loads and DataStore writes, held independently at the two sides of page completion. */
@RunWith(AndroidJUnit4::class)
class PageWebLookTest {
    @get:Rule val compose = createComposeRule()
    private val manager = FakeServer(47941)
    private val writes = FailingLooks(ApplicationProvider.getApplicationContext())
    private val finish = CountDownLatch(1)
    private val write = CompletableDeferred<Unit>()
    private lateinit var web: PageWeb
    private lateinit var chooser: LookChooser
    private val old = Look("grove", Theme.Light)
    private val saved = Look("orbital", Theme.Dark)

    @Before
    fun setUp() {
        runBlocking { writes.store.set("race", old) }
        manager.route("/finish.js") { FakeServer.Response(200, type = "text/javascript") }
        manager.page(
            "/",
            """<!doctype html><title>Look race</title><script>
              window.agentGuildSkins = [{ id: 'grove', name: 'Grove' }, { id: 'orbital', name: 'Orbital' }];
              const params = new URLSearchParams(location.hash.slice(1));
              document.documentElement.dataset.skin = params.get('skin') || 'grove';
              document.documentElement.dataset.theme = params.get('theme') || 'light';
            </script><script src="/finish.js"></script>""",
        )
        compose.setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            web = remember {
                PageWeb(context, Page(manager.origin, "0123456789abcdef0123456789abcdef"), PageView.Yard, true, old,
                    LinkOpener { _, _ -> }, PageEvents.None, object : PageWeb.MicrophoneAccess {
                        override fun granted() = false
                        override fun ask(result: (Boolean) -> Unit) = result(false)
                    })
            }
            chooser = remember {
                LookChooser(web, scope) { look ->
                    writes.store.set("race", look)
                    web.applySavedLook(look)
                }
            }
            DisposableEffect(web) {
                web.start()
                onDispose { web.destroy() }
            }
            LaunchedEffect(web.loads) { chooser.loadChanged(web.loads) }
            web.webView?.let { view -> key(view) { AndroidView(factory = { view }) } }
        }
        compose.waitUntil(30_000) { web.loaded }
    }

    @After
    fun tearDown() {
        finish.countDown()
        write.complete(Unit)
        try {
            if (::web.isInitialized) compose.runOnIdle { web.destroy() }
        } finally {
            writes.close()
            manager.close()
        }
    }

    @Test fun saveCompletesDuringReload() = saveAcrossLoad(renderer = false, beforeFinish = true)
    @Test fun saveCompletesAfterReload() = saveAcrossLoad(renderer = false, beforeFinish = false)
    @Test fun saveCompletesDuringRendererReplacement() = saveAcrossLoad(renderer = true, beforeFinish = true)
    @Test fun saveCompletesAfterRendererReplacement() = saveAcrossLoad(renderer = true, beforeFinish = false)

    @Test fun failedSaveDuringReloadKeepsTheSavedLook() = saveAcrossLoad(renderer = false, beforeFinish = true, failed = true)

    @Test
    fun destructionDiscardsPostedSaveWorkAndLateCompletion() {
        runBlocking { writes.store.set("race", saved) }
        compose.runOnIdle {
            val current = web.webView!!
            val client = current.webViewClient
            val url = current.url
            web.applySavedLook(saved)
            web.destroy()
            client.onPageFinished(current, url)
        }
        // Drain the main-thread save operation after destruction.
        compose.runOnIdle {
            assertNull(web.webView)
            assertFalse(web.loaded)
        }
        assertEquals(saved, runBlocking { writes.store.current()["race"] })
        assertEquals(1, manager.hits("/"))
    }

    @Test
    fun aRepeatedCompletionKeepsThePreviewAndCancelRestoresItsSnapshot() {
        val current = web.webView!!
        compose.runOnIdle { chooser.open(old) }
        compose.waitUntil(30_000) { chooser.isOpen }
        compose.runOnIdle { chooser.choose(saved.skin, saved.theme) }
        assertEquals(listOf("orbital", "dark", "grove", "light"), appearanceAndAddress())
        // Hash changes can produce another completion for the already-loaded document.
        compose.runOnIdle { current.webViewClient.onPageFinished(current, current.url) }
        assertEquals(listOf("orbital", "dark", "grove", "light"), appearanceAndAddress())
        compose.runOnIdle { chooser.cancel() }
        assertEquals(listOf("grove", "light", "grove", "light"), appearanceAndAddress())
    }

    @Test
    fun aSaveRespectsTheCurrentSkinListAndResolvesSystemWithoutLosingOtherParameters() {
        val current = web.webView!!
        evaluate(current, """
            window.agentGuildSkins = [{ id: 'grove', name: 'Grove' }];
            document.documentElement.dataset.skin = 'vapor';
            localStorage.setItem('agentGuild.theme', 'dark');
            window.matchMedia = () => ({ matches: false });
            history.replaceState({ kept: true }, '', location.pathname + '?query=kept' + location.hash + '&other=kept');
            true
        """.trimIndent())
        val system = Look("orbital", Theme.System)
        runBlocking { writes.store.set("race", system) }
        compose.runOnIdle { web.applySavedLook(system) }
        assertEquals(listOf("vapor", "dark", "orbital", null), appearanceAndAddress())

        // An unspecified skin also keeps the page's choice; System falls back to matchMedia.
        evaluate(current, "localStorage.removeItem('agentGuild.theme'), true")
        runBlocking { writes.store.set("race", Look.Default) }
        compose.runOnIdle { web.applySavedLook(Look.Default) }
        assertEquals(listOf("vapor", "light", null, null), appearanceAndAddress())
        assertEquals("true", evaluate(current, """(() => {
            const params = new URLSearchParams(location.hash.slice(1));
            return location.search === '?query=kept' && history.state.kept === true &&
                params.get('other') === 'kept' && params.get('layout') === 'compact' &&
                params.get('expand') === 'none' && params.get('view') === 'yard' &&
                params.get('token') === '0123456789abcdef0123456789abcdef';
        })()"""))
        assertEquals(1, manager.hits("/"))
    }

    private fun saveAcrossLoad(renderer: Boolean, beforeFinish: Boolean, failed: Boolean = false) {
        val writing = CompletableDeferred<Unit>()
        writes.beforeWrite = { writing.complete(Unit); write.await() }
        writes.failing = failed
        compose.runOnIdle { chooser.open(old) }
        compose.waitUntil(30_000) { chooser.isOpen }
        compose.runOnIdle {
            chooser.choose(saved.skin, saved.theme)
            chooser.save()
        }
        compose.waitUntil(30_000) { writing.isCompleted }

        val finishing = CountDownLatch(1)
        manager.route("/finish.js") {
            finishing.countDown()
            // Only the test (or teardown on failure) releases this gate, even on a slow runner.
            finish.await()
            FakeServer.Response(200, type = "text/javascript")
        }
        val first = web.webView!!
        val load = web.loads
        compose.runOnIdle {
            if (renderer) assertTrue(WebViewCompat.getWebViewRenderProcess(first)!!.terminate())
            else first.evaluateJavascript("location.reload()", null)
        }
        compose.waitUntil(30_000) { finishing.count == 0L && web.loads != load && !web.loaded }
        val current = web.webView!!
        assertEquals(renderer, current !== first)
        compose.waitUntil(30_000) { !chooser.isOpen }
        assertEquals(listOf("grove", "light", "grove", "light"), appearanceAndAddress())
        evaluate(current, "window.marked = true")

        if (!beforeFinish) {
            finish.countDown()
            compose.waitUntil(30_000) { web.loaded }
        }
        write.complete(Unit)
        compose.waitUntil(30_000) { !chooser.saving }
        // A script behind the save's posted work is a queue barrier, not a timing delay.
        evaluate(current, "true")
        val expected = if (failed) old else saved
        assertEquals(expected, runBlocking { writes.store.current()["race"] })
        if (beforeFinish) {
            assertFalse(web.loaded)
            finish.countDown()
            compose.waitUntil(30_000) { web.loaded }
        }

        val expectedAppearance = listOf(expected.skin, expected.theme.value, expected.skin, expected.theme.value)
        assertEquals(expectedAppearance, appearanceAndAddress())
        assertEquals("true", evaluate(current, "window.marked === true"))
        assertEquals("true", evaluate(current, "new URLSearchParams(location.hash.slice(1)).get('layout') === 'compact'"))
        assertEquals(2, manager.hits("/"))

        val again = web.loads
        compose.runOnIdle { current.evaluateJavascript("location.reload()", null) }
        compose.waitUntil(30_000) { web.loads != again && web.loaded }
        assertEquals(expectedAppearance, appearanceAndAddress())
        assertEquals(3, manager.hits("/"))
    }

    private fun appearanceAndAddress() = Json.parse(evaluate(web.webView!!, """(() => {
        const params = new URLSearchParams(location.hash.slice(1));
        return [document.documentElement.dataset.skin, document.documentElement.dataset.theme, params.get('skin'), params.get('theme')];
    })()"""))
}
