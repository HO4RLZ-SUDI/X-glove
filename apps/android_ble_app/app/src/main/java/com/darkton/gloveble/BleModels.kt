package com.darkton.gloveble

import java.util.UUID

val Esp32ServiceUuid: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
val Esp32NameCharacteristicUuid: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
val ClientCharacteristicConfigUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

const val Esp32NamePrefix = "ESP32"
val GloveNamePrefixes = listOf("ESP32", "Glove", "SmartGlove", "BLEGlove")
const val MaxDeviceNameChars = 20
const val MaxCommandChars = 80
const val MaxPayloadPreviewChars = 240

const val RecordingCountdownSec = 3
const val RecordingDurationMs = 3000L
const val FlexChannels = 5

/**
 * Which physical glove a connection / sample belongs to. The app holds two
 * simultaneous BLE links — one per hand — and the slot is assigned
 * automatically from the device name (see BleViewModel.handFromName).
 */
enum class Hand(val label: String, val short: String) {
    LEFT("มือซ้าย", "L"),
    RIGHT("มือขวา", "R");

    companion object {
        fun fromShort(code: String?): Hand? = when (code?.trim()?.uppercase()) {
            "L", "LEFT" -> LEFT
            "R", "RIGHT" -> RIGHT
            else -> null
        }
    }
}

/**
 * One telemetry sample: five flex channels plus the MPU6050 accel/gyro (raw
 * int16 counts straight from the sensor). IMU fields default to 0 so samples
 * and CSV rows recorded before the IMU existed still load. `hand` tags which
 * glove produced the sample so a dual-hand recording stays separable.
 */
data class FlexSample(
    val timestampMs: Long,
    val f1: Int, val f2: Int, val f3: Int, val f4: Int, val f5: Int,
    val ax: Int = 0, val ay: Int = 0, val az: Int = 0,
    val gx: Int = 0, val gy: Int = 0, val gz: Int = 0,
    val hand: Hand = Hand.LEFT
)

data class GestureSession(
    val id: String,
    val label: String,
    val session: String,
    val samples: List<FlexSample>
) {
    val sampleCount: Int get() = samples.size
    val durationMs: Long get() = samples.lastOrNull()?.timestampMs ?: 0L
    val hands: List<Hand> get() = samples.map { it.hand }.distinct().sortedBy { it.ordinal }
}

enum class RecordingState { IDLE, COUNTDOWN, RECORDING }

enum class OledDisplayPage(
    val label: String,
    val command: String
) {
    DASHBOARD("Dashboard", "OLED:DASH"),
    TELEMETRY("Telemetry", "OLED:TELEM"),
    SYSTEM("System", "OLED:SYS"),
    HAND("Hand", "OLED:HAND"),
    WAVE("Wave", "OLED:WAVE")
}

enum class BleStatus(val label: String) {
    DISCONNECTED("Disconnected"),
    SCANNING("Scanning"),
    CONNECTING("Connecting"),
    CONNECTED("Connected")
}

enum class ConnectionPhase(val label: String) {
    IDLE("Ready"),
    SCANNING("Scanning for glove"),
    CONNECTING("Opening BLE link"),
    DISCOVERING("Finding glove service"),
    STARTING_STREAM("Starting live stream"),
    CONNECTED("Glove online")
}

enum class DialogAction {
    NONE,
    OPEN_BLUETOOTH_SETTINGS,
    OPEN_APP_SETTINGS,
    OPEN_LOCATION_SETTINGS
}

data class UiDialog(
    val title: String,
    val message: String,
    val action: DialogAction = DialogAction.NONE
)

data class BleDeviceItem(
    val address: String,
    val name: String,
    val rssi: Int,
    val lastSeenMillis: Long,
    val advertisesService: Boolean,
    val isLikelyGlove: Boolean
)

/** Everything about a single glove link (one hand). */
data class HandConnection(
    val hand: Hand,
    val status: BleStatus = BleStatus.DISCONNECTED,
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val connectedName: String? = null,
    val connectedAddress: String? = null,
    val connectedRssi: Int? = null,
    val connectedUptimeSeconds: Long = 0,
    val latestPayload: String? = null,
    val latestPayloadAtMillis: Long? = null,
    val glovePacketCount: Int = 0,
    val selectedOledPage: OledDisplayPage = OledDisplayPage.DASHBOARD,
    val isSavingName: Boolean = false,
    val isSendingCommand: Boolean = false,
    /** Last confidently recognized sign for this hand (null until one is found). */
    val recognizedWord: String? = null,
    /** Live recognition confidence 0..1 for the most recent reading. */
    val recognitionConfidence: Float = 0f
) {
    val isConnected: Boolean get() = status == BleStatus.CONNECTED
    val isBusy: Boolean get() = isSavingName || isSendingCommand
}

data class BleUiState(
    val left: HandConnection = HandConnection(Hand.LEFT),
    val right: HandConnection = HandConnection(Hand.RIGHT),
    val isScanning: Boolean = false,
    val scanPhase: ConnectionPhase = ConnectionPhase.IDLE,
    val devices: List<BleDeviceItem> = emptyList(),
    val showLogs: Boolean = false,
    val logs: List<String> = emptyList(),
    val dialog: UiDialog? = null,
    val recordingState: RecordingState = RecordingState.IDLE,
    val recordingCountdown: Int = 0,
    val recordingSampleCount: Int = 0,
    val lastSavedPath: String? = null,
    val datasetSessions: List<GestureSession> = emptyList(),
    val selectedDatasetSessionId: String? = null,
    val datasetPlaybackSampleIndex: Int = 0,
    val datasetPlaybackPlaying: Boolean = false,
    val datasetCsvPath: String? = null,
    val datasetMessage: String? = null,
    val isDatasetLoading: Boolean = false,
    /** When on, live packets are classified into signs on the Live screen. */
    val recognitionEnabled: Boolean = false,
    /** How many distinct signs the recognizer can currently tell apart. */
    val recognizerLabelCount: Int = 0
) {
    fun hand(h: Hand): HandConnection = if (h == Hand.LEFT) left else right

    val hands: List<HandConnection> get() = listOf(left, right)
    val connectedHands: List<HandConnection> get() = hands.filter { it.isConnected }
    val anyConnected: Boolean get() = left.isConnected || right.isConnected
    val anyConnecting: Boolean
        get() = left.status == BleStatus.CONNECTING || right.status == BleStatus.CONNECTING
    val connectedCount: Int get() = connectedHands.size
}

sealed interface UiEvent {
    data class Toast(val message: String) : UiEvent
}
