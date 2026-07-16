package com.ronin.phoneshm.feature.measurement

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun MeasurementScreen(
    viewModel: MeasurementViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status indicator
        val statusText = when {
            uiState.isRecording -> "RECORDING LIVE DATA"
            uiState.recordingFinished -> "RECORDING COMPLETED"
            else -> "IDLE (READY TO RECORD)"
        }
        val statusColor = when {
            uiState.isRecording -> Color(0xFFE53935) // Vibrant Red
            uiState.recordingFinished -> Color(0xFF43A047) // Vibrant Green
            else -> Color(0xFF757575)
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(statusColor.copy(alpha = 0.15f))
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Text(
                text = statusText,
                color = statusColor,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Realtime Visualizer Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Live Accelerometer Stream (100Hz)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricAxisView(label = "X-Axis", value = uiState.currentX, color = Color(0xFF2196F3))
                    MetricAxisView(label = "Y-Axis", value = uiState.currentY, color = Color(0xFF4CAF50))
                    MetricAxisView(label = "Z-Axis", value = uiState.currentZ, color = Color(0xFFFF9800))
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Samples Collected", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text("${uiState.totalSamplesCollected}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Instant Sample Rate", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text(String.format("%.1f Hz", uiState.currentSampleRateHz), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Progress/Duration bar if recording
        if (uiState.isRecording) {
            Text(
                text = "Recording Elapsed: ${uiState.elapsedSeconds}s",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }

        // Finalized Metadata & Statistics Card
        if (uiState.recordingFinished) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)) // Light green background
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Session Timing & Precision Report",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    ReportRow(label = "Avg Sample Rate:", value = String.format("%.2f Hz", uiState.currentSampleRateHz))
                    ReportRow(label = "Clock Jitter Std Dev:", value = String.format("%.4f ms", uiState.clockJitterMs))
                    ReportRow(
                        label = "Clock Drift PPM:",
                        value = String.format("%.1f PPM", uiState.clockDriftPpm),
                        color = if (Math.abs(uiState.clockDriftPpm) > 500f) Color.Red else Color(0xFF2E7D32)
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = Color(0xFFC8E6C9))
                    Spacer(modifier = Modifier.height(8.dp))

                    Text("Stored Session File Path:", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = uiState.rawStorageFileUri ?: "None",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.DarkGray
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Recording Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.isRecording) {
                Button(
                    onClick = { viewModel.stopRecordingEarly() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Stop Recording")
                }
            } else {
                Button(
                    onClick = { viewModel.startRecording("building_profile_active", 10) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Record 10s")
                }
                Button(
                    onClick = { viewModel.startRecording("building_profile_active", 30) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Record 30s")
                }
            }
        }
    }
}

@Composable
fun MetricAxisView(label: String, value: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = String.format("%+.3f", value),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun ReportRow(label: String, value: String, color: Color = Color.Unspecified) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color)
    }
}
