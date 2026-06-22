package com.jt.snipshot

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { SettingsScreen() }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    val keepOriginal by Prefs.keepOriginal(context).collectAsState(initial = true)

    var crashLog by remember { mutableStateOf(CrashLogger.read(context)) }
    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasMedia by remember { mutableStateOf(PermissionHelpers.hasMediaImages(context)) }
    var hasAllFiles by remember { mutableStateOf(PermissionHelpers.hasAllFilesAccess()) }
    var batteryUnrestricted by remember { mutableStateOf(PermissionHelpers.isBatteryUnrestricted(context)) }
    var serviceRunning by remember { mutableStateOf(ScreenshotWatcher.isActive(context)) }
    var serviceError by remember { mutableStateOf(ServiceStatus.lastError) }
    var observerFires by remember { mutableIntStateOf(ServiceStatus.observerFires) }
    var lastUri by remember { mutableStateOf(ServiceStatus.lastUri) }
    var lastDecision by remember { mutableStateOf(ServiceStatus.lastDecision) }
    var lastAcceptedFire by remember { mutableStateOf(ServiceStatus.lastAcceptedFire) }
    var overlayAttempts by remember { mutableIntStateOf(ServiceStatus.overlayAttempts) }
    var overlayResult by remember { mutableStateOf(ServiceStatus.overlayResult) }
    var showDebug by remember { mutableStateOf(false) }

    val mediaLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMedia = granted }

    // Live-refresh diagnostics while the debug panel is open; otherwise the
    // numbers freeze at whatever they were on last ON_RESUME.
    LaunchedEffect(showDebug) {
        while (showDebug) {
            serviceRunning = ScreenshotWatcher.isActive(context)
            serviceError = ServiceStatus.lastError
            observerFires = ServiceStatus.observerFires
            lastUri = ServiceStatus.lastUri
            lastDecision = ServiceStatus.lastDecision
            lastAcceptedFire = ServiceStatus.lastAcceptedFire
            overlayAttempts = ServiceStatus.overlayAttempts
            overlayResult = ServiceStatus.overlayResult
            kotlinx.coroutines.delay(1000)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlay = Settings.canDrawOverlays(context)
                hasMedia = PermissionHelpers.hasMediaImages(context)
                hasAllFiles = PermissionHelpers.hasAllFilesAccess()
                batteryUnrestricted = PermissionHelpers.isBatteryUnrestricted(context)
                serviceRunning = ScreenshotWatcher.isActive(context)
                serviceError = ServiceStatus.lastError
                observerFires = ServiceStatus.observerFires
                lastUri = ServiceStatus.lastUri
                lastDecision = ServiceStatus.lastDecision
                lastAcceptedFire = ServiceStatus.lastAcceptedFire
                overlayAttempts = ServiceStatus.overlayAttempts
                overlayResult = ServiceStatus.overlayResult
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Snipshot", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Auto-crop screenshots the moment you take them. " +
                "Tap to keep full screen. Drag to crop.",
            style = MaterialTheme.typography.bodyMedium
        )

        crashLog?.let { log ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Last crash detected",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        log,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            CrashLogger.clear(context)
                            crashLog = null
                        }) { Text("Dismiss") }
                        TextButton(onClick = {
                            val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                            clip?.setPrimaryClip(android.content.ClipData.newPlainText("Snipshot crash", log))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }) { Text("Copy") }
                    }
                }
            }
        }

        HorizontalDivider()

        Text("Permissions", style = MaterialTheme.typography.titleMedium)

        PermissionRow(
            label = "Read screenshots",
            granted = hasMedia,
            onRequest = { mediaLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES) }
        )
        PermissionRow(
            label = "Draw over other apps",
            granted = hasOverlay,
            onRequest = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }
        )
        PermissionRow(
            label = "Unrestricted battery (recommended — so the overlay isn't delayed)",
            granted = batteryUnrestricted,
            onRequest = {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
                runCatching { context.startActivity(intent) }.onFailure {
                    // Some OEMs reject the direct dialog — fall back to the settings list.
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    }
                }
            }
        )
        PermissionRow(
            label = "Manage all files (optional — only to delete originals)",
            granted = hasAllFiles,
            onRequest = {
                val intent = if (Build.VERSION.SDK_INT >= 30) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                }
                context.startActivity(intent)
            }
        )

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Keep original screenshot", style = MaterialTheme.typography.titleSmall)
                Text(
                    "If off, the cropped version replaces the original.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = keepOriginal,
                onCheckedChange = { v -> scope.launch { Prefs.setKeepOriginal(context, v) } }
            )
        }

        if (!keepOriginal && !hasAllFiles) {
            Text(
                "Deleting originals needs \"Manage all files\" access. " +
                    "Grant it above, or turn \"Keep original\" on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // Status block — visible feedback so we always know if the service is up.
        Surface(
            color = if (serviceRunning) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (serviceRunning) "Status: Watching ✓" else "Status: Stopped",
                    style = MaterialTheme.typography.titleMedium
                )
                serviceError?.let { err ->
                    Text(
                        text = "Last error: $err",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        if (showDebug) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Observer fires: $observerFires • Overlay attempts: $overlayAttempts",
                        style = MaterialTheme.typography.bodySmall
                    )
                    lastUri?.let {
                        Text(
                            text = "Last URI: $it",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2
                        )
                    }
                    lastDecision?.let {
                        Text(
                            text = "Last decision: $it",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3
                        )
                    }
                    lastAcceptedFire?.let {
                        Text(
                            text = "Last accepted: $it",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2
                        )
                    }
                    overlayResult?.let {
                        Text(
                            text = "Overlay result: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("OK")) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                            maxLines = 3
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        val ready = hasMedia && hasOverlay && (keepOriginal || hasAllFiles)
        Button(
            onClick = {
                val err = ScreenshotWatcher.start(context)
                if (err == null) {
                    serviceRunning = true
                    Toast.makeText(context, "Watching for screenshots", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Start failed: $err", Toast.LENGTH_LONG).show()
                }
            },
            enabled = ready,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (ready) "Start watching" else "Grant required permissions") }

        OutlinedButton(
            onClick = {
                ScreenshotWatcher.stop(context)
                serviceRunning = false
                Toast.makeText(context, "Stopped watching", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Stop") }

        TextButton(
            onClick = { showDebug = !showDebug },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (showDebug) "Hide debug info" else "Show debug info") }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        if (granted) Text("Granted")
        else TextButton(onClick = onRequest) { Text("Grant") }
    }
}
