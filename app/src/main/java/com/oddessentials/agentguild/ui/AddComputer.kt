package com.oddessentials.agentguild.ui

import android.content.pm.PackageManager
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oddessentials.agentguild.AgentGuildApp
import com.oddessentials.agentguild.app
import com.oddessentials.agentguild.model.DeviceSignIn
import com.oddessentials.agentguild.model.DeviceSignIn.Answer
import com.oddessentials.agentguild.model.Health
import com.oddessentials.agentguild.model.HealthResult
import com.oddessentials.agentguild.model.SignInLink
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the code screen shows, never the device secret: no [code] while it is asked for, and [ended] once it can't be approved. */
private data class CodeScreen(val code: String? = null, val expiresAt: Instant? = null, val ended: String? = null)

/** Adds a computer from its sign-in link or by a code approved on the page, or signs in to [updating] again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddComputer(updating: String?, scanFirst: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val computers by app.store.computers.collectAsState(initial = null)
    val target = updating?.let { id -> computers?.firstOrNull { it.id == id } }
    val canScan = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val tv = remember { app.tv.isTv(context) }

    // Not saved with the activity's state: it holds the token.
    var link by remember { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    var scanning by rememberSaveable { mutableStateOf(scanFirst && canScan) }
    var checking by remember { mutableStateOf<Job?>(null) }
    var codeScreen by remember { mutableStateOf<CodeScreen?>(null) }
    var requesting by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val parsed = AgentGuildApp.parseLink(link)
    val parsedAddress = AgentGuildApp.parseAddress(address)

    LaunchedEffect(target?.address) {
        if (address.isEmpty()) target?.let { address = it.address.removePrefix("https://") }
    }

    fun save(signIn: SignInLink) {
        checking = scope.launch {
            try {
                // The computer as the store has it now, not as this screen first saw it.
                if (updating != null) {
                    val current = app.store.current().firstOrNull { it.id == updating }
                    if (current == null || !signIn.isFor(current)) {
                        problem = if (current == null) "This computer was removed." else "This sign-in link is for a different computer."
                        return@launch
                    }
                }
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

    fun add() {
        val signIn = AgentGuildApp.parseLink(link)
        if (signIn == null) {
            problem = "That isn't an Agent Guild sign-in link."
            return
        }
        save(signIn)
    }

    fun getCode() {
        val computer = AgentGuildApp.parseAddress(address)
        if (computer == null) {
            problem = "That isn't a computer address."
            return
        }
        problem = null
        requesting?.cancel()
        codeScreen = CodeScreen()
        // The device secret and the token stay in this coroutine; the screen holds only the code.
        requesting = scope.launch {
            when (val started = DeviceSignIn.start(computer, app::isOnline)) {
                is Answer.Code -> {
                    val screen = CodeScreen(started.code, started.expiresAt)
                    codeScreen = screen
                    when (val answer = DeviceSignIn.await(computer, started, app::isOnline)) {
                        is Answer.Approved -> {
                            codeScreen = null
                            save(answer.link)
                        }
                        Answer.Denied -> codeScreen = screen.copy(ended = "The sign-in was declined.")
                        Answer.Expired -> codeScreen = screen.copy(ended = "This code expired or was already answered.")
                        is Answer.Failed -> codeScreen = screen.copy(ended = answer.problem.message)
                        else -> codeScreen = screen.copy(ended = "Agent Guild answered unexpectedly.")
                    }
                }
                else -> {
                    codeScreen = null
                    problem = when (started) {
                        Answer.Busy -> "Too many devices are asking to sign in. Try again in a moment."
                        Answer.Unsupported -> "Update Agent Guild to use sign-in by code."
                        is Answer.Failed -> started.problem.message
                        else -> "Agent Guild answered unexpectedly."
                    }
                }
            }
        }
    }

    codeScreen?.let { screen ->
        val leave = {
            requesting?.cancel()
            codeScreen = null
        }
        BackHandler(onBack = leave)
        Scaffold { padding ->
            CodeView(screen, onNewCode = ::getCode, onCancel = leave, Modifier.padding(padding))
        }
        return
    }

    if (scanning) {
        BackHandler { scanning = false }
        Scanner(
            onScan = { scanned ->
                // Cancel sets scanning to false at once, before the scanner leaves the screen.
                if (!scanning) return@Scanner
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
            val addressFields = @Composable {
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        problem = null
                    },
                    modifier = Modifier.fillMaxWidth().testTag("address"),
                    label = { Text("Computer address") },
                    placeholder = { Text("computer.tailnet.ts.net") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    singleLine = true,
                )
                Text(
                    "As shown in Agent Guild on your computer under Settings → Remote access. You then approve a code there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = ::getCode, enabled = parsedAddress != null && checking == null, modifier = Modifier.fillMaxWidth().testTag("getCode")) {
                    Text("Get Code")
                }
            }
            if (tv) addressFields()
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
            if (!tv) addressFields()
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

@Composable
private fun CodeView(screen: CodeScreen, onNewCode: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (screen.code == null) {
            CircularProgressIndicator()
            Text("Asking for a code…")
        } else {
            Text(screen.code, Modifier.testTag("code"), style = MaterialTheme.typography.displayLarge, fontFamily = FontFamily.Monospace)
            Text(
                "On your computer or phone, open Agent Guild and approve this code.",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            screen.expiresAt?.let {
                Text(
                    "This code expires at ${DateFormat.getTimeFormat(context).format(Date.from(it))}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (screen.ended != null) {
                Text(screen.ended, Modifier.testTag("ended"), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Button(onClick = onNewCode, modifier = Modifier.testTag("newCode")) { Text("New Code") }
            } else {
                CircularProgressIndicator()
            }
        }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}
