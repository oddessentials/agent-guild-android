package com.oddessentials.agentguild

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.oddessentials.agentguild.ui.AgentGuildTheme
import com.oddessentials.agentguild.ui.Root
import com.oddessentials.agentguild.ui.Unsupported

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val support = app.webViewSupport
        val supported = support.supportsMessageListener()
        setContent {
            AgentGuildTheme {
                if (supported) Root() else Unsupported(support.version(this))
            }
        }
    }
}
