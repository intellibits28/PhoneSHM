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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    activeBuildingHash: String,
    activeMeasurementId: String,
    modifier: Modifier = Modifier,
    onNavigateToAnalysis: (String?) -> Unit = {},
    onNavigateToHistory: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    
    var startDelay by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(5) }
    var showConfigDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(activeMeasurementId) {
        viewModel.checkPastSessions(activeMeasurementId)
    }

    androidx.compose.runtime.LaunchedEffect(uiState.recordingFinished) {
        if (uiState.recordingFinished) {
            try {
                val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
                toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 500)
                kotlinx.coroutines.delay(600)
                toneGen.release()
            } catch (e: Exception) {
                // Ignore if tone generation fails
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status indicator
        val statusText = when {
            uiState.isCalibrating -> uiState.calibrationStatus
            uiState.isRecording -> "RECORDING LIVE DATA"
            uiState.recordingFinished -> "RECORDING COMPLETED"
            else -> "IDLE (READY TO RECORD)"
        }
        val statusColor = when {
            uiState.isCalibrating -> Color(0xFFF57F17) // Warning/Orange
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
        if (!uiState.isRecording && !uiState.isCalibrating) {
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

            if (uiState.hasPastSessions) {
                OutlinedButton(
                    onClick = onNavigateToHistory,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "View Past Sessions",
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            androidx.compose.material3.TextButton(
                onClick = { showConfigDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("View & Edit DSP Config Parameters")
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (showConfigDialog) {
            val configMgr = com.ronin.phoneshm.core.storage.RemoteConfigManager
            var peakToRms by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(configMgr.peakToRmsThreshold.toFloat()) }
            var ambientSnr by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(configMgr.ambientSnrThresholdDb) }
            var sanity by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(configMgr.spectralSanityThreshold.toFloat()) }

            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showConfigDialog = false },
                title = { Text("Edit DSP Config Settings") },
                text = {
                    Column {
                        Text("These DSP threshold values are now stored locally.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text("PEAK_TO_RMS: ${String.format("%.1f", peakToRms)}", fontWeight = FontWeight.Bold)
                        androidx.compose.material3.Slider(value = peakToRms, onValueChange = { peakToRms = it }, valueRange = 2f..20f)
                        
                        Text("AMBIENT_SNR_DB: ${String.format("%.1f", ambientSnr)}", fontWeight = FontWeight.Bold)
                        androidx.compose.material3.Slider(value = ambientSnr, onValueChange = { ambientSnr = it }, valueRange = 0.1f..10f)
                        
                        Text("SPECTRAL_SANITY: ${String.format("%.2f", sanity)}", fontWeight = FontWeight.Bold)
                        androidx.compose.material3.Slider(value = sanity, onValueChange = { sanity = it }, valueRange = 0.01f..0.5f)
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        configMgr.updateConfig(peakToRms = peakToRms, ambientSnr = ambientSnr, spectralSanity = sanity)
                        showConfigDialog = false
                    }) {
                        Text("Save & Apply")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showConfigDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
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
        if (!uiState.isRecording && !uiState.isCalibrating) {
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
            
            // Start Delay Selection
            Text("Start Delay Timer (before recording)", style = MaterialTheme.typography.labelMedium, color = Color.Gray, modifier = Modifier.padding(bottom = 4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0, 5, 10, 15).forEach { delay ->
                    OutlinedButton(
                        onClick = { startDelay = delay },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (startDelay == delay) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        )
                    ) {
                        Text("${delay}s", color = if (startDelay == delay) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        // Recording Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.isCalibrating) {
                // Hide recording actions during calibration
            } else if (uiState.isRecording) {
                Button(
                    onClick = { viewModel.stopRecordingEarly() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Stop Recording")
                }
            } else {
                Button(
                    onClick = {
                        viewModel.startRecording(
                            buildingHash = activeBuildingHash,
                            measurementId = activeMeasurementId,
                            durationSec = 10,
                            startDelaySec = startDelay
                        ) { sid, uri ->
                            com.ronin.phoneshm.core.storage.SessionUploadManager.enqueueUpload(context, sid, uri)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("10s Demo")
                }
                Button(
                    onClick = {
                        viewModel.startRecording(
                            buildingHash = activeBuildingHash,
                            measurementId = activeMeasurementId,
                            durationSec = 30,
                            startDelaySec = startDelay
                        ) { sid, uri ->
                            com.ronin.phoneshm.core.storage.SessionUploadManager.enqueueUpload(context, sid, uri)
                        }
                    },
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
                        viewModel.startRecording(
                            buildingHash = activeBuildingHash,
                            measurementId = activeMeasurementId,
                            durationSec = duration,
                            startDelaySec = startDelay
                        ) { sid, uri ->
                            com.ronin.phoneshm.core.storage.SessionUploadManager.enqueueUpload(context, sid, uri)
                        }
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

        // Battery Restriction Warning Dialog for Xiaomi/OEM long ambient recordings
        val context = androidx.compose.ui.platform.LocalContext.current
        var showBatteryWarningDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

        if (showBatteryWarningDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showBatteryWarningDialog = false },
                title = {
                    Text(
                        text = "⚡ Battery Restriction Notice",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                text = {
                    Text(
                        text = "On Xiaomi/MIUI, Huawei, Oppo, and Vivo devices, background battery restrictions can cause sampling gaps during long recordings, especially if the screen turns off or the app is backgrounded.\n\nTo ensure clean 10-minute recordings:\n1. Set Battery Saver to 'No Restrictions'\n2. Enable 'Autostart' (MIUI specific)\n3. Keep screen on and remain in app during recording.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            context.startActivity(com.ronin.phoneshm.core.device.BatteryOptimizationHelper.createAppSettingsIntent(context))
                        }
                    ) {
                        Text("Open Settings")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showBatteryWarningDialog = false
                            viewModel.startRecording(
                                buildingHash = activeBuildingHash,
                                measurementId = activeMeasurementId,
                                durationSec = 600,
                                startDelaySec = startDelay
                            ) { sid, uri ->
                                com.ronin.phoneshm.core.storage.SessionUploadManager.enqueueUpload(context, sid, uri)
                            }
                        }
                    ) {
                        Text("Proceed Recording")
                    }
                }
            )
        }

        // Ambient Baseline Mode: 10-minute continuous recording for weak-signal buildings
        if (!uiState.isRecording && !uiState.isCalibrating) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (com.ronin.phoneshm.core.device.BatteryOptimizationHelper.shouldShowBatteryWarning(context)) {
                        showBatteryWarningDialog = true
                    } else {
                        viewModel.startRecording(
                            buildingHash = activeBuildingHash,
                            measurementId = activeMeasurementId,
                            durationSec = 600,
                            startDelaySec = startDelay
                        ) { sid, uri ->
                            com.ronin.phoneshm.core.storage.SessionUploadManager.enqueueUpload(context, sid, uri)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E7490))
            ) {
                Text(
                    text = "\uD83C\uDF0A Ambient Baseline (10 min, No Impact Needed)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
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
