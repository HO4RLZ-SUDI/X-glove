package com.darkton.gloveble

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Card with an optional header used by every screen. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (trailing != null) {
                        Spacer(Modifier.width(12.dp))
                        trailing()
                    }
                }
            }
            content()
        }
    }
}

@Composable
fun InfoMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun PayloadChip(label: String, value: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 34.dp)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun SignalBars(rssi: Int?, modifier: Modifier = Modifier) {
    val activeBars = when {
        rssi == null -> 0
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -80 -> 2
        else -> 1
    }
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = modifier
            .width(30.dp)
            .height(22.dp)
    ) {
        val barWidth = size.width / 6f
        val gap = barWidth / 2f
        for (index in 0 until 4) {
            val heightFraction = (index + 1) / 4f
            val barHeight = size.height * heightFraction
            val left = index * (barWidth + gap)
            drawRoundRect(
                color = if (index < activeBars) active else inactive,
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(3f, 3f)
            )
        }
    }
}

/** Animated horizontal bar for one flex channel. */
@Composable
fun FlexMeterRow(index: Int, value: Int?, modifier: Modifier = Modifier) {
    val level = value?.let { flexPercent(it) } ?: 0
    val fraction by animateFloatAsState(
        targetValue = level / 100f,
        animationSpec = tween(durationMillis = 160),
        label = "flexMeter$index"
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "F${index + 1}",
            modifier = Modifier.width(26.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Text(
            text = value?.toString() ?: "--",
            modifier = Modifier.width(48.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 1
        )
    }
}

@Composable
fun BatteryBadge(batteryMv: Int?, modifier: Modifier = Modifier) {
    val percent = batteryPercentFromMv(batteryMv)
    val color = when {
        percent == null -> MaterialTheme.colorScheme.onSurfaceVariant
        percent < 20 -> MaterialTheme.colorScheme.error
        percent < 50 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            text = percent?.let { "🔋 $it%" } ?: "🔋 --",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

/** Full-screen placeholder shown on tabs that need an active connection. */
@Composable
fun NotConnectedPlaceholder(
    message: String,
    onGoConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Bluetooth,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "ยังไม่ได้เชื่อมต่อถุงมือ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onGoConnect) {
                Text("ไปหน้าเชื่อมต่อ")
            }
        }
    }
}

// ---------- Telemetry parsing helpers ----------

/** Accel/gyro raw counts from a live packet; null when the IMU sent nothing. */
data class ImuReading(
    val ax: Int, val ay: Int, val az: Int,
    val gx: Int, val gy: Int, val gz: Int
)

data class LiveGloveData(
    val flex: List<Int>,
    val batteryMv: Int?,
    val imu: ImuReading? = null
)

// MPU6050 default full-scale ranges: accel +/-2g, gyro +/-250 deg/s.
private const val AccelLsbPerG = 16384f
private const val GyroLsbPerDps = 131f

/** Parses a `DATA:f1=..,ax=..,gx=..,b=..` packet into flex + IMU + battery. */
fun parseLiveGloveData(payload: String?): LiveGloveData? {
    if (payload.isNullOrBlank()) return null
    val body = if (payload.contains(":")) payload.substringAfter(":") else payload
    val map = body.split(',').mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq < 0) return@mapNotNull null
        val key = part.substring(0, eq).trim().lowercase(java.util.Locale.US)
        val value = part.substring(eq + 1).trim().toIntOrNull() ?: return@mapNotNull null
        key to value
    }.toMap()
    val flex = (1..FlexChannels).map { map["f$it"] ?: return null }
    val imu = if (map.containsKey("ax")) {
        ImuReading(
            ax = map["ax"] ?: 0, ay = map["ay"] ?: 0, az = map["az"] ?: 0,
            gx = map["gx"] ?: 0, gy = map["gy"] ?: 0, gz = map["gz"] ?: 0
        )
    } else {
        null
    }
    return LiveGloveData(flex = flex, batteryMv = map["b"], imu = imu)
}

fun accelG(raw: Int): Float = raw / AccelLsbPerG

fun gyroDps(raw: Int): Float = raw / GyroLsbPerDps

/**
 * Maps a flex reading to 0..100. Calibrated values are deltas with full scale
 * ~1400 (matches firmware); large values mean an uncalibrated raw 0..4095 read.
 */
fun flexPercent(value: Int): Int {
    val magnitude = abs(value)
    val fullScale = if (magnitude > 1600) 4095 else 1400
    return (magnitude.coerceAtMost(fullScale) * 100) / fullScale
}

fun batteryPercentFromMv(batteryMv: Int?): Int? {
    if (batteryMv == null || batteryMv < 0) return null
    val clamped = batteryMv.coerceIn(3300, 4200)
    return (clamped - 3300) * 100 / 900
}

data class PayloadValue(
    val label: String,
    val value: String
)

fun parsePayloadValues(payload: String?): List<PayloadValue> {
    if (payload.isNullOrBlank()) return emptyList()
    val body = when {
        payload.contains(":") -> payload.substringAfter(":")
        else -> payload
    }
    return body
        .split(',', ';', '|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .take(10)
        .mapIndexed { index, token ->
            val parts = token.split("=", ":", limit = 2).map { it.trim() }
            if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                PayloadValue(parts[0], parts[1])
            } else {
                PayloadValue("v${index + 1}", token)
            }
        }
}

// ---------- Formatters ----------

fun formatUptime(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds / 60) % 60
    val secs = seconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, secs)
}

fun formatDurationMs(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val minutes = seconds / 60
    val secs = seconds % 60
    val tenths = (milliseconds % 1000) / 100
    return "%02d:%02d.%d".format(minutes, secs, tenths)
}

fun formatSessionStamp(session: String): String {
    return if (session.length == 15 && session[8] == '_') {
        "${session.substring(0, 4)}-${session.substring(4, 6)}-${session.substring(6, 8)} " +
            "${session.substring(9, 11)}:${session.substring(11, 13)}:${session.substring(13, 15)}"
    } else {
        session
    }
}

fun FlexSample.channelValue(index: Int): Int {
    return when (index) {
        0 -> f1
        1 -> f2
        2 -> f3
        3 -> f4
        4 -> f5
        else -> 0
    }
}
