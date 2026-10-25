package com.oddessentials.agentguild.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.oddessentials.agentguild.model.Destination

/** The app's screens, as a back stack. */
sealed interface Screen {
    data object List : Screen
    data class Add(val updating: String? = null, val scanFirst: Boolean = false) : Screen
    /** [look] opens the TV's Skin and Theme panel once the page has loaded. */
    data class Page(val destination: Destination, val look: Boolean = false) : Screen

    val key: String
        get() = when (this) {
            List -> "list"
            is Add -> "add:${updating.orEmpty()}:$scanFirst"
            is Page -> when (destination) {
                Destination.Demo -> "page:demo"
                is Destination.Saved -> "page:${destination.id}"
            } + if (look) ":look" else ""
        }

    companion object {
        fun of(key: String): Screen? {
            val parts = key.split(':')
            return when (parts[0]) {
                "list" -> List
                "add" -> Add(parts.getOrNull(1)?.ifEmpty { null }, parts.getOrNull(2) == "true")
                "page" -> parts.getOrNull(1)?.let { Page(if (it == "demo") Destination.Demo else Destination.Saved(it), parts.getOrNull(2) == "look") }
                else -> null
            }
        }
    }
}

private val StackSaver = listSaver<List<Screen>, String>(
    save = { stack -> stack.map { it.key } },
    restore = { keys -> keys.mapNotNull(Screen::of).ifEmpty { listOf(Screen.List) } },
)

@Composable
fun Root() {
    var stack by rememberSaveable(stateSaver = StackSaver) { mutableStateOf(listOf<Screen>(Screen.List)) }
    val states = rememberSaveableStateHolder()
    val push = { screen: Screen -> stack = stack + screen }
    val pop = {
        states.removeState(stack.last().key)
        stack = stack.dropLast(1)
    }
    BackHandler(enabled = stack.size > 1, onBack = pop)

    val top = stack.last()
    states.SaveableStateProvider(top.key) {
        when (top) {
            Screen.List -> ComputerList(
                onOpen = { push(Screen.Page(it)) },
                onLook = { push(Screen.Page(it, look = true)) },
                onAdd = { updating -> push(Screen.Add(updating)) },
            )
            is Screen.Add -> AddComputer(updating = top.updating, scanFirst = top.scanFirst, onDone = pop)
            is Screen.Page -> PageScreen(
                destination = top.destination,
                openLook = top.look,
                onBack = pop,
                onSignIn = { id, scan -> push(Screen.Add(id, scan)) },
            )
        }
    }
}

@Composable
fun AgentGuildTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
