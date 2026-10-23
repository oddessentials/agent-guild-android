package com.oddessentials.agentguild.ui

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.oddessentials.agentguild.AgentGuildApp
import com.oddessentials.agentguild.app
import com.oddessentials.agentguild.model.Health
import com.oddessentials.agentguild.model.HealthResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Adds a computer from its sign-in link, or signs in to [updating] again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddComputer(updating: String?, scanFirst: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val computers by app.store.computers.collectAsState(initial = null)
    val target = updating?.let { id -> computers?.firstOrNull { it.id == id } }
    val canScan = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    // Not saved with the activity's state: it holds the token.
    var link by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    var scanning by rememberSaveable { mutableStateOf(scanFirst && canScan) }
    var checking by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val parsed = AgentGuildApp.parseLink(link)

    fun add() {
        val signIn = AgentGuildApp.parseLink(link)
        if (signIn == null) {
            problem = "That isn't an Agent Guild sign-in link."
            return
        }
        if (updating != null && (target == null || !signIn.isFor(target))) {
            problem = if (target == null) "This computer was removed." else "This sign-in link is for a different computer."
            return
        }
        checking = scope.launch {
            try {
                when (val result = Health.check(signIn.address, signIn.token, app::isOnline)) {
                    is HealthResult.Failed -> problem = result.problem.message
                    is HealthResult.Supported -> {
                        if (updating != null) {
                            if (!app.store.update(updating, signIn)) {
                                problem = "This computer was removed."
                                return@launch
                            }
                        } else {
                            app.store.add(signIn, name)
                        }
                        onDone()
                    }
                }
            } finally {
                checking = null
            }
        }
    }

    if (scanning) {
        BackHandler { scanning = false }
        Scanner(
            onScan = { scanned ->
                scanning = false
                link = scanned
                problem = null
                add()
            },
            onCancel = { scanning = false },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (updating == null) "Add Computer" else "Sign In to ${target?.name ?: "Computer"}") },
                navigationIcon = { TextButton(onClick = onDone) { Text("Cancel") } },
                actions = {
                    if (checking != null) {
                        CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(24.dp))
                    } else {
                        TextButton(onClick = ::add, enabled = parsed != null, modifier = Modifier.testTag("submit")) {
                            Text(if (updating == null) "Add" else "Sign In")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = link,
                onValueChange = {
                    link = it
                    problem = null
                },
                modifier = Modifier.fillMaxWidth().testTag("link"),
                label = { Text("Sign-in link") },
                placeholder = { Text("https://computer.tailnet.ts.net/#token=…") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            Text(
                "In Agent Guild on your computer: Settings → Remote access → Connect another device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (canScan) {
                OutlinedButton(onClick = { scanning = true }) { Text("Scan Sign-in Code") }
            }
            if (updating == null) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().testTag("name"),
                    label = { Text("Name") },
                    placeholder = { Text(parsed?.defaultName ?: "My computer") },
                    singleLine = true,
                )
            }
            problem?.let {
                Text(it, Modifier.testTag("problem"), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
