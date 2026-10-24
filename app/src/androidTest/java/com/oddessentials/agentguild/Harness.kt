package com.oddessentials.agentguild

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.oddessentials.agentguild.model.Computer
import com.oddessentials.agentguild.model.Json
import com.oddessentials.agentguild.model.SignInLink
import com.oddessentials.agentguild.web.LinkOpener
import com.oddessentials.agentguild.web.PageEvents
import com.oddessentials.agentguild.web.WebViewSupport
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * Sets up the app for a test: an empty list of computers and recorders in place of Custom Tabs and
 * the page's events. The scratch manager's address and token come from the instrumentation arguments
 * `managerAddress` and `managerToken`.
 *
 * Waits go through [ComposeTestRule.waitUntil]: the Compose test clock advances only while the rule
 * synchronizes, so a test thread that blocks otherwise stops the app's screens from changing.
 */
class Harness(private val compose: ComposeTestRule) {
    val app: AgentGuildApp = ApplicationProvider.getApplicationContext()
    val opened = LinkedBlockingQueue<String>()
    val ready = LinkedBlockingQueue<String>()
    val blocked = LinkedBlockingQueue<String>()
    private val scenarios = CopyOnWriteArrayList<ActivityScenario<MainActivity>>()

    init {
        runBlocking { app.store.current().forEach { app.store.remove(it.id) } }
        app.webViewSupport = WebViewSupport.System
        app.links = LinkOpener { _: Context, url -> opened += url }
        app.events = object : PageEvents {
            override fun ready(version: String) {
                ready += version
            }

            override fun blocked(url: String) {
                blocked += url
            }
        }
    }

    fun launch(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java).also { scenarios += it }

    fun add(link: String, name: String): Computer = runBlocking {
        app.store.add(requireNotNull(SignInLink.parse(link, allowLoopbackHttp = true)) { "not a sign-in link" }, name)
    }

    fun addManager(name: String = "Scratch") = add(managerLink, name)

    fun close() {
        scenarios.forEach { it.close() }
        app.links = LinkOpener.CustomTabs
        app.events = PageEvents.None
        app.webViewSupport = WebViewSupport.System
        runBlocking { app.store.current().forEach { app.store.remove(it.id) } }
    }

    /** The page's `WebView`, once there is one other than [other]. */
    fun webView(scenario: ActivityScenario<MainActivity>, other: WebView? = null, timeoutSeconds: Long = 30): WebView {
        var found: WebView? = null
        compose.waitUntil(TimeUnit.SECONDS.toMillis(timeoutSeconds)) {
            scenario.onActivity { activity -> found = find(activity.window.decorView)?.takeIf { it !== other } }
            found != null
        }
        return found!!
    }

    /** Evaluates [script] in the page until it returns true. */
    fun untilTrue(webView: WebView, script: String, timeoutSeconds: Long = 30) {
        compose.waitUntil(TimeUnit.SECONDS.toMillis(timeoutSeconds)) { evaluate(webView, "Boolean($script)") == "true" }
    }

    /** The next item [queue] receives. */
    fun <T> next(queue: LinkedBlockingQueue<T>, timeoutSeconds: Long = 30): T {
        var item: T? = null
        compose.waitUntil(TimeUnit.SECONDS.toMillis(timeoutSeconds)) {
            item = queue.poll()
            item != null
        }
        return item!!
    }

    private fun find(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }

    companion object {
        private val arguments get() = InstrumentationRegistry.getArguments()
        val managerAddress: String get() = arguments.getString("managerAddress") ?: "http://127.0.0.1:47931"
        val managerToken: String get() = requireNotNull(arguments.getString("managerToken")) { "Pass the scratch manager's token as the managerToken argument." }
        val managerLink get() = "$managerAddress/#token=$managerToken"

        /** Runs [script] in the page and returns its result as JSON text. */
        fun evaluate(webView: WebView, script: String, timeoutSeconds: Long = 20): String {
            val latch = CountDownLatch(1)
            var result: String? = null
            webView.post { webView.evaluateJavascript(script) { result = it; latch.countDown() } }
            check(latch.await(timeoutSeconds, TimeUnit.SECONDS)) { "The script didn't finish" }
            return result!!
        }

        /** [script]'s result as a string. */
        fun evaluateString(webView: WebView, script: String) = Json.parse(evaluate(webView, script)) as? String

        /** Evaluates [script] until it returns true, for tests without a screen to keep drawing. */
        fun waitFor(webView: WebView, script: String, timeoutSeconds: Long = 20) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (System.nanoTime() < deadline) {
                if (evaluate(webView, "Boolean($script)") == "true") return
            }
            error("Never true: $script")
        }

        fun url(webView: WebView): String? {
            val latch = CountDownLatch(1)
            var url: String? = null
            webView.post { url = webView.url; latch.countDown() }
            check(latch.await(10, TimeUnit.SECONDS))
            return url
        }
    }
}
