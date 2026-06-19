package com.darkton.gloveble

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ConnectScreen(
    state: BleUiState,
    onScanClick: () -> Unit,
    onStopScanClick: () -> Unit,
    onConnectClick: (String) -> Unit,
    onDisconnectHand: (Hand) -> Unit,
    onGoLive: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(state.hands, key = { it.hand }) { hand ->
            HandSlotCard(
                connection = hand,
                onDisconnect = { onDisconnectHand(hand.hand) },
                onGoLive = onGoLive
            )
        }

        item {
            ScanPanel(
                state = state,
                onScanClick = onScanClick,
                onStopScanClick = onStopScanClick
            )
        }

        item {
            DeviceListHeader(count = state.devices.size)
        }

        if (state.devices.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (state.isScanning) "กำลังค้นหาถุงมือ..." else "กดปุ่มสแกนเพื่อเริ่มค้นหา",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(state.devices, key = { it.address }) { device ->
                val connecting = state.hands.any {
                    it.status == BleStatus.CONNECTING && it.connectedAddress == device.address
                }
                val connectedHere = state.hands.firstOrNull {
                    it.isConnected && it.connectedAddress == device.address
                }
                DeviceRow(
                    device = device,
                    connecting = connecting,
                    connectedHand = connectedHere?.hand,
                    onConnectClick = onConnectClick
                )
            }
        }
    }
}

