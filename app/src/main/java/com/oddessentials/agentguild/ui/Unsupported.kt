package com.oddessentials.agentguild.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Shown instead of everything else when the `WebView` lacks `WEB_MESSAGE_LISTENER`. */
@Composable
fun Unsupported(version: String?) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Update Android System WebView", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                "Agent Guild needs a newer Android System WebView than this device has" +
                    (version?.let { " (version $it)" } ?: "") +
                    ". Update Android System WebView, then open Agent Guild again.",
                Modifier.testTag("unsupported"),
                textAlign = TextAlign.Center,
            )
        }
    }
}
