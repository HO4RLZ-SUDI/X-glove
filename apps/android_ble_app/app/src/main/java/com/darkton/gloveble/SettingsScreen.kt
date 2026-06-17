package com.darkton.gloveble

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    state: BleUiState,
    themeController: ThemeController,
    onSaveNameClick: (String) -> Unit,
    onSendCommandClick: (String) -> Unit,
    onDisconnectClick: () -> Unit,
    onToggleLogs: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ThemeCard(controller = themeController)
        }

        if (state.status == BleStatus.CONNECTED) {
            item {
                DeviceNameCard(state = state, onSaveNameClick = onSaveNameClick)
            }
            item {
                CommandConsoleCard(state = state, onSendCommandClick = onSendCommandClick)
            }
        } else {
            item {
                SectionCard(title = "อุปกรณ์") {
                    Text(
                        text = "เชื่อมต่อถุงมือก่อน เพื่อเปลี่ยนชื่ออุปกรณ์และส่งคำสั่ง",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            DebugLogCard(state = state, onToggleLogs = onToggleLogs)
        }

        item {
            SectionCard(title = "เกี่ยวกับ") {
                AboutRow("เวอร์ชันแอป", "0.3.1")
                AboutRow("Service UUID", Esp32ServiceUuid.toString())
                AboutRow("Characteristic", Esp32NameCharacteristicUuid.toString())
            }
        }

        if (state.status == BleStatus.CONNECTED) {
            item {
                OutlinedButton(
                    onClick = onDisconnectClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("ตัดการเชื่อมต่อถุงมือ")
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(controller: ThemeController) {
    SectionCard(
        title = "ธีมและการแสดงผล",
        subtitle = "ปรับแอปให้เข้ากับสไตล์ของคุณ"
    ) {
        // Light / dark / system.
        Text(
            text = "โหมดสี",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeMode.entries.forEach { mode ->
                SegmentChip(
                    text = mode.label,
                    selected = controller.mode == mode,
                    modifier = Modifier.weight(1f),
                    onClick = { controller.selectMode(mode) }
                )
            }
        }

        // Material You dynamic color (Android 12+).
        if (controller.dynamicColorSupported) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { controller.selectDynamicColor(!controller.dynamicColor) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "สีจากวอลล์เปเปอร์ (Material You)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "ดึงสีจากภาพพื้นหลังเครื่องของคุณ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = controller.dynamicColor,
                    onCheckedChange = { controller.selectDynamicColor(it) }
                )
            }
        }

        // Accent palette (used when dynamic color is off).
        Text(
            text = "สีหลัก",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AccentTheme.entries.forEach { accent ->
                AccentSwatch(
                    accent = accent,
                    selected = !controller.dynamicColor && controller.accent == accent,
                    dimmed = controller.dynamicColor,
                    onClick = {
                        if (controller.dynamicColor) controller.selectDynamicColor(false)
                        controller.selectAccent(accent)
                    }
                )
            }
        }
    }
}

@Composable
private fun SegmentChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = container,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = content,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

@Composable
private fun AccentSwatch(
    accent: AccentTheme,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit
) {
    val ring = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Column(
        modifier = Modifier
            .width(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(accent.seed.copy(alpha = if (dimmed) 0.4f else 1f))
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = ring,
                    shape = RoundedCornerShape(percent = 50)
                )
        )
        Text(
            text = accent.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DeviceNameCard(
    state: BleUiState,
    onSaveNameClick: (String) -> Unit
) {
    var deviceName by remember(state.connectedName) {
        mutableStateOf(state.connectedName.orEmpty().take(MaxDeviceNameChars))
    }

    SectionCard(
        title = "ชื่อถุงมือ",
        subtitle = "ชื่อที่ใช้ตอน advertise ผ่าน BLE"
    ) {
        OutlinedTextField(
            value = deviceName,
            onValueChange = { next ->
                if (next.length <= MaxDeviceNameChars) deviceName = next
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ชื่อถุงมือ") },
            supportingText = {
                Text("${deviceName.length}/$MaxDeviceNameChars")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words
            )
        )

        Button(
            onClick = { onSaveNameClick(deviceName) },
            enabled = !state.isSavingName,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isSavingName) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
            }
            Text("บันทึกชื่อ")
        }
    }
}

@Composable
private fun CommandConsoleCard(
    state: BleUiState,
    onSendCommandClick: (String) -> Unit
) {
    var command by remember { mutableStateOf("") }

    SectionCard(
        title = "ส่งคำสั่งเอง",
        subtitle = "สำหรับผู้ใช้ขั้นสูง เช่น OLED:WAVE, BRIGHT:50, CAL"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { next ->
                    if (next.length <= MaxCommandChars) command = next
                },
                modifier = Modifier.weight(1f),
                label = { Text("คำสั่ง") },
                supportingText = {
                    Text("${command.length}/$MaxCommandChars")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters
                )
            )
            Button(
                onClick = { onSendCommandClick(command) },
                enabled = command.isNotBlank() && !state.isSendingCommand,
                modifier = Modifier.defaultMinSize(minWidth = 84.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (state.isSendingCommand) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("ส่ง")
                }
            }
        }
    }
}

@Composable
private fun DebugLogCard(
    state: BleUiState,
    onToggleLogs: () -> Unit
) {
    SectionCard(
        title = "Debug log",
        subtitle = "เหตุการณ์ BLE ล่าสุด ${state.logs.size} รายการ",
        trailing = {
            TextButton(onClick = onToggleLogs) {
                Text(if (state.showLogs) "ซ่อน" else "แสดง")
            }
        }
    ) {
        if (state.showLogs) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0B1220))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.logs) { line ->
                    Text(
                        text = line,
                        color = Color(0xFFE5E7EB),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}
