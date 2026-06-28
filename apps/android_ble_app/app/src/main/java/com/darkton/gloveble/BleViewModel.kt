package com.darkton.gloveble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.os.Environment
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.FileWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PendingWriteKind {
    DEVICE_NAME,
    COMMAND
}

private data class CsvSaveResult(
    val path: String,
    val session: String
)

private data class DatasetLoadResult(
    val path: String,
    val sessions: List<GestureSession>,
    val message: String?
)

class BleViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val scannedDevices = linkedMapOf<String, BluetoothDevice>()
    private var scanActive = false
    private var scanWatchdogJob: Job? = null

    private var tts: TextToSpeech? = null

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    /** Per-hand runtime state for the two simultaneous GATT connections. */
    private inner class HandLink(val hand: Hand) {
        var gatt: BluetoothGatt? = null
        var nameCharacteristic: BluetoothGattCharacteristic? = null
        var connectedAtMillis = 0L
        var connectingAddress: String? = null
        var connectAttempt = 0
        var rssiJob: Job? = null
        var uptimeJob: Job? = null
        var connectionTimeoutJob: Job? = null
        var saveTimeoutJob: Job? = null
        var commandTimeoutJob: Job? = null
        var nameConfirmReadJob: Job? = null
        var pendingWriteKind: PendingWriteKind? = null
        var pendingName: String? = null
        val callback = HandGattCallback(hand)
    }

    private val leftLink = HandLink(Hand.LEFT)
    private val rightLink = HandLink(Hand.RIGHT)
    private fun link(hand: Hand): HandLink = if (hand == Hand.LEFT) leftLink else rightLink

    private val recordingBuffer = mutableListOf<FlexSample>()
    private var recordingLabel = ""
    private var recordingStartMs = 0L
    private var countdownJob: Job? = null
    private var autoStopJob: Job? = null
    private var datasetPlaybackJob: Job? = null

    /** Static + dynamic sign classifier, rebuilt whenever the dataset reloads. */
    private var recognizer: GestureRecognizer? = null

    /** Per-hand live recognition state (smoothing window + gesture capture). */
    private val recognitionStates = mutableMapOf<Hand, HandRecognitionState>()

    /**
     * Tracks the still/moving state machine for one hand: a short flex window for
     * static poses, plus a capture buffer that fills while the hand is moving.
     */
    private class HandRecognitionState {
        val flexWindow = ArrayDeque<List<Int>>()
        var capturing = false
        val gestureBuffer = mutableListOf<FloatArray>()
        var stillFrames = 0
    }

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("th", "TH")
            }
        }
        refreshDataset()
    }

    // ---------- State helpers ----------

    private fun updateHand(hand: Hand, transform: (HandConnection) -> HandConnection) {
        _uiState.update { state ->
            if (hand == Hand.LEFT) {
                state.copy(left = transform(state.left))
            } else {
                state.copy(right = transform(state.right))
            }
        }
    }

    private fun handState(hand: Hand): HandConnection = _uiState.value.hand(hand)

    // ---------- Scanning ----------

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advertisedName = result.scanRecord?.deviceName
            val fallbackName = if (hasConnectPermission()) result.device.name else null
            val hasService = result.scanRecord
                ?.serviceUuids
                ?.any { it.uuid == Esp32ServiceUuid } == true
            // On Android 13 the device name can be null on first scan of an
            // unpaired device even with BLUETOOTH_CONNECT granted. Fall back to
            // a glove-like name so the filter below doesn't silently drop it;
            // the real name shows up once GATT connects.
            val name = advertisedName ?: fallbackName
                ?: if (hasService) "Glove-ESP32" else "Unknown"
            val nameMatchesGlove = isLikelyGloveName(name)

            if (!nameMatchesGlove && !hasService) {
                return
            }

            scannedDevices[result.device.address] = result.device
            val now = SystemClock.elapsedRealtime()
            val device = BleDeviceItem(
                address = result.device.address,
                name = name,
                rssi = result.rssi,
                lastSeenMillis = now,
                advertisesService = hasService,
                isLikelyGlove = nameMatchesGlove || hasService
            )

            _uiState.update { state ->
                val merged = (state.devices.filterNot { it.address == device.address } + device)
                    .sortedWith(compareByDescending<BleDeviceItem> { it.rssi }.thenBy { it.name })
                state.copy(devices = merged)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanActive = false
            val message = "BLE scan failed: $errorCode"
            addLog(message)
            _uiState.update {
                it.copy(
                    isScanning = false,
                    scanPhase = ConnectionPhase.IDLE,
                    dialog = UiDialog(
                        title = "Scan failed",
                        message = "สแกน BLE ไม่สำเร็จ ($errorCode). ลองปิด/เปิด Bluetooth แล้วสแกนใหม่"
                    )
                )
            }
        }
    }

    fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            showBleUnsupported()
            return
        }
        if (!hasScanPermission()) {
            showPermissionDenied()
            return
        }
        if (!hasConnectPermission()) {
            showPermissionDenied()
            return
        }
        if (!adapter.isEnabled) {
            showBluetoothOff()
            return
        }
        // Below Android 12, BLE scanning silently returns zero results when
        // Location Services are off — the classic "works on my phone, not my
        // friend's" symptom. Block early with an actionable prompt.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationServiceEnabled()) {
            showLocationOff()
            return
        }

        stopScan()
        scannedDevices.clear()
        // Scanning does NOT touch the existing connections — the user may already
        // have one hand online and be scanning to add the other.
        _uiState.update {
            it.copy(
                isScanning = true,
                scanPhase = ConnectionPhase.SCANNING,
                devices = emptyList(),
                dialog = null
            )
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            showBluetoothOff()
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            @SuppressLint("MissingPermission")
            scanner.startScan(null, settings, scanCallback)
            scanActive = true
            addLog("Scanning for ESP32 or service $Esp32ServiceUuid")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isLocationServiceEnabled()) {
                addLog("Location Services off on Android 12+; realme/Xiaomi/Oppo need it for BLE scan")
                _uiState.update { it.copy(dialog = locationDialog()) }
            }
            startScanWatchdog()
        } catch (error: SecurityException) {
            addLog("Scan permission error: ${error.message}")
            showPermissionDenied()
        }
    }

    private fun startScanWatchdog() {
        scanWatchdogJob?.cancel()
        scanWatchdogJob = viewModelScope.launch {
            delay(8000)
            val state = _uiState.value
            if (state.isScanning && state.devices.isEmpty()) {
                if (!isLocationServiceEnabled()) {
                    addLog("No devices after 8s and Location Services off")
                    _uiState.update { it.copy(dialog = locationDialog()) }
                } else {
                    addLog("No devices found after 8s of scanning")
                }
            }
        }
    }

    fun stopScan() {
        scanWatchdogJob?.cancel()
        scanWatchdogJob = null
        val adapter = bluetoothAdapter ?: return
        if (!scanActive || !hasScanPermission()) {
            _uiState.update { it.copy(isScanning = false, scanPhase = ConnectionPhase.IDLE) }
            return
        }
        runCatching {
            @SuppressLint("MissingPermission")
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
        }
        scanActive = false
        _uiState.update { it.copy(isScanning = false, scanPhase = ConnectionPhase.IDLE) }
        addLog("Scan stopped")
    }

    // ---------- Connecting ----------

    fun connect(address: String) {
        val device = scannedDevices[address]
        if (device == null) {
            emitToast("ไม่พบอุปกรณ์นี้แล้ว ลองสแกนใหม่")
            return
        }
        if (!hasConnectPermission()) {
            showPermissionDenied()
            return
        }

        val name = safeDeviceName(device) ?: deviceItemName(address)
        val hand = resolveHand(address, name)

        // If this slot is already busy, tear it down before reusing it.
        closeLink(hand)

        stopScan()
        updateHand(hand) {
            it.copy(
                status = BleStatus.CONNECTING,
                phase = ConnectionPhase.CONNECTING,
                connectedName = name ?: "ESP32",
                connectedAddress = address,
                connectedRssi = null,
                connectedUptimeSeconds = 0,
                latestPayload = null,
                latestPayloadAtMillis = null,
                glovePacketCount = 0,
                isSavingName = false,
                isSendingCommand = false
            )
        }
        _uiState.update { it.copy(dialog = null) }
        addLog("Connecting ${hand.short} to $address")
        val handLink = link(hand)
        handLink.connectingAddress = address
        handLink.connectAttempt = 0
        startConnectionTimeout(hand, address)
        openGatt(hand, address)
    }

    /** Picks which hand slot a device should occupy, from its name then free slots. */
    private fun resolveHand(address: String, name: String?): Hand {
        handFromName(name)?.let { return it }
        val state = _uiState.value
        val leftFree = !state.left.isConnected && state.left.status != BleStatus.CONNECTING
        val rightFree = !state.right.isConnected && state.right.status != BleStatus.CONNECTING
        return when {
            // Don't displace a slot already pointed at this address.
            state.left.connectedAddress == address -> Hand.LEFT
            state.right.connectedAddress == address -> Hand.RIGHT
            leftFree -> Hand.LEFT
            rightFree -> Hand.RIGHT
            else -> Hand.LEFT
        }
    }

    private fun handFromName(name: String?): Hand? {
        if (name.isNullOrBlank()) return null
        val trimmed = name.trim()
        val lower = trimmed.lowercase(Locale.US)
        if (lower.contains("right") || trimmed.contains("ขวา")) return Hand.RIGHT
        if (lower.contains("left") || trimmed.contains("ซ้าย")) return Hand.LEFT
        // Fall back to a trailing single letter, e.g. "Glove R" / "Glove-L".
        return when (trimmed.trimEnd().lastOrNull()?.uppercaseChar()) {
            'R' -> Hand.RIGHT
            'L' -> Hand.LEFT
            else -> null
        }
    }

    private fun deviceItemName(address: String): String? =
        _uiState.value.devices.firstOrNull { it.address == address }?.name

    @SuppressLint("MissingPermission")
    private fun openGatt(hand: Hand, address: String) {
        val device = scannedDevices[address] ?: bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            failConnection(hand, "ไม่พบอุปกรณ์นี้แล้ว ลองสแกนใหม่")
            return
        }
        if (!hasConnectPermission()) {
            showPermissionDenied()
            return
        }

        val handLink = link(hand)
        try {
            handLink.gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, handLink.callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, handLink.callback)
            }
            if (handLink.gatt == null) {
                failConnection(hand, "เริ่มเชื่อมต่อ BLE ไม่สำเร็จ")
            }
        } catch (error: SecurityException) {
            addLog("Connect permission error: ${error.message}")
            handLink.connectionTimeoutJob?.cancel()
            showPermissionDenied()
        }
    }

    fun disconnect(hand: Hand) {
        addLog("Disconnect requested (${hand.short})")
        val handLink = link(hand)
        handLink.connectingAddress = null
        handLink.connectAttempt = 0
        stopConnectedTickers(hand)
        if (hasConnectPermission()) {
            runCatching {
                @SuppressLint("MissingPermission")
                handLink.gatt?.disconnect()
            }
        }
        closeLink(hand)
        updateHand(hand) { HandConnection(hand) }
    }

    fun disconnectAll() {
        disconnect(Hand.LEFT)
        disconnect(Hand.RIGHT)
    }

    // ---------- GATT callback (one instance per hand) ----------

    private inner class HandGattCallback(val hand: Hand) : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val handLink = link(hand)
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    addLog("GATT connection error (${hand.short}): $status")
                    // Error 133 (and friends) is a flaky, device-specific GATT failure
                    // common on Realme/Oppo/Xiaomi. Retry the direct connection a few
                    // times before giving up instead of failing on the first attempt.
                    val address = handLink.connectingAddress
                    if (
                        handState(hand).status == BleStatus.CONNECTING &&
                        address != null &&
                        handLink.connectAttempt < MAX_CONNECT_ATTEMPTS
                    ) {
                        handLink.connectAttempt++
                        addLog("Retrying ${hand.short} (attempt ${handLink.connectAttempt}) after error $status")
                        closeLink(hand)
                        viewModelScope.launch {
                            delay(450)
                            if (handState(hand).status == BleStatus.CONNECTING) {
                                openGatt(hand, address)
                            }
                        }
                    } else {
                        failConnection(hand, "เชื่อมต่อ BLE ไม่สำเร็จ ($status)")
                    }
                }
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    addLog("Connected (${hand.short}), negotiating MTU")
                    if (!hasConnectPermission()) {
                        showPermissionDenied()
                        handleDisconnected(hand, lost = false)
                        return
                    }
                    updateHand(hand) { it.copy(phase = ConnectionPhase.DISCOVERING) }
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    // discoverServices() is called in onMtuChanged once MTU exchange
                    // completes; calling both simultaneously causes GATT error 133 on
                    // Android 13 because the two operations overlap on the ATT channel.
                    gatt.requestMtu(185)
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    addLog("Disconnected (${hand.short})")
                    val wasActive = handState(hand).status == BleStatus.CONNECTED ||
                        handState(hand).status == BleStatus.CONNECTING
                    handleDisconnected(hand, lost = wasActive)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection(hand, "ค้นหา service ไม่สำเร็จ ($status)")
                return
            }

            val service = gatt.getService(Esp32ServiceUuid)
            val characteristic = service?.getCharacteristic(Esp32NameCharacteristicUuid)
            if (service == null || characteristic == null) {
                failConnection(hand, "ไม่พบ Service/Characteristic ของ ESP32 ในอุปกรณ์นี้")
                return
            }

            val handLink = link(hand)
            handLink.nameCharacteristic = characteristic
            handLink.connectedAtMillis = SystemClock.elapsedRealtime()
            handLink.connectionTimeoutJob?.cancel()
            updateHand(hand) { it.copy(phase = ConnectionPhase.STARTING_STREAM) }

            val device = gatt.device
            val name = safeDeviceName(device) ?: handState(hand).connectedName ?: "ESP32"
            updateHand(hand) {
                it.copy(
                    status = BleStatus.CONNECTED,
                    phase = ConnectionPhase.CONNECTED,
                    connectedName = name,
                    connectedAddress = device.address,
                    connectedUptimeSeconds = 0,
                    isSavingName = false,
                    isSendingCommand = false
                )
            }
            _uiState.update { it.copy(dialog = null) }
            addLog("GATT ready (${hand.short}): ${device.address}")
            startConnectedTickers(hand)
            if (!enableNotifications(gatt, characteristic)) {
                readNameCharacteristic(gatt, characteristic)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != ClientCharacteristicConfigUuid) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Notification enabled (${hand.short})")
            } else {
                addLog("Notification descriptor failed (${hand.short}): $status")
            }
            val characteristic = link(hand).nameCharacteristic ?: return
            readNameCharacteristic(gatt, characteristic)
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            addLog("MTU changed (${hand.short}): mtu=$mtu status=$status")
            // Only trigger discovery on the initial connection (phase == DISCOVERING).
            // Subsequent MTU changes (e.g. from a reconnect race) should be ignored.
            if (handState(hand).phase != ConnectionPhase.DISCOVERING) return
            if (!hasConnectPermission()) {
                showPermissionDenied()
                handleDisconnected(hand, lost = false)
                return
            }
            addLog("Starting service discovery (${hand.short})")
            val discoveryStarted = gatt.discoverServices()
            if (!discoveryStarted) {
                failConnection(hand, "เริ่มค้นหา service ไม่สำเร็จ")
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                updateHand(hand) { it.copy(connectedRssi = rssi) }
            }
        }

        @Deprecated("Deprecated in Android 13, kept for older devices")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid == Esp32NameCharacteristicUuid &&
                status == BluetoothGatt.GATT_SUCCESS
            ) {
                @Suppress("DEPRECATION")
                handleCharacteristicPayload(hand, characteristic.value, fromNotification = false)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (characteristic.uuid == Esp32NameCharacteristicUuid &&
                status == BluetoothGatt.GATT_SUCCESS
            ) {
                handleCharacteristicPayload(hand, value, fromNotification = false)
            }
        }

        @Deprecated("Deprecated in Android 13, kept for older devices")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == Esp32NameCharacteristicUuid) {
                @Suppress("DEPRECATION")
                handleCharacteristicPayload(hand, characteristic.value, fromNotification = true)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == Esp32NameCharacteristicUuid) {
                handleCharacteristicPayload(hand, value, fromNotification = true)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != Esp32NameCharacteristicUuid) return
            val handLink = link(hand)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (handLink.pendingWriteKind) {
                    PendingWriteKind.DEVICE_NAME -> {
                        addLog("Name write sent (${hand.short})")
                        scheduleNameReadBack(hand, gatt, characteristic)
                    }
                    PendingWriteKind.COMMAND -> {
                        handLink.commandTimeoutJob?.cancel()
                        handLink.pendingWriteKind = null
                        updateHand(hand) { it.copy(isSendingCommand = false) }
                        emitToast("ส่งคำสั่งแล้ว (${hand.short})")
                        addLog("Command write sent (${hand.short})")
                    }
                    null -> addLog("Write sent (${hand.short})")
                }
            } else {
                handLink.saveTimeoutJob?.cancel()
                handLink.commandTimeoutJob?.cancel()
                handLink.pendingWriteKind = null
                handLink.pendingName = null
                updateHand(hand) {
                    it.copy(isSavingName = false, isSendingCommand = false)
                }
                emitToast("ส่งข้อมูลไม่สำเร็จ ($status)")
                addLog("Characteristic write failed (${hand.short}): $status")
            }
        }
    }

    // ---------- Writes (per hand) ----------

    fun writeDeviceName(hand: Hand, name: String) {
        val cleanName = name.trim()
        val handLink = link(hand)
        when {
            cleanName.isEmpty() -> {
                emitToast("กรุณาใส่ชื่ออุปกรณ์")
                return
            }
            cleanName.length > MaxDeviceNameChars -> {
                emitToast("ชื่อยาวเกิน $MaxDeviceNameChars ตัวอักษร")
                return
            }
            !handState(hand).isConnected -> {
                emitToast("ยังไม่ได้เชื่อมต่อ ${hand.label}")
                return
            }
            !hasConnectPermission() -> {
                showPermissionDenied()
                return
            }
        }

        if (handLink.pendingWriteKind != null) {
            emitToast("รอให้คำสั่งก่อนหน้าจบก่อน")
            return
        }

        handLink.pendingWriteKind = PendingWriteKind.DEVICE_NAME
        handLink.pendingName = cleanName
        updateHand(hand) { it.copy(isSavingName = true, isSendingCommand = false) }
        addLog("Writing name (${hand.short}): $cleanName")

        handLink.saveTimeoutJob?.cancel()
        handLink.saveTimeoutJob = viewModelScope.launch {
            delay(5000)
            if (handState(hand).isSavingName) {
                handLink.pendingWriteKind = null
                handLink.pendingName = null
                updateHand(hand) { it.copy(isSavingName = false) }
                emitToast("ยังไม่ได้รับการยืนยันจาก ESP32")
                addLog("Name write timed out (${hand.short})")
            }
        }

        val payload = "NAME:$cleanName".toByteArray(StandardCharsets.UTF_8)
        val started = writeCharacteristicPayload(hand, payload)
        if (!started) {
            handLink.saveTimeoutJob?.cancel()
            handLink.pendingWriteKind = null
            handLink.pendingName = null
            updateHand(hand) { it.copy(isSavingName = false) }
            emitToast("เริ่มส่งชื่อไม่สำเร็จ")
        }
    }

    fun writeGloveCommand(hand: Hand, command: String) {
        val cleanCommand = command.trim()
        val handLink = link(hand)
        when {
            cleanCommand.isEmpty() -> {
                emitToast("กรุณาใส่คำสั่ง")
                return
            }
            cleanCommand.length > MaxCommandChars -> {
                emitToast("คำสั่งยาวเกิน $MaxCommandChars ตัวอักษร")
                return
            }
            !handState(hand).isConnected -> {
                emitToast("ยังไม่ได้เชื่อมต่อ ${hand.label}")
                return
            }
            !hasConnectPermission() -> {
                showPermissionDenied()
                return
            }
            handLink.pendingWriteKind != null -> {
                emitToast("รอให้คำสั่งก่อนหน้าจบก่อน")
                return
            }
        }

        handLink.pendingWriteKind = PendingWriteKind.COMMAND
        updateHand(hand) { it.copy(isSendingCommand = true) }
        addLog("Writing command (${hand.short}): $cleanCommand")

        handLink.commandTimeoutJob?.cancel()
        handLink.commandTimeoutJob = viewModelScope.launch {
            delay(5000)
            if (handState(hand).isSendingCommand) {
                handLink.pendingWriteKind = null
                updateHand(hand) { it.copy(isSendingCommand = false) }
                emitToast("ส่งคำสั่งไม่สำเร็จ")
                addLog("Command write timed out (${hand.short})")
            }
        }

        val started = writeCharacteristicPayload(hand, cleanCommand.toByteArray(StandardCharsets.UTF_8))
        if (!started) {
            handLink.commandTimeoutJob?.cancel()
            handLink.pendingWriteKind = null
            updateHand(hand) { it.copy(isSendingCommand = false) }
            emitToast("เริ่มส่งคำสั่งไม่สำเร็จ")
        }
    }

    fun selectOledPage(hand: Hand, page: OledDisplayPage) {
        val state = handState(hand)
        if (state.isConnected && !state.isSavingName && !state.isSendingCommand) {
            updateHand(hand) { it.copy(selectedOledPage = page) }
        }
        writeGloveCommand(hand, page.command)
    }

    /** Sends the same command to every connected glove. */
    fun broadcastCommand(command: String) {
        _uiState.value.connectedHands.forEach { writeGloveCommand(it.hand, command) }
    }

    // ---------- Recording (both hands at once) ----------

    fun startRecording(label: String) {
        val cleanLabel = label.trim()
        if (cleanLabel.isEmpty()) {
            emitToast("กรุณาใส่ชื่อท่า")
            return
        }
        if (!_uiState.value.anyConnected) {
            emitToast("ยังไม่ได้เชื่อมต่อถุงมือ")
            return
        }
        cancelRecordingInternal()
        recordingLabel = cleanLabel
        recordingBuffer.clear()
        _uiState.update {
            it.copy(
                recordingState = RecordingState.COUNTDOWN,
                recordingCountdown = RecordingCountdownSec,
                recordingSampleCount = 0
            )
        }
        countdownJob = viewModelScope.launch {
            for (i in RecordingCountdownSec downTo 1) {
                _uiState.update { it.copy(recordingCountdown = i) }
                delay(1000)
            }
            recordingStartMs = SystemClock.elapsedRealtime()
            _uiState.update { it.copy(recordingState = RecordingState.RECORDING, recordingCountdown = 0) }
            autoStopJob = launch {
                delay(RecordingDurationMs)
                finishRecording()
            }
        }
    }

    fun cancelRecording() {
        cancelRecordingInternal()
    }

    // ---------- Live recognition ----------

    fun toggleRecognition() {
        val enabling = !_uiState.value.recognitionEnabled
        if (enabling && recognizer?.isEmpty != false) {
            emitToast("ยังไม่มีข้อมูลท่าให้รู้จำ — บันทึกท่าก่อน")
            return
        }
        recognitionStates.clear()
        _uiState.update {
            it.copy(
                recognitionEnabled = enabling,
                left = it.left.copy(recognizedWord = null, recognitionConfidence = 0f),
                right = it.right.copy(recognizedWord = null, recognitionConfidence = 0f)
            )
        }
    }

    /**
     * Live recognition state machine, run per packet:
     *  - **Still** → average a short flex window and ask the static matcher.
     *  - **Moving** (gyro magnitude crosses the start threshold) → capture a
     *    feature trajectory until the hand settles, then ask the dynamic (DTW)
     *    matcher and emit the recognized sign.
     */
    private fun runRecognition(hand: Hand, text: String) {
        val activeRecognizer = recognizer ?: return
        val live = parseLiveGloveData(text) ?: return
        val state = recognitionStates.getOrPut(hand) { HandRecognitionState() }
        val motion = GestureRecognizer.gyroMagnitude(live.imu)

        state.flexWindow.addLast(live.flex)
        while (state.flexWindow.size > RECOGNIZE_WINDOW) state.flexWindow.removeFirst()

        // Only attempt moving-sign capture when we actually have IMU data and at
        // least one dynamic template to compare against.
        val canCapture = live.imu != null && activeRecognizer.hasDynamic

        if (state.capturing) {
            state.gestureBuffer.add(activeRecognizer.feature(live.flex, live.imu))
            state.stillFrames = if (motion < GestureRecognizer.MotionStopCounts) {
                state.stillFrames + 1
            } else {
                0
            }
            val settled = state.stillFrames >= MOTION_STOP_FRAMES
            val overflow = state.gestureBuffer.size >= MAX_GESTURE_FRAMES
            if (settled || overflow) {
                finishGestureCapture(hand, state)
            }
            return
        }

        if (canCapture && motion >= GestureRecognizer.MotionStartCounts) {
            state.capturing = true
            state.stillFrames = 0
            state.gestureBuffer.clear()
            state.gestureBuffer.add(activeRecognizer.feature(live.flex, live.imu))
            return
        }

        // Still hand → static pose matching on the smoothed flex window.
        if (!activeRecognizer.hasStatic) return
        val averaged = (0 until FlexChannels).map { channel ->
            state.flexWindow.sumOf { it.getOrElse(channel) { 0 } } / state.flexWindow.size
        }
        applyRecognition(hand, activeRecognizer.classifyStatic(averaged, hand))
    }

    private fun finishGestureCapture(hand: Hand, state: HandRecognitionState) {
        val activeRecognizer = recognizer
        val captured = state.gestureBuffer.toList()
        state.capturing = false
        state.stillFrames = 0
        state.gestureBuffer.clear()
        if (activeRecognizer == null || captured.size < MIN_GESTURE_FRAMES) return
        applyRecognition(hand, activeRecognizer.classifyDynamic(captured, hand))
    }

    private fun applyRecognition(hand: Hand, result: RecognitionResult?) {
        updateHand(hand) {
            it.copy(
                // Sticky word: hold the last confident sign until a new one is sure.
                recognizedWord = if (result?.isConfident == true) result.word else it.recognizedWord,
                recognitionConfidence = result?.confidence ?: it.recognitionConfidence
            )
        }
    }

    fun refreshDataset() {
        loadDataset(preferredSessionId = _uiState.value.selectedDatasetSessionId)
    }

    fun selectDatasetSession(sessionId: String) {
        datasetPlaybackJob?.cancel()
        _uiState.update {
            it.copy(
                selectedDatasetSessionId = sessionId,
                datasetPlaybackSampleIndex = 0,
                datasetPlaybackPlaying = false
            )
        }
    }

    fun playDatasetSession() {
        val state = _uiState.value
        val session = state.datasetSessions.firstOrNull { it.id == state.selectedDatasetSessionId }
        if (session == null || session.samples.isEmpty()) {
            emitToast("ยังไม่มี session สำหรับ playback")
            return
        }

        datasetPlaybackJob?.cancel()
        _uiState.update { it.copy(datasetPlaybackPlaying = true) }
        datasetPlaybackJob = viewModelScope.launch {
            while (true) {
                val currentState = _uiState.value
                val currentSession = currentState.datasetSessions
                    .firstOrNull { it.id == currentState.selectedDatasetSessionId }
                    ?: break
                if (currentSession.samples.isEmpty()) break

                val currentIndex = currentState.datasetPlaybackSampleIndex
                    .coerceIn(0, currentSession.samples.lastIndex)
                if (currentIndex >= currentSession.samples.lastIndex) {
                    _uiState.update {
                        it.copy(
                            datasetPlaybackSampleIndex = currentSession.samples.lastIndex,
                            datasetPlaybackPlaying = false
                        )
                    }
                    break
                }

                val currentSample = currentSession.samples[currentIndex]
                val nextSample = currentSession.samples[currentIndex + 1]
                val waitMs = (nextSample.timestampMs - currentSample.timestampMs)
                    .coerceIn(40L, 250L)
                delay(waitMs)
                _uiState.update {
                    if (it.selectedDatasetSessionId == currentSession.id) {
                        it.copy(datasetPlaybackSampleIndex = currentIndex + 1)
                    } else {
                        it
                    }
                }
            }
            _uiState.update { it.copy(datasetPlaybackPlaying = false) }
        }
    }

    fun pauseDatasetPlayback() {
        datasetPlaybackJob?.cancel()
        datasetPlaybackJob = null
        _uiState.update { it.copy(datasetPlaybackPlaying = false) }
    }

    fun resetDatasetPlayback() {
        datasetPlaybackJob?.cancel()
        datasetPlaybackJob = null
        _uiState.update {
            it.copy(
                datasetPlaybackSampleIndex = 0,
                datasetPlaybackPlaying = false
            )
        }
    }

    private fun cancelRecordingInternal() {
        countdownJob?.cancel()
        autoStopJob?.cancel()
        countdownJob = null
        autoStopJob = null
        recordingBuffer.clear()
        _uiState.update {
            it.copy(
                recordingState = RecordingState.IDLE,
                recordingCountdown = 0,
                recordingSampleCount = 0
            )
        }
    }

    private fun finishRecording() {
        autoStopJob?.cancel()
        autoStopJob = null
        val samples = recordingBuffer.toList()
        val label = recordingLabel
        recordingBuffer.clear()
        _uiState.update {
            it.copy(
                recordingState = RecordingState.IDLE,
                recordingCountdown = 0,
                recordingSampleCount = 0
            )
        }
        if (samples.isEmpty()) {
            emitToast("ไม่มีข้อมูลที่บันทึก — ตรวจว่าถุงมือกำลัง stream อยู่")
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { saveCsv(label, samples) }
            if (result != null) {
                _uiState.update { it.copy(lastSavedPath = result.path) }
                val hands = samples.map { it.hand }.distinct().joinToString("+") { it.short }
                emitToast("บันทึก $label แล้ว: ${samples.size} ตัวอย่าง ($hands)")
                addLog("Saved ${samples.size} samples for '$label' -> ${result.path}")
                loadDataset(preferredSessionId = datasetSessionId(label, result.session))
            } else {
                emitToast("บันทึกไฟล์ไม่สำเร็จ")
                addLog("Failed to save recording for '$label'")
            }
        }
    }

    private fun saveCsv(label: String, samples: List<FlexSample>): CsvSaveResult? {
        return runCatching {
            val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: appContext.filesDir
            dir.mkdirs()
            val file = File(dir, "gestures.csv")
            val isNew = !file.exists()
            val session = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            FileWriter(file, true).use { writer ->
                if (isNew) {
                    writer.appendLine("label,session,hand,t_ms,f1,f2,f3,f4,f5,ax,ay,az,gx,gy,gz")
                }
                for (s in samples) {
                    writer.appendLine(
                        listOf(
                            label,
                            session,
                            s.hand.short,
                            s.timestampMs.toString(),
                            s.f1.toString(),
                            s.f2.toString(),
                            s.f3.toString(),
                            s.f4.toString(),
                            s.f5.toString(),
                            s.ax.toString(),
                            s.ay.toString(),
                            s.az.toString(),
                            s.gx.toString(),
                            s.gy.toString(),
                            s.gz.toString()
                        ).joinToString(",") { csvEscape(it) }
                    )
                }
            }
            CsvSaveResult(path = file.absolutePath, session = session)
        }.getOrElse { error ->
            addLog("CSV write error: ${error.message}")
            null
        }
    }

    private fun loadDataset(preferredSessionId: String? = null) {
        datasetPlaybackJob?.cancel()
        datasetPlaybackJob = null
        viewModelScope.launch {
            _uiState.update { it.copy(isDatasetLoading = true, datasetPlaybackPlaying = false) }
            val result = withContext(Dispatchers.IO) { loadDatasetFromCsv() }
            val builtRecognizer = withContext(Dispatchers.IO) { GestureRecognizer(result.sessions) }
            recognizer = builtRecognizer
            recognitionStates.clear()
            _uiState.update { state ->
                val preferred = preferredSessionId
                    ?.takeIf { id -> result.sessions.any { it.id == id } }
                val current = state.selectedDatasetSessionId
                    ?.takeIf { id -> result.sessions.any { it.id == id } }
                val selectedId = preferred ?: current ?: result.sessions.firstOrNull()?.id
                val selectedSession = result.sessions.firstOrNull { it.id == selectedId }
                val nextIndex = if (selectedId == state.selectedDatasetSessionId) {
                    state.datasetPlaybackSampleIndex.coerceAtMost(
                        selectedSession?.samples?.lastIndex ?: 0
                    )
                } else {
                    0
                }
                state.copy(
                    datasetSessions = result.sessions,
                    selectedDatasetSessionId = selectedId,
                    datasetPlaybackSampleIndex = nextIndex.coerceAtLeast(0),
                    datasetPlaybackPlaying = false,
                    datasetCsvPath = result.path,
                    datasetMessage = result.message,
                    isDatasetLoading = false,
                    recognizerLabelCount = builtRecognizer.labels.size,
                    // Keep recognition on only if there is still something to recognize.
                    recognitionEnabled = state.recognitionEnabled && !builtRecognizer.isEmpty
                )
            }
            addLog("Dataset loaded: ${result.sessions.size} sessions")
        }
    }

    private fun loadDatasetFromCsv(): DatasetLoadResult {
        val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: appContext.filesDir
        val file = File(dir, "gestures.csv")
        val path = file.absolutePath
        if (!file.exists()) {
            return DatasetLoadResult(
                path = path,
                sessions = emptyList(),
                message = "ยังไม่มี gestures.csv"
            )
        }

        val groupedSamples = linkedMapOf<String, MutableList<FlexSample>>()
        val labels = linkedMapOf<String, String>()
        val sessionNames = linkedMapOf<String, String>()
        var skippedRows = 0

        file.useLines { lines ->
            val iterator = lines.iterator()
            if (!iterator.hasNext()) return@useLines
            // Map column names from the header so both the legacy layout (no
            // `hand` column) and the new dual-hand layout load correctly.
            val header = parseCsvLine(iterator.next()).map { it.trim().lowercase(Locale.US) }
            fun col(name: String) = header.indexOf(name)
            val iLabel = col("label").takeIf { it >= 0 } ?: 0
            val iSession = col("session").takeIf { it >= 0 } ?: 1
            val iHand = col("hand")
            val iTime = col("t_ms")
            val iF1 = col("f1")

            iterator.forEach { line ->
                if (line.isBlank()) return@forEach
                val columns = parseCsvLine(line)

                fun intAt(index: Int): Int? =
                    columns.getOrNull(index)?.trim()?.toIntOrNull()

                // Resolve flex start: explicit f1 header, else position after t_ms.
                val timeIndex = if (iTime >= 0) iTime else 2
                val flexStart = when {
                    iF1 >= 0 -> iF1
                    else -> timeIndex + 1
                }

                val label = columns.getOrNull(iLabel)?.trim().orEmpty()
                val session = columns.getOrNull(iSession)?.trim().orEmpty()
                val timestampMs = columns.getOrNull(timeIndex)?.trim()?.toLongOrNull()
                val f1 = intAt(flexStart)
                val f2 = intAt(flexStart + 1)
                val f3 = intAt(flexStart + 2)
                val f4 = intAt(flexStart + 3)
                val f5 = intAt(flexStart + 4)
                if (timestampMs == null || f1 == null || f2 == null ||
                    f3 == null || f4 == null || f5 == null
                ) {
                    skippedRows++
                    return@forEach
                }

                val hand = if (iHand >= 0) {
                    Hand.fromShort(columns.getOrNull(iHand)?.trim()) ?: Hand.LEFT
                } else {
                    Hand.LEFT
                }

                fun imu(offset: Int): Int = intAt(flexStart + 5 + offset) ?: 0

                val sample = FlexSample(
                    timestampMs = timestampMs,
                    f1 = f1, f2 = f2, f3 = f3, f4 = f4, f5 = f5,
                    ax = imu(0), ay = imu(1), az = imu(2),
                    gx = imu(3), gy = imu(4), gz = imu(5),
                    hand = hand
                )

                val id = datasetSessionId(label, session)
                labels[id] = label
                sessionNames[id] = session
                groupedSamples.getOrPut(id) { mutableListOf() }.add(sample)
            }
        }

        val sessions = groupedSamples.mapNotNull { (id, samples) ->
            if (samples.isEmpty()) return@mapNotNull null
            GestureSession(
                id = id,
                label = labels[id].orEmpty(),
                session = sessionNames[id].orEmpty(),
                samples = samples.sortedBy { it.timestampMs }
            )
        }.sortedWith(
            compareByDescending<GestureSession> { it.session }
                .thenBy { it.label.lowercase(Locale.US) }
        )

        val message = when {
            sessions.isEmpty() -> "gestures.csv ไม่มี sample ที่อ่านได้"
            skippedRows > 0 -> "ข้าม $skippedRows แถวที่อ่านไม่ได้"
            else -> null
        }
        return DatasetLoadResult(path = path, sessions = sessions, message = message)
    }

    private fun datasetSessionId(label: String, session: String): String {
        return "$session$label"
    }

    private fun csvEscape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun parseCsvLine(line: String): List<String> {
        val cells = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    cell.append('"')
                    index++
                }
                char == '"' -> {
                    inQuotes = !inQuotes
                }
                char == ',' && !inQuotes -> {
                    cells.add(cell.toString())
                    cell.clear()
                }
                else -> {
                    cell.append(char)
                }
            }
            index++
        }

        cells.add(cell.toString())
        return cells
    }

    fun dismissDialog() {
        _uiState.update { it.copy(dialog = null) }
    }

    fun toggleLogs() {
        _uiState.update { it.copy(showLogs = !it.showLogs) }
    }

    fun showPermissionDenied() {
        _uiState.update {
            it.copy(
                dialog = UiDialog(
                    title = "ต้องอนุญาต Bluetooth",
                    message = "แอปต้องใช้สิทธิ์ Bluetooth เพื่อสแกนและเชื่อมต่อ ESP32. เปิด permission ของแอปแล้วลองใหม่",
                    action = DialogAction.OPEN_APP_SETTINGS
                )
            )
        }
    }

    fun showBluetoothOff() {
        _uiState.update {
            it.copy(
                dialog = UiDialog(
                    title = "Bluetooth ปิดอยู่",
                    message = "เปิด Bluetooth แล้วกด Scan อีกครั้ง",
                    action = DialogAction.OPEN_BLUETOOTH_SETTINGS
                )
            )
        }
    }

    fun showBleUnsupported() {
        _uiState.update {
            it.copy(
                dialog = UiDialog(
                    title = "เครื่องนี้ไม่รองรับ BLE",
                    message = "ต้องใช้อุปกรณ์ Android ที่รองรับ Bluetooth Low Energy"
                )
            )
        }
    }

    private fun locationDialog(): UiDialog = UiDialog(
        title = "เปิด Location เพื่อสแกน",
        message = "Android กำหนดให้เปิด Location Services ก่อน จึงจะสแกนหาอุปกรณ์ " +
            "Bluetooth ได้ (โดยเฉพาะมือถือ realme / OPPO / Xiaomi). แอปไม่ได้ใช้ตำแหน่ง " +
            "ของคุณ — เปิด Location แล้วกดสแกนอีกครั้ง",
        action = DialogAction.OPEN_LOCATION_SETTINGS
    )

    private fun showLocationOff() {
        _uiState.update {
            it.copy(
                isScanning = false,
                scanPhase = ConnectionPhase.IDLE,
                dialog = locationDialog()
            )
        }
    }

    private fun isLocationServiceEnabled(): Boolean {
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun startConnectedTickers(hand: Hand) {
        stopConnectedTickers(hand)
        val handLink = link(hand)
        handLink.uptimeJob = viewModelScope.launch {
            while (handState(hand).status == BleStatus.CONNECTED) {
                val seconds = (SystemClock.elapsedRealtime() - handLink.connectedAtMillis) / 1000
                updateHand(hand) { it.copy(connectedUptimeSeconds = seconds) }
                delay(1000)
            }
        }
        handLink.rssiJob = viewModelScope.launch {
            while (handState(hand).status == BleStatus.CONNECTED) {
                if (hasConnectPermission()) {
                    runCatching {
                        @SuppressLint("MissingPermission")
                        handLink.gatt?.readRemoteRssi()
                    }
                }
                delay(2000)
            }
        }
    }

    private fun startConnectionTimeout(hand: Hand, address: String) {
        val handLink = link(hand)
        handLink.connectionTimeoutJob?.cancel()
        handLink.connectionTimeoutJob = viewModelScope.launch {
            delay(12000)
            val state = handState(hand)
            if (state.status == BleStatus.CONNECTING && state.connectedAddress == address) {
                failConnection(hand, "เชื่อมต่อ ${hand.label} ไม่สำเร็จภายใน 12 วินาที")
            }
        }
    }

    private fun stopConnectedTickers(hand: Hand) {
        val handLink = link(hand)
        handLink.rssiJob?.cancel()
        handLink.uptimeJob?.cancel()
        handLink.connectionTimeoutJob?.cancel()
        handLink.saveTimeoutJob?.cancel()
        handLink.commandTimeoutJob?.cancel()
        handLink.nameConfirmReadJob?.cancel()
        handLink.rssiJob = null
        handLink.uptimeJob = null
        handLink.connectionTimeoutJob = null
        handLink.saveTimeoutJob = null
        handLink.commandTimeoutJob = null
        handLink.nameConfirmReadJob = null
        handLink.pendingWriteKind = null
        handLink.pendingName = null
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        return runCatching {
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
                addLog("Characteristic does not advertise notify; live data may be unavailable")
                return false
            }
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(ClientCharacteristicConfigUuid)
            if (descriptor != null) {
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    ) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                if (!started) {
                    addLog("Notification descriptor write did not start")
                }
                started
            } else {
                addLog("CCCD descriptor missing; notifications may not work")
                false
            }
        }.onFailure {
            addLog("Enable notification failed: ${it.message}")
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun readNameCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            addLog("Characteristic does not advertise read")
            return
        }
        val started = runCatching {
            gatt.readCharacteristic(characteristic)
        }.getOrElse { error ->
            addLog("Read exception: ${error.message}")
            false
        }
        if (!started) {
            addLog("Name read did not start")
        }
    }

    private fun scheduleNameReadBack(
        hand: Hand,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val handLink = link(hand)
        handLink.nameConfirmReadJob?.cancel()
        handLink.nameConfirmReadJob = viewModelScope.launch {
            delay(700)
            if (handState(hand).isSavingName &&
                handLink.pendingWriteKind == PendingWriteKind.DEVICE_NAME
            ) {
                readNameCharacteristic(gatt, characteristic)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicPayload(hand: Hand, payload: ByteArray): Boolean {
        val handLink = link(hand)
        val gatt = handLink.gatt
        val characteristic = handLink.nameCharacteristic
        if (gatt == null || characteristic == null) {
            emitToast("BLE characteristic ยังไม่พร้อม")
            return false
        }

        val supportsWrite =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        if (!supportsWrite) {
            addLog("Characteristic does not advertise write")
            return false
        }

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val writeType = if (
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                ) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                gatt.writeCharacteristic(characteristic, payload, writeType) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = payload
                characteristic.writeType = if (
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                ) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        }.getOrElse { error ->
            addLog("Write exception: ${error.message}")
            false
        }
    }

    private fun handleCharacteristicPayload(hand: Hand, payload: ByteArray?, fromNotification: Boolean) {
        if (payload == null || payload.isEmpty()) return
        val text = payload.toString(StandardCharsets.UTF_8).trim()
        if (text.isEmpty()) return
        val handLink = link(hand)

        when {
            fromNotification &&
                handLink.pendingWriteKind == PendingWriteKind.DEVICE_NAME &&
                text.startsWith("OK:") -> {
                val newName = text.removePrefix("OK:").trim()
                confirmDeviceName(hand, newName.ifEmpty { handLink.pendingName.orEmpty() })
            }
            fromNotification &&
                handLink.pendingWriteKind == PendingWriteKind.DEVICE_NAME &&
                text.startsWith("ERR:") -> {
                val reason = text.removePrefix("ERR:").trim()
                handLink.saveTimeoutJob?.cancel()
                handLink.nameConfirmReadJob?.cancel()
                handLink.pendingWriteKind = null
                handLink.pendingName = null
                updateHand(hand) { it.copy(isSavingName = false) }
                emitToast("บันทึกไม่สำเร็จ: $reason")
                addLog("ESP32 rejected name (${hand.short}): $reason")
            }
            fromNotification && text.startsWith("GEST:", ignoreCase = true) -> {
                val word = text.substringAfter(":").trim()
                if (word.isNotEmpty()) speak(word)
            }
            fromNotification &&
                text.startsWith("OK:OLED:", ignoreCase = true) -> {
                val page = parseOledPageCode(text.substringAfterLast(":"))
                if (page != null) {
                    updateHand(hand) { it.copy(selectedOledPage = page) }
                    addLog("OLED page confirmed (${hand.short}): ${page.command}")
                } else {
                    addLog("Unknown OLED page ack: $text")
                }
            }
            fromNotification &&
                text.startsWith("ERR:BAD_OLED_PAGE", ignoreCase = true) -> {
                emitToast("เลือกหน้า OLED ไม่สำเร็จ")
                addLog("ESP32 rejected OLED page (${hand.short})")
            }
            text.startsWith("NAME:", ignoreCase = true) -> {
                val name = text.substringAfter(":").trim()
                applyDeviceNameRead(hand, name)
            }
            fromNotification || looksLikeGlovePayload(text) -> {
                handleGlovePayload(hand, text)
            }
            else -> {
                applyDeviceNameRead(hand, text)
            }
        }
    }

    private fun parseOledPageCode(code: String): OledDisplayPage? {
        return when (code.trim().uppercase(Locale.US)) {
            "DASH", "DASHBOARD" -> OledDisplayPage.DASHBOARD
            "TELEM", "TELEMETRY", "FLEX" -> OledDisplayPage.TELEMETRY
            "SYS", "SYSTEM", "STATUS" -> OledDisplayPage.SYSTEM
            "HAND", "FINGER" -> OledDisplayPage.HAND
            "WAVE", "WAVEFORM", "GRAPH" -> OledDisplayPage.WAVE
            else -> null
        }
    }

    private fun applyDeviceNameRead(hand: Hand, name: String) {
        if (name.isEmpty()) return
        val handLink = link(hand)
        val expectedName = handLink.pendingName
        if (
            handLink.pendingWriteKind == PendingWriteKind.DEVICE_NAME &&
            expectedName != null &&
            name == expectedName
        ) {
            confirmDeviceName(hand, name)
            return
        }
        updateHand(hand) { it.copy(connectedName = name.take(MaxDeviceNameChars)) }
        addLog("Name read (${hand.short}): $name")
    }

    private fun confirmDeviceName(hand: Hand, name: String) {
        val handLink = link(hand)
        handLink.saveTimeoutJob?.cancel()
        handLink.nameConfirmReadJob?.cancel()
        handLink.pendingWriteKind = null
        handLink.pendingName = null
        updateHand(hand) {
            val nextName = name.ifEmpty { it.connectedName.orEmpty() }.take(MaxDeviceNameChars)
            val resolvedName = nextName.ifEmpty { it.connectedName.orEmpty() }
            it.copy(
                connectedName = if (resolvedName.isEmpty()) null else resolvedName,
                isSavingName = false
            )
        }
        emitToast("บันทึกชื่อแล้ว (${hand.short})")
        addLog("ESP32 confirmed name (${hand.short}): $name")
    }

    private fun handleGlovePayload(hand: Hand, text: String) {
        val preview = text.take(MaxPayloadPreviewChars)
        var nextCount = 0
        updateHand(hand) { state ->
            nextCount = state.glovePacketCount + 1
            state.copy(
                latestPayload = preview,
                latestPayloadAtMillis = SystemClock.elapsedRealtime(),
                glovePacketCount = nextCount
            )
        }
        if (nextCount == 1 || nextCount % 20 == 0) {
            addLog("Glove packets (${hand.short}): $nextCount")
        }

        if (_uiState.value.recordingState == RecordingState.RECORDING) {
            parseFlexSample(hand, text)?.let { sample ->
                recordingBuffer.add(sample)
                _uiState.update { it.copy(recordingSampleCount = recordingBuffer.size) }
            }
        }

        if (_uiState.value.recognitionEnabled) {
            runRecognition(hand, text)
        }
    }

    private fun parseFlexSample(hand: Hand, text: String): FlexSample? {
        val body = if (text.startsWith("DATA:", ignoreCase = true)) text.substringAfter(":") else text
        val map = body.split(",").mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq < 0) return@mapNotNull null
            part.substring(0, eq).trim().lowercase(Locale.US) to part.substring(eq + 1).trim().toIntOrNull()
        }.toMap()
        val f1 = map["f1"] ?: return null
        val f2 = map["f2"] ?: return null
        val f3 = map["f3"] ?: return null
        val f4 = map["f4"] ?: return null
        val f5 = map["f5"] ?: return null
        val t = SystemClock.elapsedRealtime() - recordingStartMs
        return FlexSample(
            timestampMs = t,
            f1 = f1, f2 = f2, f3 = f3, f4 = f4, f5 = f5,
            ax = map["ax"] ?: 0, ay = map["ay"] ?: 0, az = map["az"] ?: 0,
            gx = map["gx"] ?: 0, gy = map["gy"] ?: 0, gz = map["gz"] ?: 0,
            hand = hand
        )
    }

    private fun looksLikeGlovePayload(text: String): Boolean {
        val upper = text.uppercase(Locale.US)
        if (
            upper.startsWith("DATA:") ||
            upper.startsWith("SENSOR:") ||
            upper.startsWith("FLEX:") ||
            upper.startsWith("IMU:")
        ) {
            return true
        }
        val hasPayloadSeparator = text.any { it == ',' || it == ';' || it == '=' || it == '|' }
        return hasPayloadSeparator && text.any { it.isDigit() }
    }

    private fun failConnection(hand: Hand, message: String) {
        addLog("${hand.short}: $message")
        emitToast(message)
        disconnect(hand)
        _uiState.update {
            it.copy(
                dialog = UiDialog(
                    title = "เชื่อมต่อไม่ได้",
                    message = "$message\n\nตรวจว่า ESP32 ใช้ Service UUID และ Characteristic UUID ตรงกับแอป"
                )
            )
        }
    }

    private fun handleDisconnected(hand: Hand, lost: Boolean) {
        val handLink = link(hand)
        handLink.connectingAddress = null
        handLink.connectAttempt = 0
        stopConnectedTickers(hand)
        closeLink(hand)
        updateHand(hand) { HandConnection(hand) }
        if (lost) {
            _uiState.update {
                it.copy(
                    dialog = UiDialog(
                        title = "การเชื่อมต่อหลุด (${hand.label})",
                        message = "ESP32 ตัดการเชื่อมต่อกลางคัน. ตรวจระยะห่าง แบตเตอรี่ และลองเชื่อมต่อใหม่"
                    )
                )
            }
        }
    }

    private fun closeLink(hand: Hand) {
        val handLink = link(hand)
        handLink.nameCharacteristic = null
        if (hasConnectPermission()) {
            runCatching {
                @SuppressLint("MissingPermission")
                handLink.gatt?.close()
            }
        }
        handLink.gatt = null
    }

    private fun safeDeviceName(device: BluetoothDevice): String? {
        if (!hasConnectPermission()) return null
        return runCatching {
            @SuppressLint("MissingPermission")
            device.name
        }.getOrNull()
    }

    private fun isLikelyGloveName(name: String): Boolean {
        return GloveNamePrefixes.any { prefix ->
            name.startsWith(prefix, ignoreCase = true)
        } || name.contains("glove", ignoreCase = true)
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun emitToast(message: String) {
        viewModelScope.launch {
            _events.emit(UiEvent.Toast(message))
        }
    }

    private fun addLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _uiState.update { state ->
            val nextLogs = (state.logs + "$timestamp $message").takeLast(80)
            state.copy(logs = nextLogs)
        }
    }

    override fun onCleared() {
        datasetPlaybackJob?.cancel()
        stopScan()
        disconnectAll()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onCleared()
    }

    private companion object {
        const val MAX_CONNECT_ATTEMPTS = 3

        /** Number of recent live readings averaged together before static matching. */
        const val RECOGNIZE_WINDOW = 6

        /** Consecutive still frames that end a moving-gesture capture. */
        const val MOTION_STOP_FRAMES = 4

        /** Shortest capture (frames) worth classifying as a moving sign. */
        const val MIN_GESTURE_FRAMES = 8

        /** Safety cap so a continuously moving hand can't capture forever. */
        const val MAX_GESTURE_FRAMES = 120
    }
}
