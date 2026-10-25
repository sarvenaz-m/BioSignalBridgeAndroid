package dev.sarvenaz.biosignalbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.sarvenaz.biosignalbridge.ui.BioSignalBridgeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: BridgeViewModel by viewModels()
    private var pendingAction: (() -> Unit)? = null
    private var pendingPermissions = emptyList<String>()
    private var exportId: String? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pendingAction
        pendingAction = null
        if (pendingPermissions.all { permission -> checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED }) action?.invoke()
        else model.engine.showMessage("Allow nearby-device access to use a live sensor.")
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = exportId
        exportId = null
        if (uri != null && id != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = model.engine.exportArchive(id)
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("Could not open the selected destination.")
                }
            }
            model.engine.showMessage(result.fold({ "Session saved as CSV and metadata." }, { it.message ?: "Could not export the session. Please choose another destination." }))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportId = savedInstanceState?.getString("export-session")
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.rgb(18, 60, 74)),
            navigationBarStyle = SystemBarStyle.light(Color.rgb(243, 246, 245), Color.rgb(18, 60, 74)),
        )
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            BioSignalBridgeApp(state, model.actions.copy(
                onDeviceScan = { withBluetoothPermissions(true) { model.engine.scan() } },
                onConnect = { address -> withBluetoothPermissions(false) {
                    runCatching {
                        startForegroundService(Intent(this, CaptureService::class.java).setAction(CaptureService.CONNECT).putExtra(CaptureService.ADDRESS, address))
                    }.onFailure { model.engine.showMessage("Could not start the sensor connection. Please try again.") }
                } },
                onExport = { id -> exportId = id; export.launch("BioSignalBridge-session.zip") },
            ))
        }
    }

    private fun withBluetoothPermissions(scan: Boolean, action: () -> Unit) {
        val required = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) + if (scan) listOf(Manifest.permission.BLUETOOTH_SCAN) else emptyList()
            else if (scan) listOf(Manifest.permission.ACCESS_FINE_LOCATION) else emptyList()
        if (required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) action()
        else {
            pendingPermissions = required
            pendingAction = action
            val requests = required + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
            permissions.launch(requests.distinct().toTypedArray())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("export-session", exportId)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) model.engine.onBackground()
    }
}
