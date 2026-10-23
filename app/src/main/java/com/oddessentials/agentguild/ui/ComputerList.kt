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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.oddessentials.agentguild.app
import com.oddessentials.agentguild.model.Computer
import com.oddessentials.agentguild.model.Destination
import com.oddessentials.agentguild.model.Origin
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComputerList(onOpen: (Destination) -> Unit, onAdd: (updating: String?) -> Unit) {
    val store = LocalContext.current.app.store
    val computers by store.computers.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Guild") },
                actions = { TextButton(onClick = { onAdd(null) }) { Text("Add Computer") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item { SectionHeader("Computers") }
            items(computers.orEmpty(), key = { it.id }) { computer ->
                ComputerRow(
                    computer,
                    onOpen = { onOpen(Destination.Saved(computer.id)) },
                    onRename = { renaming = computer.id },
                    onSignIn = { onAdd(computer.id) },
                    onRemove = { scope.launch { store.remove(computer.id) } },
                )
            }
            if (computers?.isEmpty() == true) {
                item {
                    Footnote("On your computer, open Agent Guild's Settings → Remote access → Connect another device, then scan or paste its sign-in link.")
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Column(Modifier.fillMaxWidth().clickable { onOpen(Destination.Demo) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Try the demo", style = MaterialTheme.typography.bodyLarge)
                    Text("Simulated sessions, with no computer needed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun ComputerRow(computer: Computer, onOpen: () -> Unit, onRename: () -> Unit, onSignIn: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().testTag("computer:${computer.name}").clickable(onClick = onOpen).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(computer.name, style = MaterialTheme.typography.bodyLarge)
            Text(Origin.parse(computer.address)?.host ?: computer.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            TextButton(onClick = { menu = true }, Modifier.semantics { contentDescription = "More for ${computer.name}" }) { Text("⋮") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Sign In Again") }, onClick = { menu = false; onSignIn() })
                DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onRemove() })
            }
        }
    }
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
