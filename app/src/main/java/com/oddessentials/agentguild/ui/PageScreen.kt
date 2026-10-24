package com.oddessentials.agentguild.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.oddessentials.agentguild.app
import com.oddessentials.agentguild.model.Destination
import com.oddessentials.agentguild.model.Health
import com.oddessentials.agentguild.model.HealthProblem
import com.oddessentials.agentguild.model.HealthResult
import com.oddessentials.agentguild.model.Page
import com.oddessentials.agentguild.model.PageView
import com.oddessentials.agentguild.model.Recovery
import com.oddessentials.agentguild.web.PageWeb

/**
 * A computer's page, or the demo. A saved computer is checked whenever the screen resumes: a
 * rejected token or a changed address offers to sign in again, and other failures keep the page
 * with a banner. On a TV, the page is the compact Yard, full screen, and Back closes the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScreen(destination: Destination, onBack: () -> Unit, onSignIn: (id: String, scan: Boolean) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val tv = remember { app.tv.isTv(context) }
    val computers by app.store.computers.collectAsState(initial = null)
    val computer = (destination as? Destination.Saved)?.let { saved -> computers?.firstOrNull { it.id == saved.id } }
    val page = when (destination) {
        Destination.Demo -> Page.demo
        is Destination.Saved -> computer?.let(Page::of)
    }
    // Saved with this window's state.
    var viewValue by rememberSaveable { mutableStateOf(PageView.Yard.value) }
    val view = if (tv) PageView.Yard else PageView.of(viewValue) ?: PageView.Yard

    var attempt by remember { mutableIntStateOf(0) }
    var checked by remember(page) { mutableStateOf(false) }
    var problem by remember(page) { mutableStateOf<HealthProblem?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { attempt++ }
    LaunchedEffect(page, attempt) {
        if (destination == Destination.Demo || page == null || attempt == 0) return@LaunchedEffect
        val result = Health.check(page.address, page.token!!, app::isOnline)
        checked = Recovery.showsPage(result, checked)
        problem = (result as? HealthResult.Failed)?.problem
    }
    val showsPage = page != null && (destination == Destination.Demo || checked)
    val activity = LocalActivity.current
    BackHandler(enabled = tv && showsPage) { activity?.finish() }

    Scaffold(
        topBar = {
            if (!tv) TopAppBar(
                title = { Text(if (destination == Destination.Demo) "Demo" else computer?.name ?: "Agent Guild", maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (page != null) {
                        SingleChoiceSegmentedButtonRow(Modifier.padding(end = 8.dp)) {
                            PageView.entries.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = option == view,
                                    onClick = { viewValue = option.value },
                                    shape = SegmentedButtonDefaults.itemShape(index, PageView.entries.size),
                                    modifier = Modifier.testTag("view:${option.value}"),
                                    icon = {},
                                ) { Text(option.title) }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding()) {
            when {
                page == null -> if (computers != null) Message("This computer was removed.")
                showsPage -> Column(Modifier.fillMaxSize()) {
                    problem?.let { Banner(it.message) }
                    WebPage(page, view, compact = tv, Modifier.weight(1f))
                }
                problem != null -> CantConnect(
                    problem!!,
                    onScan = { onSignIn((destination as Destination.Saved).id, true) },
                    onPaste = { onSignIn((destination as Destination.Saved).id, false) },
                    onRetry = {
                        problem = null
                        attempt++
                    },
                )
                else -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("Connecting…", Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun WebPage(page: Page, view: PageView, compact: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val app = context.app
    var pendingMicrophone by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingMicrophone?.invoke(granted)
        pendingMicrophone = null
    }
    val web = remember(page) {
        PageWeb(
            context, page, view, compact, app.links, app.events,
            object : PageWeb.MicrophoneAccess {
                override fun granted() =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

                override fun ask(result: (Boolean) -> Unit) {
                    pendingMicrophone?.invoke(false)
                    pendingMicrophone = result
                    askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        )
    }
    DisposableEffect(web) {
        web.start()
        onDispose { web.destroy() }
    }
    LaunchedEffect(web, view) { web.show(view) }

    Box(modifier.fillMaxWidth().then(if (compact) Modifier.keepScreenOn() else Modifier)) {
        web.webView?.let { webView ->
            key(webView) { AndroidView({ webView }, Modifier.fillMaxSize().testTag("page")) }
        }
        if (web.left) {
            Column(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("The page tried to leave ${page.origin.host}, so it was stopped.", textAlign = TextAlign.Center, modifier = Modifier.testTag("left"))
                Button(onClick = web::reload) { Text("Try Again") }
            }
        }
    }
}

@Composable
private fun CantConnect(problem: HealthProblem, onScan: () -> Unit, onPaste: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    val canScan = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Can't connect", style = MaterialTheme.typography.titleLarge)
        Text(problem.message, Modifier.testTag("problem"), textAlign = TextAlign.Center)
        if (problem.needsSignIn) {
            if (canScan) Button(onClick = onScan) { Text("Scan Sign-in Code") }
            OutlinedButton(onClick = onPaste) { Text("Paste Sign-in Link") }
        }
        TextButton(onClick = onRetry) { Text("Try Again") }
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp).testTag("banner"),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center)
    }
}
