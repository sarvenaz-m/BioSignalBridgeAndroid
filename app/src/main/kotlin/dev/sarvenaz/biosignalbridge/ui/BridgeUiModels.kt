package dev.sarvenaz.biosignalbridge.ui

import dev.sarvenaz.biosignalbridge.signal.WindowInspection

enum class BridgeTab { MONITOR, SESSIONS, DEVICES, SETTINGS }
enum class BridgeMode { PREVIEW, LIVE, REVIEW }

data class UiPoint(val timeSec: Float, val value: Float, val gapBefore: Boolean = false)
data class UiMarker(val id: String, val timeSec: Float, val label: String)
data class UiSession(
    val id: String,
    val name: String,
    val source: String,
    val startedAt: String,
    val durationSec: Float,
    val sampleCount: Long,
    val markerCount: Int = 0,
)
data class UiDevice(
    val address: String,
    val name: String,
    val detail: String,
    val rssi: Int? = null,
    val connected: Boolean = false,
)
data class BridgeUiState(
    val tab: BridgeTab = BridgeTab.MONITOR,
    val mode: BridgeMode = BridgeMode.PREVIEW,
    val phase: String = "Ready",
    val message: String = "",
    val running: Boolean = false,
    val recording: Boolean = false,
    val points: List<UiPoint> = emptyList(),
    val windowInspection: WindowInspection? = null,
    val markers: List<UiMarker> = emptyList(),
    val sessions: List<UiSession> = emptyList(),
    val devices: List<UiDevice> = emptyList(),
    val activeSessionId: String? = null,
    val activeSessionName: String = "MIT-BIH · Record 100",
    val durationSec: Float = 0f,
    val rateHz: Int = 250,
    val totalSamples: Long = 0,
    val lostPackets: Long = 0,
    val duplicates: Long = 0,
    val outOfOrder: Long = 0,
    val clockResets: Long = 0,
    val malformed: Long = 0,
    val overflow: Long = 0,
    val showWindowSec: Float = 8f,
    val windowEndSec: Float = 8f,
    val unit: String = "mV",
    val channelName: String = "ECG · MLII",
    val channelOptions: List<Pair<Int, String>> = emptyList(),
    val selectedChannel: Int = 0,
    val selectedDevice: String? = null,
    val scanning: Boolean = false,
)
data class BridgeActions(
    val onTab: (BridgeTab) -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onRecord: () -> Unit = {},
    val onMarker: (String) -> Unit = {},
    val onWindow: (Float) -> Unit = {},
    val onSeek: (Float) -> Unit = {},
    val onDeviceScan: () -> Unit = {},
    val onConnect: (String) -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onChannel: (Int) -> Unit = {},
    val onMode: (BridgeMode) -> Unit = {},
    val onSessionOpen: (String) -> Unit = {},
    val onExport: (String) -> Unit = {},
    val onRename: (String, String) -> Unit = { _, _ -> },
    val onDelete: (String) -> Unit = {},
)
