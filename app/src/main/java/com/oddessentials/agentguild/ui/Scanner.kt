package com.oddessentials.agentguild.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.oddessentials.agentguild.AgentGuildApp
import com.oddessentials.agentguild.app
import com.oddessentials.agentguild.scan.QrReader
import java.util.concurrent.Executors

/** The camera, until it sees a QR code that holds a sign-in link. */
@Composable
fun Scanner(onScan: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var permitted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        refused = !granted
    }
    LaunchedEffect(Unit) { if (!permitted) ask.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize()) {
        if (permitted) {
            CameraPreview(onScan)
        } else if (refused) {
            Text(
                "Agent Guild needs the camera to scan the sign-in code. You can paste the sign-in link instead.",
                Modifier.align(Alignment.Center).padding(24.dp),
                textAlign = TextAlign.Center,
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (permitted) {
                Text("Point the camera at the sign-in code.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            Button(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(onScan: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val scanner = remember { QrReader.client() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var done by remember { mutableStateOf(false) }
    // The effect outlives the composition that started it, so it calls the newest onScan.
    val currentOnScan by rememberUpdatedState(onScan)

    DisposableEffect(lifecycleOwner) {
        val deliver = { texts: List<String> ->
            val link = texts.firstOrNull { AgentGuildApp.parseLink(it) != null }
            if (link != null && !done) {
                done = true
                currentOnScan(link)
            }
        }
        context.app.scanResults?.let { injected ->
            injected(deliver)
            return@DisposableEffect onDispose {
                scanner.close()
                executor.shutdown()
            }
        }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val image = proxy.image
                if (image == null || done) {
                    proxy.close()
                    return@setAnalyzer
                }
                val input = InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees)
                QrReader.read(scanner, input).addOnCompleteListener(ContextCompat.getMainExecutor(context)) { task ->
                    proxy.close()
                    if (task.isSuccessful) deliver(task.result)
                }
            }
            val camera = provider!!
            val selector = if (camera.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            camera.unbindAll()
            camera.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            provider?.unbindAll()
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView({ previewView }, Modifier.fillMaxSize())
}
