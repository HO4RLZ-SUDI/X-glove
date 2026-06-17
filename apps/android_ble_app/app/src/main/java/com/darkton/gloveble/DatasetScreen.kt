package com.darkton.gloveble

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun DatasetScreen(
    state: BleUiState,
    onRefreshDataset: () -> Unit,
    onSelectDatasetSession: (String) -> Unit,
    onPlayDataset: () -> Unit,
    onPauseDataset: () -> Unit,
    onResetDataset: () -> Unit
) {
    val selectedSession = state.datasetSessions
        .firstOrNull { it.id == state.selectedDatasetSessionId }
    val selectedIndex = state.datasetPlaybackSampleIndex
        .coerceIn(0, selectedSession?.samples?.lastIndex ?: 0)
    val selectedSample = selectedSession?.samples?.getOrNull(selectedIndex)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard(
                title = "ชุดข้อมูลท่ามือ",
                subtitle = "${state.datasetSessions.size} sessions",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.isDatasetLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        OutlinedButton(
                            onClick = onRefreshDataset,
                            enabled = !state.isDatasetLoading,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("รีเฟรช")
                        }
                    }
                }
            ) {
                state.datasetMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.datasetCsvPath?.let { path ->
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        selectedSession?.let { session ->
            item {
                SectionCard(
                    title = session.label.ifBlank { "Untitled" },
                    subtitle = formatSessionStamp(session.session)
                ) {
                    DatasetWaveform(
                        session = session,
                        sampleIndex = selectedIndex,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(154.dp)
                    )

                    val progress = if (session.samples.lastIndex > 0) {
                        selectedIndex / session.samples.lastIndex.toFloat()
                    } else {
                        1f
                    }
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selectedSample?.let { sample ->
                            for (index in 0 until FlexChannels) {
                                PayloadChip(
                                    label = "F${index + 1}",
                                    value = sample.channelValue(index).toString()
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${selectedIndex + 1}/${session.sampleCount}  " +
                                formatDurationMs(selectedSample?.timestampMs ?: 0L),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (state.datasetPlaybackPlaying) {
                            Button(
                                onClick = onPauseDataset,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text("หยุด")
                            }
                        } else {
                            Button(
                                onClick = onPlayDataset,
                                enabled = session.sampleCount > 1,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text("เล่น")
                            }
                        }
                        OutlinedButton(
                            onClick = onResetDataset,
                            enabled = session.sampleCount > 1,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("รีเซ็ต")
                        }
                    }
                }
            }
        }

        if (state.datasetSessions.isEmpty()) {
            item {
                SectionCard {
                    Text(
                        text = "ยังไม่มีท่ามือที่บันทึกไว้ ไปที่แท็บ \"บันทึก\" เพื่อเริ่มเก็บข้อมูล",
                        modifier = Modifier.padding(vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(
                items = state.datasetSessions,
                key = { it.id }
            ) { session ->
                DatasetSessionRow(
                    session = session,
                    selected = session.id == selectedSession?.id,
                    onClick = { onSelectDatasetSession(session.id) }
                )
            }
        }
    }
}

@Composable
private fun DatasetSessionRow(
    session: GestureSession,
    selected: Boolean,
    onClick: () -> Unit
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = container,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = session.label.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatSessionStamp(session.session),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "${session.sampleCount} samples",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DatasetWaveform(
    session: GestureSession,
    sampleIndex: Int,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error
    val outline = MaterialTheme.colorScheme.outlineVariant
    val axis = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f)
    val colors = listOf(primary, secondary, tertiary, error, MaterialTheme.colorScheme.onSurface)
    val shape = MaterialTheme.shapes.medium

    Canvas(
        modifier = modifier
            .border(1.dp, outline, shape)
            .padding(8.dp)
    ) {
        val samples = session.samples
        if (samples.isEmpty()) return@Canvas

        val lastIndex = samples.lastIndex.coerceAtLeast(1)
        val laneHeight = size.height / FlexChannels
        for (channel in 0 until FlexChannels) {
            val top = channel * laneHeight
            val values = samples.map { it.channelValue(channel) }
            val minValue = values.minOrNull() ?: 0
            val maxValue = values.maxOrNull() ?: 0
            val range = (maxValue - minValue).takeIf { it != 0 } ?: 1
            val color = colors[channel % colors.size]

            drawLine(
                color = axis,
                start = Offset(0f, top + laneHeight / 2f),
                end = Offset(size.width, top + laneHeight / 2f),
                strokeWidth = 1.dp.toPx()
            )

            for (index in 1 until samples.size) {
                val previous = samples[index - 1].channelValue(channel)
                val current = samples[index].channelValue(channel)
                val x1 = (index - 1) / lastIndex.toFloat() * size.width
                val x2 = index / lastIndex.toFloat() * size.width
                val y1 = top + laneHeight - 4.dp.toPx() -
                    ((previous - minValue) / range.toFloat()) * (laneHeight - 8.dp.toPx())
                val y2 = top + laneHeight - 4.dp.toPx() -
                    ((current - minValue) / range.toFloat()) * (laneHeight - 8.dp.toPx())
                drawLine(
                    color = color,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        val cursorX = sampleIndex.coerceIn(0, samples.lastIndex) /
            lastIndex.toFloat() * size.width
        drawLine(
            color = primary.copy(alpha = 0.88f),
            start = Offset(cursorX, 0f),
            end = Offset(cursorX, size.height),
            strokeWidth = 2.dp.toPx()
        )
    }
}
