package com.darkton.gloveble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest

enum class AppTab(val label: String) {
    CONNECT("เชื่อมต่อ"),
    LIVE("Live"),
    RECORD("บันทึก"),
    DATASET("ข้อมูล"),
    SETTINGS("ตั้งค่า")
}

private fun tabIcon(tab: AppTab): ImageVector = when (tab) {
    AppTab.CONNECT -> Icons.Outlined.Bluetooth
    AppTab.LIVE -> Icons.Outlined.Timeline
    AppTab.RECORD -> Icons.Outlined.RadioButtonChecked
    AppTab.DATASET -> Icons.Outlined.Folder
    AppTab.SETTINGS -> Icons.Outlined.Settings
}

class MainActivity : ComponentActivity() {
    private val viewModel: BleViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) {
                startScanWhenReady()
            } else {
                viewModel.showPermissionDenied()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val themeController = remember { ThemeController(applicationContext) }
            GloveBleTheme(themeController) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val context = LocalContext.current

                LaunchedEffect(Unit) {
                    viewModel.events.collectLatest { event ->
                        when (event) {
                            is UiEvent.Toast -> Toast
                                .makeText(context, event.message, Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }

                GloveApp(
                    state = state,
                    themeController = themeController,
                    onScanClick = { startScanWhenReady() },
                    onStopScanClick = viewModel::stopScan,
                    onConnectClick = viewModel::connect,
                    onDisconnectClick = viewModel::disconnect,
                    onSaveNameClick = viewModel::writeDeviceName,
                    onSendCommandClick = viewModel::writeGloveCommand,
                    onSelectOledPage = viewModel::selectOledPage,
                    onStartRecording = viewModel::startRecording,
                    onCancelRecording = viewModel::cancelRecording,
                    onRefreshDataset = viewModel::refreshDataset,
                    onSelectDatasetSession = viewModel::selectDatasetSession,
                    onPlayDataset = viewModel::playDatasetSession,
                    onPauseDataset = viewModel::pauseDatasetPlayback,
                    onResetDataset = viewModel::resetDatasetPlayback,
                    onToggleLogs = viewModel::toggleLogs,
                    onDismissDialog = viewModel::dismissDialog,
                    onDialogAction = ::handleDialogAction
                )
            }
        }
    }

    private fun startScanWhenReady() {
        val packageManager = packageManager
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            viewModel.showBleUnsupported()
            return
        }

        val missingPermissions = requiredBlePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            viewModel.showBluetoothOff()
            return
        }

        viewModel.startScan()
    }

    private fun requiredBlePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun handleDialogAction(action: DialogAction) {
        when (action) {
            DialogAction.NONE -> Unit
            DialogAction.OPEN_BLUETOOTH_SETTINGS -> {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            DialogAction.OPEN_APP_SETTINGS -> {
                val uri = Uri.fromParts("package", packageName, null)
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            }
            DialogAction.OPEN_LOCATION_SETTINGS -> {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        viewModel.dismissDialog()
    }
}

@Composable
private fun GloveApp(
    state: BleUiState,
    themeController: ThemeController,
    onScanClick: () -> Unit,
    onStopScanClick: () -> Unit,
    onConnectClick: (String) -> Unit,
    onDisconnectClick: () -> Unit,
    onSaveNameClick: (String) -> Unit,
    onSendCommandClick: (String) -> Unit,
    onSelectOledPage: (OledDisplayPage) -> Unit,
    onStartRecording: (String) -> Unit,
    onCancelRecording: () -> Unit,
    onRefreshDataset: () -> Unit,
    onSelectDatasetSession: (String) -> Unit,
    onPlayDataset: () -> Unit,
    onPauseDataset: () -> Unit,
    onResetDataset: () -> Unit,
    onToggleLogs: () -> Unit,
    onDismissDialog: () -> Unit,
    onDialogAction: (DialogAction) -> Unit
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(AppTab.CONNECT.ordinal) }
    val tab = AppTab.entries[tabIndex]

    // Jump to the Live tab right after a successful connection.
    LaunchedEffect(state.status) {
        if (state.status == BleStatus.CONNECTED) {
            tabIndex = AppTab.LIVE.ordinal
        }
    }

    Scaffold(
        topBar = { ConnectionStatusBar(state) },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tabIndex = item.ordinal },
                        icon = {
                            Icon(
                                imageVector = tabIcon(item),
                                contentDescription = item.label
                            )
                        },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (tab) {
                AppTab.CONNECT -> ConnectScreen(
                    state = state,
                    onScanClick = onScanClick,
                    onStopScanClick = onStopScanClick,
                    onConnectClick = onConnectClick,
                    onDisconnectClick = onDisconnectClick,
                    onGoLive = { tabIndex = AppTab.LIVE.ordinal }
                )
                AppTab.LIVE -> LiveScreen(
                    state = state,
                    onSendCommandClick = onSendCommandClick,
                    onSelectOledPage = onSelectOledPage,
                    onGoConnect = { tabIndex = AppTab.CONNECT.ordinal }
                )
                AppTab.RECORD -> RecordScreen(
                    state = state,
                    onStartRecording = onStartRecording,
                    onCancelRecording = onCancelRecording,
                    onGoConnect = { tabIndex = AppTab.CONNECT.ordinal },
                    onGoDataset = { tabIndex = AppTab.DATASET.ordinal }
                )
                AppTab.DATASET -> DatasetScreen(
                    state = state,
                    onRefreshDataset = onRefreshDataset,
                    onSelectDatasetSession = onSelectDatasetSession,
                    onPlayDataset = onPlayDataset,
                    onPauseDataset = onPauseDataset,
                    onResetDataset = onResetDataset
                )
                AppTab.SETTINGS -> SettingsScreen(
                    state = state,
                    themeController = themeController,
                    onSaveNameClick = onSaveNameClick,
                    onSendCommandClick = onSendCommandClick,
                    onDisconnectClick = onDisconnectClick,
                    onToggleLogs = onToggleLogs
                )
            }
        }
    }

    state.dialog?.let { dialog ->
        AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text(dialog.title) },
            text = { Text(dialog.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (dialog.action == DialogAction.NONE) {
                            onDismissDialog()
                        } else {
                            onDialogAction(dialog.action)
                        }
                    }
                ) {
                    Text(
                        when (dialog.action) {
                            DialogAction.NONE -> "ตกลง"
                            DialogAction.OPEN_BLUETOOTH_SETTINGS -> "เปิด Bluetooth"
                            DialogAction.OPEN_APP_SETTINGS -> "เปิด Settings"
                            DialogAction.OPEN_LOCATION_SETTINGS -> "เปิด Location"
                        }
                    )
                }
            },
            dismissButton = {
                if (dialog.action != DialogAction.NONE) {
                    TextButton(onClick = onDismissDialog) {
                        Text("ปิด")
                    }
                }
            }
        )
    }
}

