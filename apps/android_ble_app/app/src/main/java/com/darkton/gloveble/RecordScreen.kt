package com.darkton.gloveble

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val SuggestedLabels = listOf("ก", "ข", "ค", "A", "B", "สวัสดี", "ขอบคุณ")

@Composable
fun RecordScreen(
    state: BleUiState,
    onStartRecording: (String) -> Unit,
    onCancelRecording: () -> Unit,
    onGoConnect: () -> Unit,
    onGoDataset: () -> Unit
) {
    if (state.status != BleStatus.CONNECTED) {
        NotConnectedPlaceholder(
            message = "เชื่อมต่อถุงมือก่อน แล้วจึงบันทึกท่ามือได้",
            onGoConnect = onGoConnect
        )
        return
    }

    var label by rememberSaveable { mutableStateOf("") }
    val live = remember(state.latestPayload) { parseLiveGloveData(state.latestPayload) }

    when (state.recordingState) {
        RecordingState.IDLE -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    SectionCard(
                        title = "บันทึกท่ามือใหม่",
                        subtitle = "ตั้งชื่อท่า กดเริ่ม แล้วค้างท่าไว้ ${RecordingDurationMs / 1000} วินาที"
                    ) {
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it.take(20) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("ชื่อท่า เช่น ก, A, สวัสดี") },
                            singleLine = true
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SuggestedLabels.forEach { suggestion ->
                                OutlinedButton(
                                    onClick = { label = suggestion },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(suggestion)
                                }
                            }
                        }

                        Button(
                            onClick = { onStartRecording(label) },
                            enabled = label.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = MaterialTheme.colorScheme.onSecondary
                            )
                        ) {
                            Text("● เริ่มบันทึก (นับถอยหลัง $RecordingCountdownSec วิ)")
                        }
                    }
                }

                item {
                    SectionCard(title = "ท่าปัจจุบันของมือ") {
                        HandVisualizer(
                            levels = live?.flex?.map { flexPercent(it) }
                                ?: List(FlexChannels) { 0 },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                        )
                    }
                }

                state.lastSavedPath?.let { path ->
                    item {
                        SectionCard(title = "บันทึกล่าสุด") {
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            OutlinedButton(
                                onClick = onGoDataset,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("ดูข้อมูลที่บันทึกไว้")
                            }
                        }
                    }
                }
            }
        }

        RecordingState.COUNTDOWN -> {
            RecordingOverlay {
                Text(
                    text = "เตรียมท่า \"${label.ifBlank { "ท่ามือ" }}\"",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = state.recordingCountdown.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "ค้างท่าไว้จนกว่าจะบันทึกเสร็จ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        RecordingState.RECORDING -> {
            val expectedSamples = (RecordingDurationMs / 100).toInt()
            RecordingOverlay {
                Text(
                    text = "กำลังบันทึก \"${label.ifBlank { "ท่ามือ" }}\"",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )
                HandVisualizer(
                    levels = live?.flex?.map { flexPercent(it) }
                        ?: List(FlexChannels) { 0 },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                )
                LinearProgressIndicator(
                    progress = {
                        (state.recordingSampleCount / expectedSamples.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${state.recordingSampleCount} / $expectedSamples ตัวอย่าง",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onCancelRecording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("ยกเลิก")
                }
            }
        }
    }
}

@Composable
private fun RecordingOverlay(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            content()
        }
    }
}
