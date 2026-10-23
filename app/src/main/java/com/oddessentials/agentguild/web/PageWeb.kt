package com.oddessentials.agentguild.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import com.oddessentials.agentguild.BuildConfig
import com.oddessentials.agentguild.model.Bridge
import com.oddessentials.agentguild.model.BridgeMessage
import com.oddessentials.agentguild.model.Microphone
import com.oddessentials.agentguild.model.MicrophoneDecision
import com.oddessentials.agentguild.model.Navigation
import com.oddessentials.agentguild.model.NavigationDecision
import com.oddessentials.agentguild.model.Page
import com.oddessentials.agentguild.model.PageView

/**
 * The `WebView` that shows one [Page], with the bridge and the rules of the README's "WebView
 * security". It loads only [Page.url], and keeps its main frame on the page's origin.
 */
class PageWeb(
    private val context: Context,
    val page: Page,
    initialView: PageView,
    private val links: LinkOpener,
    private val events: PageEvents,
    private val microphone: MicrophoneAccess,
) {
    interface MicrophoneAccess {
        fun granted(): Boolean

        /** Asks for the `RECORD_AUDIO` runtime permission and reports whether the user allowed it. */
        fun ask(result: (Boolean) -> Unit)
    }

    /** The current `WebView`; a new one replaces it after its renderer is gone. */
    var webView: WebView? by mutableStateOf(null)
        private set

    /** The main frame started a page off the manager's origin, and was stopped. */
    var left by mutableStateOf(false)
        private set

    private var shownView = initialView
    private var loadedView: PageView? = null
    private var finished = false

    fun start() {
        if (webView == null) webView = create().also { load(it) }
    }

    /** Loads the page again, from its validated URL. */
    fun reload() {
        webView?.let(::load)
    }

    fun show(view: PageView) {
        if (view == shownView) return
        shownView = view
        if (finished) webView?.evaluateJavascript(Bridge.viewScript(view), null)
    }

    fun destroy() {
        webView?.let(::dispose)
        webView = null
    }

    private fun load(view: WebView) {
        left = false
        finished = false
        loadedView = shownView
        view.loadUrl(page.url(shownView))
    }

    private fun dispose(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    private fun openOutside(url: String) {
        if (Navigation.mayOpenOutside(url, page.token)) links.open(context, url)
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    private fun create(): WebView {
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        WebViewCompat.addWebMessageListener(view, Bridge.LISTENER_NAME, setOf(page.origin.rule)) { _, message, sourceOrigin, isMainFrame, _ ->
            if (!Bridge.accepts(isMainFrame, sourceOrigin.toString(), page.origin)) return@addWebMessageListener
            if (message.type != WebMessageCompat.TYPE_STRING) return@addWebMessageListener
            when (val parsed = Bridge.parse(message.data)) {
                is BridgeMessage.Ready -> events.ready(parsed.version)
                is BridgeMessage.Open -> openOutside(parsed.url)
                null -> Unit
            }
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val decision = Navigation.decide(
                    request.url?.toString(),
                    isMainFrame = request.isForMainFrame,
                    hasGesture = request.hasGesture(),
                    isRedirect = request.isRedirect,
                    manager = page.origin,
                )
                return when (decision) {
                    NavigationDecision.Allow -> false
                    is NavigationDecision.OpenOutside -> {
                        events.blocked(decision.url)
                        openOutside(decision.url)
                        true
                    }
                    NavigationDecision.Cancel -> {
                        events.blocked(request.url.toString())
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!Navigation.staysOnManager(url, page.origin)) {
                    view.stopLoading()
                    left = true
                    events.blocked(url.orEmpty())
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (left || !Navigation.staysOnManager(url, page.origin)) return
                finished = true
                if (shownView != loadedView) view.evaluateJavascript(Bridge.viewScript(shownView), null)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view != webView) return true
                dispose(view)
                webView = create().also { load(it) }
                return true
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false

            override fun onPermissionRequest(request: PermissionRequest) {
                val decision = Microphone.decide(request.resources.toList(), request.origin?.toString(), page.origin, microphone.granted())
                when (decision) {
                    MicrophoneDecision.Grant -> request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    MicrophoneDecision.AskThenGrant -> microphone.ask { allowed ->
                        if (allowed) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny()
                    }
                    MicrophoneDecision.Deny -> request.deny()
                }
            }
        }
        return view
    }
}
