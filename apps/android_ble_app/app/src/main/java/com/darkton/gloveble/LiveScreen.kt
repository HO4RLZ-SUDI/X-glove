package com.darkton.gloveble

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun LiveScreen(
    state: BleUiState,
    onSendCommandClick: (Hand, String) -> Unit,
    onSelectOledPage: (Hand, OledDisplayPage) -> Unit,
    onGoConnect: () -> Unit
) {
    val connected = state.connectedHands
    if (connected.isEmpty()) {
        NotConnectedPlaceholder(
            message = "เชื่อมต่อถุงมือก่อน เพื่อดูข้อมูลนิ้วมือแบบเรียลไทม์",
            onGoConnect = onGoConnect
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        connected.forEach { connection ->
            item(key = "hand-${connection.hand}") {
                HandLiveSection(
                    connection = connection,
                    onSendCommandClick = { cmd -> onSendCommandClick(connection.hand, cmd) },
                    onSelectOledPage = { page -> onSelectOledPage(connection.hand, page) }
                )
            }
        }
    }
}

@Composable
private fun HandLiveSection(
    connection: HandConnection,
    onSendCommandClick: (String) -> Unit,
    onSelectOledPage: (OledDisplayPage) -> Unit
) {
    val live = remember(connection.latestPayload) { parseLiveGloveData(connection.latestPayload) }
    val payloadAgeSeconds = connection.latestPayloadAtMillis?.let {
        ((SystemClock.elapsedRealtime() - it) / 1000).coerceAtLeast(0)
    }

    SectionCard(
        title = "${connection.hand.label} — ${connection.connectedName ?: "Glove"}",
        subtitle = "นิ้วงอตามเซ็นเซอร์จริง",
        trailing = { BatteryBadge(batteryMv = live?.batteryMv) }
    ) {
        HandVisualizer(
            levels = live?.flex?.map { flexPercent(it) } ?: List(FlexChannels) { 0 },
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
        )

        for (index in 0 until FlexChannels) {
            FlexMeterRow(index = index, value = live?.flex?.getOrNull(index))
        }

        val imu = live?.imu
        if (imu != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PayloadChip("aX", "%+.2f".format(accelG(imu.ax)))
                PayloadChip("aY", "%+.2f".format(accelG(imu.ay)))
                PayloadChip("aZ", "%+.2f".format(accelG(imu.az)))
                PayloadChip("gX", "%+.0f".format(gyroDps(imu.gx)))
                PayloadChip("gY", "%+.0f".format(gyroDps(imu.gy)))
                PayloadChip("gZ", "%+.0f".format(gyroDps(imu.gz)))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoMetric(
                label = "Packets",
                value = connection.glovePacketCount.toString(),
                modifier = Modifier.weight(1f)
            )
            InfoMetric(
                label = "ล่าสุด",
                value = payloadAgeSeconds?.let { "${it}s" } ?: "--",
                modifier = Modifier.weight(1f)
            )
            InfoMetric(
                label = "RSSI",
                value = connection.connectedRssi?.let { "$it dBm" } ?: "--",
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QuickCommandButton("เริ่มสตรีม", "START", connection, onSendCommandClick)
            QuickCommandButton("หยุดสตรีม", "STOP", connection, onSendCommandClick)
            QuickCommandButton("คาลิเบรต", "CAL", connection, onSendCommandClick)
            QuickCommandButton("Ping", "PING", connection, onSendCommandClick)
        }

        Text(
            text = "หน้าจอ OLED",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OledDisplayPage.entries.forEach { page ->
                val selected = connection.selectedOledPage == page
                if (selected) {
                    Button(
                        onClick = { onSelectOledPage(page) },
                        enabled = !connection.isBusy,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(page.label)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelectOledPage(page) },
                        enabled = !connection.isBusy,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(page.label)
                    }
                }
            }
        }

        OledBrightnessControl(
            enabled = !connection.isBusy,
            onSetBrightness = { percent -> onSendCommandClick("BRIGHT:$percent") }
        )

        Text(
            text = connection.latestPayload ?: "รอข้อมูลจากถุงมือ...",
            modifier = Modifier.heightIn(min = 36.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (connection.latestPayload == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontFamily = if (connection.latestPayload == null) null else FontFamily.Monospace
        )
    }
}

@Composable
private fun OledBrightnessControl(
    enabled: Boolean,
    onSetBrightness: (Int) -> Unit
) {
    var brightness by remember { mutableFloatStateOf(70f) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "ความสว่าง OLED",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = brightness,
            onValueChange = { brightness = it },
            onValueChangeFinished = { onSetBrightness(brightness.toInt()) },
            valueRange = 0f..100f,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${brightness.toInt()}%",
            modifier = Modifier.width(40.dp),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun QuickCommandButton(
    label: String,
    command: String,
    connection: HandConnection,
    onSendCommandClick: (String) -> Unit
) {
    OutlinedButton(
        onClick = { onSendCommandClick(command) },
        enabled = !connection.isSendingCommand,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label)
    }
}

/** Draws a palm with five fingers that shorten as flex level rises (0..100). */
@Composable
fun HandVisualizer(levels: List<Int>, modifier: Modifier = Modifier) {
    val animatedLevels = List(FlexChannels) { index ->
        animateFloatAsState(
            targetValue = (levels.getOrNull(index) ?: 0) / 100f,
            animationSpec = tween(durationMillis = 180),
            label = "finger$index"
        )
    }
    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    val track = MaterialTheme.colorScheme.surfaceVariant

    Canvas(modifier = modifier) {
        val palmWidth = size.width * 0.66f
        val palmHeight = size.height * 0.24f
        val palmLeft = (size.width - palmWidth) / 2f
        val palmTop = size.height - palmHeight

        val fingerCount = FlexChannels
        val fingerWidth = palmWidth / (fingerCount * 2f - 1f)
        val maxFingerHeight = size.height - palmHeight - 8.dp.toPx()
        val minFingerHeight = maxFingerHeight * 0.18f

        for (index in 0 until fingerCount) {
            val x = palmLeft + index * fingerWidth * 2f
            drawRoundRect(
                color = track,
                topLeft = Offset(x, palmTop - maxFingerHeight),
                size = Size(fingerWidth, maxFingerHeight + 6.dp.toPx()),
                cornerRadius = CornerRadius(fingerWidth / 2f, fingerWidth / 2f)
            )
            val level = animatedLevels[index].value.coerceIn(0f, 1f)
            val fingerHeight = maxFingerHeight - (maxFingerHeight - minFingerHeight) * level
            drawRoundRect(
                color = primary,
                topLeft = Offset(x, palmTop - fingerHeight),
                size = Size(fingerWidth, fingerHeight + 6.dp.toPx()),
                cornerRadius = CornerRadius(fingerWidth / 2f, fingerWidth / 2f)
            )
        }

        drawRoundRect(
            color = container,
            topLeft = Offset(palmLeft, palmTop),
            size = Size(palmWidth, palmHeight),
            cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx())
        )
        drawRoundRect(
            color = primary.copy(alpha = 0.35f),
            topLeft = Offset(palmLeft, palmTop),
            size = Size(palmWidth, palmHeight),
            cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
        )
    }
}
