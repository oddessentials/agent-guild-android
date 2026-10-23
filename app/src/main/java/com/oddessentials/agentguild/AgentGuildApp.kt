package com.oddessentials.agentguild

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.oddessentials.agentguild.data.AesGcmSealer
import com.oddessentials.agentguild.data.ComputerStore
import com.oddessentials.agentguild.model.SignInLink
import com.oddessentials.agentguild.web.LinkOpener
import com.oddessentials.agentguild.web.PageEvents
import com.oddessentials.agentguild.web.WebViewSupport
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AgentGuildApp : Application() {
    lateinit var store: ComputerStore
        private set

    // Instrumented tests replace these before starting the activity.
    var webViewSupport: WebViewSupport = WebViewSupport.System
    var links: LinkOpener = LinkOpener.CustomTabs
    var events: PageEvents = PageEvents.None

    /** Set by instrumented tests in place of the camera: it receives what the scanner does with each read. */
    var scanResults: ((deliver: (texts: List<String>) -> Unit) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        store = ComputerStore.create(
            File(filesDir, "datastore/computers.sealed"),
            AesGcmSealer.keystore(),
            CoroutineScope(Dispatchers.IO + SupervisorJob()),
        )
    }

    fun isOnline(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        fun parseLink(text: String) = SignInLink.parse(text, allowLoopbackHttp = BuildConfig.DEBUG)
    }
}

val Context.app: AgentGuildApp get() = applicationContext as AgentGuildApp
