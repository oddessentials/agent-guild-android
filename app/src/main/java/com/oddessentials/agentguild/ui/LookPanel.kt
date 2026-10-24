package com.oddessentials.agentguild.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.oddessentials.agentguild.model.Look
import com.oddessentials.agentguild.model.PageLook
import com.oddessentials.agentguild.model.Theme
import com.oddessentials.agentguild.web.PageWeb
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Skin and Theme panel's state for one [PageWeb]. It belongs to the load it opened on: a new
 * load closes it, and drops any unsaved preview, since the page then loads the saved look.
 */
@Stable
class LookChooser(private val web: PageWeb, private val scope: CoroutineScope, private val save: suspend (Look) -> Unit) {
    /** What the page offered and showed when the panel opened; null while it is closed. */
    var page by mutableStateOf<PageLook?>(null)
        private set
    var skin by mutableStateOf<String?>(null)
        private set
    var theme by mutableStateOf(Theme.System)
        private set
    var saving by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    val isOpen get() = page != null

    private var load = NONE
    private var reading = NONE

    /** Opens on the current load once the page has answered. [saved] gives the theme, which the page can't tell apart from System. */
    fun open(saved: Look) {
        val current = web.loads
        if (isOpen || !web.loaded || reading == current) return
        reading = current
        web.readLook(current) { shown ->
            if (reading != current) return@readLook
            reading = NONE
            if (shown == null || isOpen || web.loads != current) return@readLook
            load = current
            skin = shown.skin
            theme = saved.theme
            failed = false
            page = shown
        }
    }

    fun choose(skin: String?, theme: Theme) {
        if (!isOpen || saving) return
        this.skin = skin
        this.theme = theme
        failed = false
        web.preview(load, skin, theme)
    }

    /** Puts back the skin and theme the page showed when the panel opened. */
    fun cancel() {
        val shown = page ?: return
        if (saving) return
        web.preview(load, shown.skin, shown.theme ?: Theme.System)
        close()
    }

    /** Closes only once the write succeeded. When it fails, the saved look is unchanged and the panel says so. */
    fun save() {
        if (!isOpen || saving) return
        val look = Look(skin, theme)
        val savedFor = load
        saving = true
        failed = false
        scope.launch {
            try {
                save(look)
                if (load == savedFor) close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (load == savedFor) failed = true
            } finally {
                saving = false
            }
        }
    }

    /** Called with each new count of [PageWeb.loads]. */
    fun loadChanged(loads: Int) {
        if (reading != NONE && reading != loads) reading = NONE
        if (isOpen && load != loads) close()
    }

    private fun close() {
        page = null
        failed = false
        load = NONE
    }

    private companion object {
        const val NONE = -1
    }
}

/** The panel over the TV's Yard. Focus stays inside it while it is open, and goes back to the page when it closes. */
@Composable
fun LookPanel(chooser: LookChooser, web: PageWeb, modifier: Modifier = Modifier) {
    val page = chooser.page ?: return
    val first = remember { FocusRequester() }
    val firstSkin = remember { chooser.skin ?: page.skins.firstOrNull()?.id }
    val firstTheme = remember { chooser.theme }
    BackHandler { chooser.cancel() }
    LaunchedEffect(Unit) { first.requestFocus() }
    DisposableEffect(Unit) { onDispose { web.webView?.requestFocus() } }

    Surface(
        modifier
            .fillMaxHeight()
            .width(360.dp)
            .testTag("look-panel")
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Skin and Theme", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            if (page.skins.isNotEmpty()) {
                SectionHeader("Skin")
                page.skins.forEach { skin ->
                    Choice(
                        skin.name,
                        selected = skin.id == chooser.skin,
                        tag = "skin:${skin.id}",
                        modifier = if (skin.id == firstSkin) Modifier.focusRequester(first) else Modifier,
                    ) { chooser.choose(skin.id, chooser.theme) }
                }
            }
            SectionHeader("Theme")
            Theme.entries.forEach { theme ->
                Choice(
                    theme.name,
                    selected = theme == chooser.theme,
                    tag = "theme:${theme.name.lowercase()}",
                    modifier = if (firstSkin == null && theme == firstTheme) Modifier.focusRequester(first) else Modifier,
                ) { chooser.choose(chooser.skin, theme) }
            }
            if (chooser.failed) {
                Text(
                    "The skin and theme couldn't be saved. Try again, or cancel to keep the saved ones.",
                    Modifier.padding(top = 8.dp).testTag("look-error"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Never disabled while saving: a disabled button would drop the focus.
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                OutlinedButton(onClick = chooser::cancel, Modifier.testTag("look-cancel")) { Text("Cancel") }
                Button(onClick = chooser::save, Modifier.testTag("look-save")) { Text(if (chooser.saving) "Saving…" else "Save") }
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