@Composable
private fun ConnectionStatusBar(state: BleUiState) {
    val targetColor = when (state.status) {
        BleStatus.DISCONNECTED -> MaterialTheme.colorScheme.error
        BleStatus.SCANNING -> MaterialTheme.colorScheme.secondary
        BleStatus.CONNECTING -> MaterialTheme.colorScheme.tertiary
        BleStatus.CONNECTED -> MaterialTheme.colorScheme.primary
    }
    val contentColor = when (state.status) {
        BleStatus.DISCONNECTED -> MaterialTheme.colorScheme.onError
        BleStatus.SCANNING -> MaterialTheme.colorScheme.onSecondary
        BleStatus.CONNECTING -> MaterialTheme.colorScheme.onTertiary
        BleStatus.CONNECTED -> MaterialTheme.colorScheme.onPrimary
    }
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 280),
        label = "statusColor"
    )

    Surface(
        color = color,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPulse(state = state, color = contentColor)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (state.status == BleStatus.CONNECTED) {
                        state.connectedName ?: state.status.label
                    } else {
                        state.status.label
                    },
                    color = contentColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = state.phase.label,
                    color = contentColor.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            if (state.status == BleStatus.SCANNING || state.status == BleStatus.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
private fun StatusPulse(state: BleUiState, color: Color) {
    val transition = rememberInfiniteTransition(label = "statusPulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "statusPulseProgress"
    )
    val active = state.status == BleStatus.SCANNING ||
        state.status == BleStatus.CONNECTING ||
        state.status == BleStatus.CONNECTED

    Canvas(modifier = Modifier.size(28.dp)) {
        val radius = size.minDimension / 4f
        if (active) {
            drawCircle(
                color = color.copy(alpha = (1f - progress) * 0.35f),
                radius = radius + progress * radius * 1.8f
            )
        }
        drawCircle(
            color = color.copy(alpha = if (state.status == BleStatus.CONNECTED) 1f else 0.82f),
            radius = radius
        )
    }
}
