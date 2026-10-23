package com.oddessentials.agentguild

import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oddessentials.agentguild.web.WebViewSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The startup check, with the WebView feature check replaced. */
@RunWith(AndroidJUnit4::class)
class StartupTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val harness = Harness(compose)

    @After
    fun tearDown() = harness.close()

    private fun webView(supported: Boolean) = object : WebViewSupport {
        override fun supportsMessageListener() = supported
        override fun version(context: Context) = "74.0.3729.186"
    }

    @Test
    fun anUnsupportedWebViewAsksForAnUpdate() {
        harness.app.webViewSupport = webView(supported = false)
        harness.launch()
        compose.onNodeWithTag("unsupported").assertTextContains("74.0.3729.186", substring = true)
        compose.onNodeWithTag("unsupported").assertTextContains("Update Android System WebView", substring = true)
        assertEquals(0, compose.onAllNodesWithTextCount("Add Computer"))
    }

    @Test
    fun aSupportedWebViewShowsTheComputers() {
        harness.app.webViewSupport = webView(supported = true)
        harness.launch()
        compose.onNodeWithText("Try the demo").assertExists()
        assertEquals(0, compose.onAllNodesWithTagCount("unsupported"))
    }
}
