package com.oddessentials.agentguild

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.oddessentials.agentguild.ui.AgentGuildTheme
import com.oddessentials.agentguild.ui.Root
import com.oddessentials.agentguild.ui.Unsupported

class MainActivity : ComponentActivity() {
    /** Set while a TV page shows. It gets each press of the remote's Menu key, which then never reaches the page. */
    var menuKey: (() -> Unit)? = null

    private var menuDown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val support = app.webViewSupport
        val supported = support.supportsMessageListener()
        val tv = app.tv.isTv(this)
        setContent {
            AgentGuildTheme {
                if (supported) Root() else Unsupported(support.version(this), tv)
            }
        }
    }

    /**
     * Runs before any view sees the key. A held key's repeats, and the release of a press it took, do
     * nothing. `Activity.dispatchKeyEvent` is public; the restriction lint reports is on androidx.core's
     * own override, which `super` still runs.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            val handler = menuKey
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (handler != null) {
                    if (event.repeatCount == 0) {
                        menuDown = true
                        handler()
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> if (menuDown) {
                    menuDown = false
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
