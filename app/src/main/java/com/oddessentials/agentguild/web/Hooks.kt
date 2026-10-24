package com.oddessentials.agentguild.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** The startup check, behind an interface so tests can show the unsupported screen on any image. */
interface WebViewSupport {
    fun supportsMessageListener(): Boolean

    fun version(context: Context): String?

    object System : WebViewSupport {
        override fun supportsMessageListener() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

        override fun version(context: Context) = WebViewCompat.getCurrentWebViewPackage(context)?.versionName
    }
}

/** Whether the app shows the TV board, behind an interface so tests can choose. */
fun interface TvCheck {
    fun isTv(context: Context): Boolean

    companion object {
        private const val FIRE_TV_FEATURE = "amazon.hardware.fire_tv"

        val System = TvCheck { context ->
            isTv(context.resources.configuration.uiMode, context.packageManager.hasSystemFeature(FIRE_TV_FEATURE))
        }

        fun isTv(uiMode: Int, fireTv: Boolean) =
            fireTv || (uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    }
}

/** Opens a link outside the app. */
fun interface LinkOpener {
    fun open(context: Context, url: String)

    companion object {
        val CustomTabs = LinkOpener { context, url ->
            try {
                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
            } catch (_: ActivityNotFoundException) {
                // No browser on the device: the link stays closed.
            }
        }
    }
}

/** What the page did, for tests to watch. */
interface PageEvents {
    /** The page sent `ready`. */
    fun ready(version: String) {}

    /** A main-frame load of [url] was cancelled, or stopped once started. */
    fun blocked(url: String) {}

    companion object {
        val None = object : PageEvents {}
    }
}
