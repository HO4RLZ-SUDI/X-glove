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
    private var bluetoothGatt: BluetoothGatt? = null
    private var nameCharacteristic: BluetoothGattCharacteristic? = null
    private var scanActive = false
    private var connectedAtMillis = 0L
    private var scanWatchdogJob: Job? = null
    private var connectingAddress: String? = null
    private var connectAttempt = 0
    private var rssiJob: Job? = null
    private var uptimeJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var saveTimeoutJob: Job? = null
    private var commandTimeoutJob: Job? = null
    private var nameConfirmReadJob: Job? = null
    private var pendingWriteKind: PendingWriteKind? = null
    private var pendingName: String? = null

    private val recordingBuffer = mutableListOf<FlexSample>()
    private var recordingLabel = ""
    private var recordingStartMs = 0L
    private var countdownJob: Job? = null
    private var autoStopJob: Job? = null
    private var datasetPlaybackJob: Job? = null

    init {
        refreshDataset()
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advertisedName = result.scanRecord?.deviceName
            val fallbackName = if (hasConnectPermission()) result.device.name else null
            val name = advertisedName ?: fallbackName ?: "Unknown ESP32"
            val hasService = result.scanRecord
                ?.serviceUuids
                ?.any { it.uuid == Esp32ServiceUuid } == true
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
                    status = BleStatus.DISCONNECTED,
                    phase = ConnectionPhase.IDLE,
                    dialog = UiDialog(
                        title = "Scan failed",
                        message = "สแกน BLE ไม่สำเร็จ ($errorCode). ลองปิด/เปิด Bluetooth แล้วสแกนใหม่"
                    )
                )
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    addLog("GATT connection error: $status")
                    // Error 133 (and friends) is a flaky, device-specific GATT failure
                    // common on Realme/Oppo/Xiaomi. Retry the direct connection a few
                    // times before giving up instead of failing on the first attempt.
                    val address = connectingAddress
                    if (
                        _uiState.value.status == BleStatus.CONNECTING &&
                        address != null &&
                        connectAttempt < MAX_CONNECT_ATTEMPTS
                    ) {
                        connectAttempt++
                        addLog("Retrying connection (attempt $connectAttempt) after error $status")
                        closeGatt()
                        viewModelScope.launch {
                            delay(450)
                            if (_uiState.value.status == BleStatus.CONNECTING) {
                                openGatt(address)
                            }
                        }
                    } else {
                        failConnection("เชื่อมต่อ BLE ไม่สำเร็จ ($status)")
                    }
                }
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    addLog("Connected, discovering services")
                    if (!hasConnectPermission()) {
                        showPermissionDenied()
                        handleDisconnected(lost = false)
                        return
                    }
                    _uiState.update { it.copy(phase = ConnectionPhase.DISCOVERING) }
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    // Match the firmware so a full flex + IMU packet fits one notification.
                    gatt.requestMtu(185)
                    val discoveryStarted = gatt.discoverServices()
                    if (!discoveryStarted) {
                        failConnection("เริ่มค้นหา service ไม่สำเร็จ")
                    }
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    addLog("Disconnected")
                    val wasActive = _uiState.value.status == BleStatus.CONNECTED ||
                        _uiState.value.status == BleStatus.CONNECTING
                    handleDisconnected(lost = wasActive)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection("ค้นหา service ไม่สำเร็จ ($status)")
                return
            }

            val service = gatt.getService(Esp32ServiceUuid)
            val characteristic = service?.getCharacteristic(Esp32NameCharacteristicUuid)
            if (service == null || characteristic == null) {
                failConnection("ไม่พบ Service/Characteristic ของ ESP32 ในอุปกรณ์นี้")
                return
            }

            nameCharacteristic = characteristic
            connectedAtMillis = SystemClock.elapsedRealtime()
            connectionTimeoutJob?.cancel()
            _uiState.update { it.copy(phase = ConnectionPhase.STARTING_STREAM) }

            val device = gatt.device
            val name = safeDeviceName(device) ?: _uiState.value.connectedName ?: "ESP32"
            _uiState.update {
                it.copy(
                    status = BleStatus.CONNECTED,
                    phase = ConnectionPhase.CONNECTED,
                    connectedName = name,
                    connectedAddress = device.address,
                    connectedUptimeSeconds = 0,
                    isSavingName = false,
                    isSendingCommand = false,
                    dialog = null
                )
            }
            addLog("GATT ready: ${device.address}")
            startConnectedTickers()
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
                addLog("Notification enabled")
            } else {
                addLog("Notification descriptor failed: $status")
            }
            val characteristic = nameCharacteristic ?: return
            readNameCharacteristic(gatt, characteristic)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            addLog("MTU changed: mtu=$mtu status=$status")
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _uiState.update { it.copy(connectedRssi = rssi) }
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
                handleCharacteristicPayload(characteristic.value, fromNotification = false)
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
                handleCharacteristicPayload(value, fromNotification = false)
            }
        }

        @Deprecated("Deprecated in Android 13, kept for older devices")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == Esp32NameCharacteristicUuid) {
                @Suppress("DEPRECATION")
                handleCharacteristicPayload(characteristic.value, fromNotification = true)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == Esp32NameCharacteristicUuid) {
                handleCharacteristicPayload(value, fromNotification = true)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != Esp32NameCharacteristicUuid) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (pendingWriteKind) {
                    PendingWriteKind.DEVICE_NAME -> {
                        addLog("Name write sent")
                        scheduleNameReadBack(gatt, characteristic)
                    }
                    PendingWriteKind.COMMAND -> {
                        commandTimeoutJob?.cancel()
                        pendingWriteKind = null
                        _uiState.update { it.copy(isSendingCommand = false) }
                        emitToast("ส่งคำสั่งแล้ว")
                        addLog("Command write sent")
                    }
                    null -> addLog("Write sent")
                }
            } else {
                saveTimeoutJob?.cancel()
                commandTimeoutJob?.cancel()
                pendingWriteKind = null
                pendingName = null
                _uiState.update {
                    it.copy(
                        isSavingName = false,
                        isSendingCommand = false
                    )
                }
                emitToast("ส่งข้อมูลไม่สำเร็จ ($status)")
                addLog("Characteristic write failed: $status")
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
            showLocationOff(blocking = true)
            return
        }

        stopScan()
        scannedDevices.clear()
        _uiState.update {
            it.copy(
                status = BleStatus.SCANNING,
                phase = ConnectionPhase.SCANNING,
                devices = emptyList(),
                connectedName = null,
                connectedAddress = null,
                connectedRssi = null,
                connectedUptimeSeconds = 0,
                latestPayload = null,
                latestPayloadAtMillis = null,
                glovePacketCount = 0,
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
            // Android 12+ AOSP doesn't require Location Services, but realme UI /
            // ColorOS, MIUI and similar still return zero scan results without it.
            // Prompt right away instead of leaving the user waiting; the scan keeps
            // running for devices that genuinely don't need it.
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

    // On Android 12+ AOSP doesn't require Location Services, but ColorOS/realme
    // UI, MIUI and similar still return nothing without it. If a scan turns up
    // empty, surface the same fix instead of leaving the user staring at an
    // endless "scanning..." with no devices.
    private fun startScanWatchdog() {
        scanWatchdogJob?.cancel()
        scanWatchdogJob = viewModelScope.launch {
            delay(8000)
            val state = _uiState.value
            if (state.status == BleStatus.SCANNING && state.devices.isEmpty()) {
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
        if (!scanActive || !hasScanPermission()) return
        runCatching {
            @SuppressLint("MissingPermission")
            adapter.bluetoothLeScanner?.stopScan(scanCallback)
        }
        scanActive = false
        if (_uiState.value.status == BleStatus.SCANNING) {
            _uiState.update {
                it.copy(
                    status = BleStatus.DISCONNECTED,
                    phase = ConnectionPhase.IDLE
                )
            }
        }
        addLog("Scan stopped")
    }

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

        stopScan()
        closeGatt()
        _uiState.update {
            it.copy(
                status = BleStatus.CONNECTING,
                phase = ConnectionPhase.CONNECTING,
                connectedName = safeDeviceName(device) ?: "ESP32",
                connectedAddress = address,
                connectedRssi = null,
                connectedUptimeSeconds = 0,
                latestPayload = null,
                latestPayloadAtMillis = null,
                glovePacketCount = 0,
                isSavingName = false,
                isSendingCommand = false,
                dialog = null
            )
        }
        addLog("Connecting to $address")
        connectingAddress = address
        connectAttempt = 0
        startConnectionTimeout(address)
        openGatt(address)
    }

    @SuppressLint("MissingPermission")
    private fun openGatt(address: String) {
        val device = scannedDevices[address] ?: bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            failConnection("ไม่พบอุปกรณ์นี้แล้ว ลองสแกนใหม่")
            return
        }
        if (!hasConnectPermission()) {
            showPermissionDenied()
            return
        }

        try {
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, gattCallback)
            }
            if (bluetoothGatt == null) {
                failConnection("เริ่มเชื่อมต่อ BLE ไม่สำเร็จ")
            }
        } catch (error: SecurityException) {
            addLog("Connect permission error: ${error.message}")
            connectionTimeoutJob?.cancel()
            showPermissionDenied()
        }
    }

    fun disconnect() {
        addLog("Disconnect requested")
        connectingAddress = null
        connectAttempt = 0
        stopConnectedTickers()
        if (hasConnectPermission()) {
            runCatching {
                @SuppressLint("MissingPermission")
                bluetoothGatt?.disconnect()
            }
        }
        closeGatt()
        _uiState.update {
            it.copy(
                status = BleStatus.DISCONNECTED,
                phase = ConnectionPhase.IDLE,
                connectedName = null,
                connectedAddress = null,
                connectedRssi = null,
                connectedUptimeSeconds = 0,
                latestPayload = null,
                latestPayloadAtMillis = null,
                glovePacketCount = 0,
                isSavingName = false,
                isSendingCommand = false
            )
        }
    }

    fun writeDeviceName(name: String) {
        val cleanName = name.trim()
        when {
            cleanName.isEmpty() -> {
                emitToast("กรุณาใส่ชื่ออุปกรณ์")
                return
            }
            cleanName.length > MaxDeviceNameChars -> {
                emitToast("ชื่อยาวเกิน $MaxDeviceNameChars ตัวอักษร")
                return
            }
            _uiState.value.status != BleStatus.CONNECTED -> {
                emitToast("ยังไม่ได้เชื่อมต่อ ESP32")
                return
            }
            !hasConnectPermission() -> {
                showPermissionDenied()
                return
            }
        }

        if (pendingWriteKind != null) {
            emitToast("รอให้คำสั่งก่อนหน้าจบก่อน")
            return
        }

        pendingWriteKind = PendingWriteKind.DEVICE_NAME
        pendingName = cleanName
        _uiState.update {
            it.copy(
                isSavingName = true,
                isSendingCommand = false
            )
        }
        addLog("Writing name: $cleanName")

        saveTimeoutJob?.cancel()
        saveTimeoutJob = viewModelScope.launch {
            delay(5000)
            if (_uiState.value.isSavingName) {
                pendingWriteKind = null
                pendingName = null
                _uiState.update { it.copy(isSavingName = false) }
                emitToast("ยังไม่ได้รับการยืนยันจาก ESP32")
                addLog("Name write timed out")
            }
        }

        // Firmware only accepts a rename via the explicit "NAME:" command now,
        // so unrecognized writes can no longer silently rename the glove.
        val payload = "NAME:$cleanName".toByteArray(StandardCharsets.UTF_8)
        val started = writeCharacteristicPayload(payload)
        if (!started) {
            saveTimeoutJob?.cancel()
            pendingWriteKind = null
            pendingName = null
            _uiState.update { it.copy(isSavingName = false) }
            emitToast("เริ่มส่งชื่อไม่สำเร็จ")
        }
    }

    fun writeGloveCommand(command: String) {
        val cleanCommand = command.trim()
        when {
            cleanCommand.isEmpty() -> {
                emitToast("กรุณาใส่คำสั่ง")
                return
            }
            cleanCommand.length > MaxCommandChars -> {
                emitToast("คำสั่งยาวเกิน $MaxCommandChars ตัวอักษร")
                return
            }
            _uiState.value.status != BleStatus.CONNECTED -> {
                emitToast("ยังไม่ได้เชื่อมต่อถุงมือ")
                return
            }
            !hasConnectPermission() -> {
                showPermissionDenied()
                return
            }
            pendingWriteKind != null -> {
                emitToast("รอให้คำสั่งก่อนหน้าจบก่อน")
                return
            }
        }

        pendingWriteKind = PendingWriteKind.COMMAND
        _uiState.update { it.copy(isSendingCommand = true) }
        addLog("Writing command: $cleanCommand")

        commandTimeoutJob?.cancel()
        commandTimeoutJob = viewModelScope.launch {
            delay(5000)
            if (_uiState.value.isSendingCommand) {
                pendingWriteKind = null
                _uiState.update { it.copy(isSendingCommand = false) }
                emitToast("ส่งคำสั่งไม่สำเร็จ")
                addLog("Command write timed out")
            }
        }

        val started = writeCharacteristicPayload(cleanCommand.toByteArray(StandardCharsets.UTF_8))
        if (!started) {
            commandTimeoutJob?.cancel()
            pendingWriteKind = null
            _uiState.update { it.copy(isSendingCommand = false) }
            emitToast("เริ่มส่งคำสั่งไม่สำเร็จ")
        }
    }

    fun selectOledPage(page: OledDisplayPage) {
        val state = _uiState.value
        if (
            state.status == BleStatus.CONNECTED &&
            !state.isSavingName &&
            !state.isSendingCommand
        ) {
            _uiState.update { it.copy(selectedOledPage = page) }
        }
        writeGloveCommand(page.command)
    }

    fun startRecording(label: String) {
        val cleanLabel = label.trim()
        if (cleanLabel.isEmpty()) {
            emitToast("กรุณาใส่ชื่อท่า")
            return
        }
        if (_uiState.value.status != BleStatus.CONNECTED) {
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
                emitToast("บันทึก $label แล้ว: ${samples.size} ตัวอย่าง")
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
                    writer.appendLine("label,session,t_ms,f1,f2,f3,f4,f5,ax,ay,az,gx,gy,gz")
                }
                for (s in samples) {
                    writer.appendLine(
                        listOf(
                            label,
                            session,
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
                    isDatasetLoading = false
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
            lines.drop(1).forEach { line ->
                if (line.isBlank()) return@forEach
                val columns = parseCsvLine(line)
                if (columns.size < 8) {
                    skippedRows++
                    return@forEach
                }

                val label = columns[0].trim()
                val session = columns[1].trim()
                val timestampMs = columns[2].trim().toLongOrNull()
                val f1 = columns[3].trim().toIntOrNull()
                val f2 = columns[4].trim().toIntOrNull()
                val f3 = columns[5].trim().toIntOrNull()
                val f4 = columns[6].trim().toIntOrNull()
                val f5 = columns[7].trim().toIntOrNull()
                if (timestampMs == null || f1 == null || f2 == null ||
                    f3 == null || f4 == null || f5 == null
                ) {
                    skippedRows++
                    return@forEach
                }

                // IMU columns (ax..gz) are optional so 8-column files written by
                // older app versions still load, with the IMU defaulting to 0.
                fun imuColumn(index: Int): Int =
                    columns.getOrNull(index)?.trim()?.toIntOrNull() ?: 0

                val sample = FlexSample(
                    timestampMs = timestampMs,
                    f1 = f1,
                    f2 = f2,
                    f3 = f3,
                    f4 = f4,
                    f5 = f5,
                    ax = imuColumn(8), ay = imuColumn(9), az = imuColumn(10),
                    gx = imuColumn(11), gy = imuColumn(12), gz = imuColumn(13)
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
        return "$session\u001F$label"
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
                status = if (it.status == BleStatus.CONNECTED) it.status else BleStatus.DISCONNECTED,
                phase = if (it.status == BleStatus.CONNECTED) it.phase else ConnectionPhase.IDLE,
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
                status = if (it.status == BleStatus.CONNECTED) it.status else BleStatus.DISCONNECTED,
                phase = if (it.status == BleStatus.CONNECTED) it.phase else ConnectionPhase.IDLE,
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
                status = BleStatus.DISCONNECTED,
                phase = ConnectionPhase.IDLE,
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

    private fun showLocationOff(blocking: Boolean) {
        _uiState.update {
            it.copy(
                status = if (blocking) BleStatus.DISCONNECTED else it.status,
                phase = if (blocking) ConnectionPhase.IDLE else it.phase,
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

    private fun startConnectedTickers() {
        stopConnectedTickers()
        uptimeJob = viewModelScope.launch {
            while (_uiState.value.status == BleStatus.CONNECTED) {
                val seconds = (SystemClock.elapsedRealtime() - connectedAtMillis) / 1000
                _uiState.update { it.copy(connectedUptimeSeconds = seconds) }
                delay(1000)
            }
        }
        rssiJob = viewModelScope.launch {
            while (_uiState.value.status == BleStatus.CONNECTED) {
                if (hasConnectPermission()) {
                    runCatching {
                        @SuppressLint("MissingPermission")
                        bluetoothGatt?.readRemoteRssi()
                    }
                }
                delay(2000)
            }
        }
    }

    private fun startConnectionTimeout(address: String) {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = viewModelScope.launch {
            delay(12000)
            val state = _uiState.value
            if (state.status == BleStatus.CONNECTING && state.connectedAddress == address) {
                failConnection("เชื่อมต่อถุงมือไม่สำเร็จภายใน 12 วินาที")
            }
        }
    }

    private fun stopConnectedTickers() {
        rssiJob?.cancel()
        uptimeJob?.cancel()
        connectionTimeoutJob?.cancel()
        saveTimeoutJob?.cancel()
        commandTimeoutJob?.cancel()
        nameConfirmReadJob?.cancel()
        rssiJob = null
        uptimeJob = null
        connectionTimeoutJob = null
        saveTimeoutJob = null
        commandTimeoutJob = null
        nameConfirmReadJob = null
        pendingWriteKind = null
        pendingName = null
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
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        nameConfirmReadJob?.cancel()
        nameConfirmReadJob = viewModelScope.launch {
            delay(700)
            if (_uiState.value.isSavingName && pendingWriteKind == PendingWriteKind.DEVICE_NAME) {
                readNameCharacteristic(gatt, characteristic)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicPayload(payload: ByteArray): Boolean {
        val gatt = bluetoothGatt
        val characteristic = nameCharacteristic
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

    private fun handleCharacteristicPayload(payload: ByteArray?, fromNotification: Boolean) {
        if (payload == null || payload.isEmpty()) return
        val text = payload.toString(StandardCharsets.UTF_8).trim()
        if (text.isEmpty()) return

        when {
            fromNotification &&
                pendingWriteKind == PendingWriteKind.DEVICE_NAME &&
                text.startsWith("OK:") -> {
                val newName = text.removePrefix("OK:").trim()
                confirmDeviceName(newName.ifEmpty { pendingName.orEmpty() })
            }
            fromNotification &&
                pendingWriteKind == PendingWriteKind.DEVICE_NAME &&
                text.startsWith("ERR:") -> {
                val reason = text.removePrefix("ERR:").trim()
                saveTimeoutJob?.cancel()
                nameConfirmReadJob?.cancel()
                pendingWriteKind = null
                pendingName = null
                _uiState.update { it.copy(isSavingName = false) }
                emitToast("บันทึกไม่สำเร็จ: $reason")
                addLog("ESP32 rejected name: $reason")
            }
            fromNotification &&
                text.startsWith("OK:OLED:", ignoreCase = true) -> {
                val page = parseOledPageCode(text.substringAfterLast(":"))
                if (page != null) {
                    _uiState.update { it.copy(selectedOledPage = page) }
                    addLog("OLED page confirmed: ${page.command}")
                } else {
                    addLog("Unknown OLED page ack: $text")
                }
            }
            fromNotification &&
                text.startsWith("ERR:BAD_OLED_PAGE", ignoreCase = true) -> {
                emitToast("เลือกหน้า OLED ไม่สำเร็จ")
                addLog("ESP32 rejected OLED page")
            }
            text.startsWith("NAME:", ignoreCase = true) -> {
                val name = text.substringAfter(":").trim()
                applyDeviceNameRead(name)
            }
            fromNotification || looksLikeGlovePayload(text) -> {
                handleGlovePayload(text)
            }
            else -> {
                applyDeviceNameRead(text)
            }
        }
    }

    private fun parseOledPageCode(code: String): OledDisplayPage? {
        return when (code.trim().uppercase(java.util.Locale.US)) {
            "DASH", "DASHBOARD" -> OledDisplayPage.DASHBOARD
            "TELEM", "TELEMETRY", "FLEX" -> OledDisplayPage.TELEMETRY
            "SYS", "SYSTEM", "STATUS" -> OledDisplayPage.SYSTEM
            "HAND", "FINGER" -> OledDisplayPage.HAND
            "WAVE", "WAVEFORM", "GRAPH" -> OledDisplayPage.WAVE
            else -> null
        }
    }

    private fun applyDeviceNameRead(name: String) {
        if (name.isEmpty()) return
        val expectedName = pendingName
        if (
            pendingWriteKind == PendingWriteKind.DEVICE_NAME &&
            expectedName != null &&
            name == expectedName
        ) {
            confirmDeviceName(name)
            return
        }
        _uiState.update { it.copy(connectedName = name.take(MaxDeviceNameChars)) }
        addLog("Name read: $name")
    }

    private fun confirmDeviceName(name: String) {
        saveTimeoutJob?.cancel()
        nameConfirmReadJob?.cancel()
        pendingWriteKind = null
        pendingName = null
        _uiState.update {
            val nextName = name.ifEmpty { it.connectedName.orEmpty() }.take(MaxDeviceNameChars)
            val resolvedName = nextName.ifEmpty { it.connectedName.orEmpty() }
            it.copy(
                connectedName = if (resolvedName.isEmpty()) null else resolvedName,
                isSavingName = false
            )
        }
        emitToast("บันทึกชื่อแล้ว")
        addLog("ESP32 confirmed name: $name")
    }

    private fun handleGlovePayload(text: String) {
        val preview = text.take(MaxPayloadPreviewChars)
        var nextCount = 0
        _uiState.update { state ->
            nextCount = state.glovePacketCount + 1
            state.copy(
                latestPayload = preview,
                latestPayloadAtMillis = SystemClock.elapsedRealtime(),
                glovePacketCount = nextCount
            )
        }
        if (nextCount == 1 || nextCount % 20 == 0) {
            addLog("Glove packets received: $nextCount")
        }

        if (_uiState.value.recordingState == RecordingState.RECORDING) {
            parseFlexSample(text)?.let { sample ->
                recordingBuffer.add(sample)
                _uiState.update { it.copy(recordingSampleCount = recordingBuffer.size) }
            }
        }
    }

    private fun parseFlexSample(text: String): FlexSample? {
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
            // IMU is optional; absent keys (no MPU6050 / older firmware) stay 0.
            ax = map["ax"] ?: 0, ay = map["ay"] ?: 0, az = map["az"] ?: 0,
            gx = map["gx"] ?: 0, gy = map["gy"] ?: 0, gz = map["gz"] ?: 0
        )
    }

    private fun looksLikeGlovePayload(text: String): Boolean {
        val upper = text.uppercase(java.util.Locale.US)
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

    private fun failConnection(message: String) {
        addLog(message)
        emitToast(message)
        disconnect()
        _uiState.update {
            it.copy(
                dialog = UiDialog(
                    title = "เชื่อมต่อไม่ได้",
                    message = "$message\n\nตรวจว่า ESP32 ใช้ Service UUID และ Characteristic UUID ตรงกับแอป"
                )
            )
        }
    }

    private fun handleDisconnected(lost: Boolean) {
        connectingAddress = null
        connectAttempt = 0
        stopConnectedTickers()
        closeGatt()
        _uiState.update {
            it.copy(
                status = BleStatus.DISCONNECTED,
                phase = ConnectionPhase.IDLE,
                connectedName = null,
                connectedAddress = null,
                connectedRssi = null,
                connectedUptimeSeconds = 0,
                latestPayload = null,
                latestPayloadAtMillis = null,
                glovePacketCount = 0,
                isSavingName = false,
                isSendingCommand = false,
                dialog = if (lost) {
                    UiDialog(
                        title = "การเชื่อมต่อหลุด",
                        message = "ESP32 ตัดการเชื่อมต่อกลางคัน. ตรวจระยะห่าง แบตเตอรี่ และลองเชื่อมต่อใหม่"
                    )
                } else {
                    it.dialog
                }
            )
        }
    }

    private fun closeGatt() {
        nameCharacteristic = null
        if (hasConnectPermission()) {
            runCatching {
                @SuppressLint("MissingPermission")
                bluetoothGatt?.close()
            }
        }
        bluetoothGatt = null
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
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _uiState.update { state ->
            val nextLogs = (state.logs + "$timestamp $message").takeLast(80)
            state.copy(logs = nextLogs)
        }
    }

    override fun onCleared() {
        datasetPlaybackJob?.cancel()
        stopScan()
        disconnect()
        super.onCleared()
    }

    private companion object {
        const val MAX_CONNECT_ATTEMPTS = 3
    }
}
