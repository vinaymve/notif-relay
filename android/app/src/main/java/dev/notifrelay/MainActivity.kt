package dev.notifrelay

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // Re-checked on every resume, since the user grants access in system Settings and comes back.
    private var hasListenerAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                MainScreen(hasListenerAccess)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasListenerAccess = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(hasListenerAccess: Boolean) {
    val context = LocalContext.current
    val store = remember { Store(context) }
    val scope = rememberCoroutineScope()
    var pairing by remember { mutableStateOf(store.pairing) }
    var enabled by remember { mutableStateOf(store.enabledApps) }
    var query by remember { mutableStateOf("") }

    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.IO) { loadApps(context.packageManager, store.enabledApps) }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        val scanned = Pairing.parse(contents)
        if (scanned == null) {
            Toast.makeText(context, "That isn't a Notif Relay pairing code", Toast.LENGTH_LONG).show()
        } else {
            store.pairing = scanned
            pairing = scanned
            Toast.makeText(context, "Paired with your Mac", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Notif Relay") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SetupRow(
                            done = hasListenerAccess,
                            title = "Notification access",
                            detail = if (hasListenerAccess) "Granted" else "Needed to read notifications",
                        ) {
                            if (!hasListenerAccess) {
                                Button(onClick = {
                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                }) { Text("Grant") }
                            }
                        }
                        SetupRow(
                            done = pairing != null,
                            title = "Mac",
                            detail = if (pairing != null) "Paired" else "Scan the QR code from the Mac menu bar app",
                        ) {
                            val label = if (pairing == null) "Scan" else "Rescan"
                            OutlinedButton(onClick = {
                                scanner.launch(
                                    ScanOptions()
                                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        .setPrompt("Scan the QR code shown by Notif Relay on your Mac")
                                        .setBeepEnabled(false)
                                        .setOrientationLocked(false)
                                )
                            }) { Text(label) }
                        }
                        pairing?.let { p ->
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    scope.launch {
                                        val result = Relay.send(
                                            context, p,
                                            Content("Notif Relay", context.packageName, "Test from your phone", "Sync is working."),
                                        )
                                        val message = when (result) {
                                            Relay.Result.SENT -> "Sent. Check your Mac."
                                            Relay.Result.QUEUED -> "No connection to the relay. Will retry."
                                            Relay.Result.REJECTED -> "Relay rejected the message. Check the server in the Mac app."
                                        }
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                },
                            ) { Text("Send test notification") }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.size(12.dp))
                Text(
                    "Apps to sync (${enabled.size} selected)",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    placeholder = { Text("Search apps") },
                    singleLine = true,
                )
            }

            val list = apps
            if (list == null) {
                item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                val filtered = if (query.isBlank()) list else list.filter { it.label.contains(query.trim(), ignoreCase = true) }
                items(filtered, key = { it.pkg }) { app ->
                    val checked = app.pkg in enabled
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(app.icon, contentDescription = null, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(
                            app.label,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Switch(checked = checked, onCheckedChange = { on ->
                            store.setEnabled(app.pkg, on)
                            enabled = store.enabledApps
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupRow(done: Boolean, title: String, detail: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (done) "✓" else "•", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

/** Launchable apps, already-enabled ones first, then alphabetical. Sorted once so rows don't jump on toggle. */
private fun loadApps(pm: PackageManager, enabled: Set<String>): List<AppEntry> {
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != "dev.notifrelay" }
        .map {
            AppEntry(
                pkg = it.activityInfo.packageName,
                label = it.loadLabel(pm).toString(),
                icon = it.loadIcon(pm).toBitmap(96, 96).asImageBitmap(),
            )
        }
        .sortedWith(compareBy<AppEntry> { it.pkg !in enabled }.thenBy { it.label.lowercase() })
}
