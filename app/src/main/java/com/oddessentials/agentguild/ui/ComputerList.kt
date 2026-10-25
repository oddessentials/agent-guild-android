package com.oddessentials.agentguild.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oddessentials.agentguild.app
import com.oddessentials.agentguild.model.Computer
import com.oddessentials.agentguild.model.Destination
import com.oddessentials.agentguild.model.Origin
import java.io.IOException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComputerList(onOpen: (Destination) -> Unit, onLook: (Destination) -> Unit, onAdd: (updating: String?) -> Unit) {
    val context = LocalContext.current
    val store = context.app.store
    val looks = context.app.looks
    val tv = remember { context.app.tv.isTv(context) }
    val computers by store.computers.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    // The control the list was left from, so a TV's remote comes back to it.
    var leftFrom by rememberSaveable { mutableStateOf<String?>(null) }
    // On a TV, the control the focus starts on: the one left from while it still exists, else the first computer, else Add Computer.
    val focus = if (!tv) null else computers?.let { list ->
        leftFrom?.takeIf { key -> key.substringBefore(':').let { id -> id == DEMO || list.any { it.id == id } } }
            ?: list.firstOrNull()?.id ?: ADD
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Guild") },
                actions = { TextButton(onClick = { onAdd(null) }, Modifier.focusOnArrival(focus == ADD)) { Text("Add Computer") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { SectionHeader("Computers") }
            items(computers.orEmpty(), key = { it.id }) { computer ->
                ComputerRow(
                    computer,
                    focus = focus,
                    onOpen = { leftFrom = computer.id; onOpen(Destination.Saved(computer.id)) },
                    onLook = if (tv) ({ leftFrom = "${computer.id}$MORE"; onLook(Destination.Saved(computer.id)) }) else null,
                    onRename = { renaming = computer.id },
                    onSignIn = { leftFrom = "${computer.id}$MORE"; onAdd(computer.id) },
                    onRemove = {
                        scope.launch {
                            store.remove(computer.id)
                            // A look left behind holds nothing secret, and is never shown again.
                            try {
                                looks.remove(computer.id)
                            } catch (_: IOException) {
                            }
                        }
                    },
                )
            }
            if (computers?.isEmpty() == true) {
                item {
                    Footnote("On your computer, open Agent Guild's Settings → Remote access → Connect another device, then scan or paste its sign-in link.")
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).focusOnArrival(focus == DEMO).clickable { leftFrom = DEMO; onOpen(Destination.Demo) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text("Try the demo", style = MaterialTheme.typography.bodyLarge)
                        Text("Simulated sessions, with no computer needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (tv) More("the demo", focus == "$DEMO$MORE") { close -> LookItem(close) { leftFrom = "$DEMO$MORE"; onLook(Destination.Demo) } }
                }
            }
        }
    }

    val renamed = computers?.firstOrNull { it.id == renaming }
    if (renamed != null) {
        RenameDialog(renamed, onDismiss = { renaming = null }) { name ->
            scope.launch { store.rename(renamed.id, name) }
            renaming = null
        }
    }
}

private const val ADD = "add"
private const val DEMO = "demo"
private const val MORE = ":more"

/** Takes the focus as soon as the control appears, so a TV's remote has a selection without a press. */
@Composable
private fun Modifier.focusOnArrival(take: Boolean): Modifier {
    if (!take) return this
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    return focusRequester(requester)
}

@Composable
private fun ComputerRow(computer: Computer, focus: String?, onOpen: () -> Unit, onLook: (() -> Unit)?, onRename: () -> Unit, onSignIn: () -> Unit, onRemove: () -> Unit) {
    // The ⋮ menu sits beside the part that opens the computer, not inside it, so the remote can reach it.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).testTag("computer:${computer.name}").focusOnArrival(focus == computer.id).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(computer.name, style = MaterialTheme.typography.bodyLarge)
            Text(Origin.parse(computer.address)?.host ?: computer.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        More(computer.name, focus == "${computer.id}$MORE") { close ->
            if (onLook != null) LookItem(close, onLook)
            DropdownMenuItem(text = { Text("Rename") }, onClick = { close(); onRename() })
            DropdownMenuItem(text = { Text("Sign In Again") }, onClick = { close(); onSignIn() })
            DropdownMenuItem(text = { Text("Remove") }, onClick = { close(); onRemove() })
        }
    }
}

/** A row's ⋮ menu. */
@Composable
private fun More(name: String, focused: Boolean, items: @Composable (close: () -> Unit) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { menu = true }, Modifier.semantics { contentDescription = "More for $name" }.focusOnArrival(focused)) { Text("⋮") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { items { menu = false } }
    }
}

@Composable
private fun LookItem(close: () -> Unit, onLook: () -> Unit) {
    DropdownMenuItem(text = { Text("Skin and Theme") }, onClick = { close(); onLook() })
}

@Composable
private fun RenameDialog(computer: Computer, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(computer.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun Footnote(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
