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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
    modifier: Modifier = Modifier,
    onNavigateToAnalysis: (String?) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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

        Spacer(modifier = Modifier.height(12.dp))

        // Prominent Top Analyze Button (Always visible when not actively recording)
        if (!uiState.isRecording) {
            Button(
                onClick = { onNavigateToAnalysis(uiState.rawStorageFileUri) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.recordingFinished) Color(0xFF0284C7) else Color(0xFF0F766E)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (uiState.recordingFinished) "⚡ Phase 5: Analyze Recorded Session Now ->" else "⚡ Phase 5: Modal & Physics Engine Demo ->",
                    color = Color.White,
                    fontWeight = FontWeight.Black
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

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

                    val internalPath = uiState.rawStorageFileUri ?: "None"
                    val fileName = internalPath.substringAfterLast('/')
                    val publicPath = "/storage/emulated/0/Download/PhoneSHM/$fileName"
                    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                    val context = androidx.compose.ui.platform.LocalContext.current

                    Text("Internal App Storage (Private DB Link):", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        androidx.compose.foundation.text.selection.SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                text = internalPath,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.DarkGray
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(internalPath))
                                android.widget.Toast.makeText(context, "Copied internal path!", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Copy", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Public Mirror (Accessible by Termux & File Manager):", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        androidx.compose.foundation.text.selection.SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                text = publicPath,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1B5E20)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(publicPath))
                                android.widget.Toast.makeText(context, "Copied public path!", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Copy", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = {
                                val cmd = "adb pull $publicPath ."
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(cmd))
                                android.widget.Toast.makeText(context, "Copied ADB Pull cmd!", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Copy ADB Pull Cmd", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { onNavigateToAnalysis(uiState.rawStorageFileUri) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Phase 5: Analyze Modal & Physics ->", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Quick jump to Phase 5 Analysis if not recording
        if (!uiState.isRecording) {
            Button(
                onClick = { onNavigateToAnalysis(uiState.rawStorageFileUri) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (uiState.recordingFinished) Color(0xFF0284C7) else Color(0xFF0F766E)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = if (uiState.recordingFinished) "⚡ Phase 5: Analyze Recorded Session Now ->" else "Phase 5: Modal & Physics Engine Demo ->",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }

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
                    Text("10s Demo")
                }
                Button(
                    onClick = { viewModel.startRecording("building_profile_active", 30) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("30s Quick")
                }
                Button(
                    onClick = {
                        // In a full implementation, we'd fetch the actual building type and floors from DataStore.
                        // For the demo, we use a placeholder lookup.
                        val config = com.ronin.phoneshm.core.physics.PhysicsRulesConfig.loadBundledConfig()
                        val duration = config.getRecommendedDurationSec("RESIDENTIAL_CONCRETE", 3)
                        viewModel.startRecording("building_profile_active", duration) 
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C3AED))
                ) {
                    // We can't know the exact duration here synchronously without side-effects, 
                    // but we can label it as 'Adaptive SHM'.
                    Text("Adaptive SHM")
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