@Composable
private fun HandSlotCard(
    connection: HandConnection,
    onDisconnect: () -> Unit,
    onGoLive: () -> Unit
) {
    SectionCard(
        title = "${connection.hand.label} (${connection.hand.short})",
        subtitle = when (connection.status) {
            BleStatus.CONNECTED -> connection.connectedName ?: connection.connectedAddress ?: "Glove"
            BleStatus.CONNECTING -> "กำลังเชื่อมต่อ..."
            else -> "ยังไม่ได้เชื่อมต่อ"
        },
        trailing = {
            if (connection.isConnected) SignalBars(rssi = connection.connectedRssi)
        }
    ) {
        ConnectionVisualizer(
            status = connection.status,
            packetCount = connection.glovePacketCount,
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        )

        if (connection.isConnected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoMetric(
                    label = "RSSI",
                    value = connection.connectedRssi?.let { "$it dBm" } ?: "--",
                    modifier = Modifier.weight(1f)
                )
                InfoMetric(
                    label = "Uptime",
                    value = formatUptime(connection.connectedUptimeSeconds),
                    modifier = Modifier.weight(1f)
                )
                InfoMetric(
                    label = "Packets",
                    value = connection.glovePacketCount.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
            Button(onClick = onGoLive, modifier = Modifier.fillMaxWidth()) {
                Text("ดูข้อมูล Live")
            }
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("ตัด ${connection.hand.label}")
            }
        } else if (connection.status == BleStatus.CONNECTING) {
            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("ยกเลิก")
            }
        } else {
            Text(
                text = "สแกนด้านล่าง แล้วเลือกอุปกรณ์ที่ลงท้ายด้วย ${connection.hand.short} เพื่อจับคู่ช่องนี้",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScanPanel(
    state: BleUiState,
    onScanClick: () -> Unit,
    onStopScanClick: () -> Unit
) {
    SectionCard(
        title = "ค้นหาถุงมือ",
        subtitle = state.scanPhase.label,
        trailing = {
            if (state.isScanning) {
                OutlinedButton(onClick = onStopScanClick) { Text("หยุด") }
            } else {
                Button(onClick = onScanClick) { Text("สแกน") }
            }
        }
    ) {
        AnimatedVisibility(visible = state.isScanning && state.devices.isEmpty()) {
            Text(
                text = "กำลังค้นหาอุปกรณ์ชื่อ ESP32, Glove, SmartGlove...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ConnectionVisualizer(
    status: BleStatus,
    packetCount: Int,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "connectionVisualizer")
    val loop by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "connectionLoop"
    )
    val linkProgress by animateFloatAsState(
        targetValue = when (status) {
            BleStatus.CONNECTED -> 1f
            BleStatus.CONNECTING -> 0.62f + loop * 0.32f
            else -> 0f
        },
        animationSpec = tween(durationMillis = 260),
        label = "linkProgress"
    )
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier) {
        val stroke = 3.dp.toPx()
        val centerY = size.height * 0.54f
        val phone = Offset(size.width * 0.28f, centerY)
        val glove = Offset(size.width * 0.72f, centerY)
        val activeColor = when (status) {
            BleStatus.SCANNING -> secondary
            BleStatus.CONNECTING -> tertiary
            BleStatus.CONNECTED -> primary
            BleStatus.DISCONNECTED -> outline
        }

        if (status == BleStatus.SCANNING) {
            repeat(3) { index ->
                val ring = (loop + index / 3f) % 1f
                drawCircle(
                    color = secondary.copy(alpha = (1f - ring) * 0.24f),
                    radius = 24.dp.toPx() + ring * 42.dp.toPx(),
                    center = glove,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        val targetX = phone.x + (glove.x - phone.x) * linkProgress.coerceIn(0f, 1f)
        drawLine(
            color = outline.copy(alpha = 0.5f),
            start = phone,
            end = glove,
            strokeWidth = stroke
        )
        if (status == BleStatus.CONNECTING || status == BleStatus.CONNECTED) {
            drawLine(
                color = activeColor,
                start = phone,
                end = Offset(targetX, glove.y),
                strokeWidth = stroke
            )
            val movingX = phone.x + (glove.x - phone.x) * loop
            drawCircle(
                color = activeColor.copy(alpha = 0.9f),
                radius = 4.dp.toPx(),
                center = Offset(movingX, centerY)
            )
        }

        val phoneWidth = 34.dp.toPx()
        val phoneHeight = 54.dp.toPx()
        drawRoundRect(
            color = surfaceVariant,
            topLeft = Offset(phone.x - phoneWidth / 2f, phone.y - phoneHeight / 2f),
            size = Size(phoneWidth, phoneHeight),
            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
        )
        drawRoundRect(
            color = activeColor.copy(alpha = 0.75f),
            topLeft = Offset(phone.x - phoneWidth / 2.8f, phone.y - phoneHeight / 3.2f),
            size = Size(phoneWidth / 1.4f, phoneHeight / 1.55f),
            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
        )

        val palmWidth = 42.dp.toPx()
        val palmHeight = 38.dp.toPx()
        drawRoundRect(
            color = activeColor.copy(alpha = if (status == BleStatus.DISCONNECTED) 0.35f else 0.88f),
            topLeft = Offset(glove.x - palmWidth / 2f, glove.y - palmHeight / 3f),
            size = Size(palmWidth, palmHeight),
            cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
        )
        repeat(5) { index ->
            val fingerX = glove.x - 18.dp.toPx() + index * 9.dp.toPx()
            val height = (22 + (index % 2) * 6).dp.toPx()
            drawRoundRect(
                color = activeColor.copy(alpha = if (status == BleStatus.DISCONNECTED) 0.3f else 0.95f),
                topLeft = Offset(fingerX, glove.y - palmHeight / 3f - height + 4.dp.toPx()),
                size = Size(6.dp.toPx(), height),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
        }

        if (status == BleStatus.CONNECTED) {
            val packetPulse = if (packetCount > 0) 0.32f + 0.28f * (1f - loop) else 0.16f
            drawCircle(
                color = primary.copy(alpha = packetPulse),
                radius = 42.dp.toPx() + loop * 16.dp.toPx(),
                center = glove,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

@Composable
private fun DeviceListHeader(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "อุปกรณ์ที่พบ",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun DeviceRow(
    device: BleDeviceItem,
    connecting: Boolean,
    connectedHand: Hand?,
    onConnectClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            SignalBars(rssi = device.rssi)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${device.rssi} dBm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (device.advertisesService) {
                DeviceBadge("service")
            }
            if (device.isLikelyGlove) {
                DeviceBadge("glove")
            }
            connectedHand?.let { DeviceBadge("ใช้เป็น ${it.short}") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onConnectClick(device.address) },
                enabled = !connecting && connectedHand == null,
                modifier = Modifier.widthIn(min = 112.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)
            ) {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (connectedHand != null) "เชื่อมแล้ว" else "เชื่อมต่อ")
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun DeviceBadge(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
    }
}
