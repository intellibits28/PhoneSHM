package com.ronin.phoneshm.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.ronin.phoneshm.core.modal.ExcitationSufficiency
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ronin.phoneshm.core.baseline.BaselineManagerEngine

@Composable
fun AnalysisScreen(
    viewModel: AnalysisViewModel = viewModel(),
    onBackToMeasurement: () -> Unit = {},
    onNavigateToReport: (f0: Double, anomaly: Boolean, quality: String, building: String) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var showResetDialog by remember { mutableStateOf(false) }

    if (com.ronin.phoneshm.feature.analysis.BuildConfig.DEBUG && showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Delete baseline for this building?") },
            text = { Text("This will permanently discard the meanF0Hz, stdF0Hz, and history for this building. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    viewModel.resetBaseline(uiState.buildingHash)
                }) {
                    Text("Confirm", color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LaunchedEffect(Unit) {
        if (uiState.modalResult == null && !uiState.isAnalyzing) {
            viewModel.analyzeSessionFileOrDemo(uiState.analyzedFilePath)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "PHASE 5: MODAL & PHYSICS ENGINE",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Structural Dynamics & Multi-Axis Welch PSD",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Typology: ${uiState.buildingType} (${uiState.floors} floors) | 1024-bin FFT @ 100Hz",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }

            if (uiState.isAnalyzing) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Color(0xFF38BDF8))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Executing Multi-Axis Welch Method & Physics Classification...",
                            color = Color(0xFFCBD5E1),
                            fontSize = 14.sp
                        )
                    }
                }
            } else if (uiState.errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF450A0A)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Analysis Failed",
                            color = Color(0xFFFCA5A5),
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = uiState.errorMessage!!, color = Color.White, fontSize = 13.sp)
                    }
                }
            } else {
                val modal = uiState.modalResult
                if (modal != null) {
                    // Fundamental Frequency Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "PRIMARY STRUCTURAL MODE (f₀)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFF94A3B8),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                 if (modal.excitationSufficiency != ExcitationSufficiency.INSUFFICIENT && modal.confidence < BaselineManagerEngine.MIN_QUALITY_CONFIDENCE_THRESHOLD) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF9A3412)),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) {
                                        Text(
                                            text = "LOW CONFIDENCE",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = when (uiState.dominantAxis) {
                                            "X" -> Color(0xFF1D4ED8)
                                            "Y" -> Color(0xFF047857)
                                            "Z" -> Color(0xFFB45309)
                                            else -> Color(0xFF6D28D9)
                                        }
                                    ),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "AXIS: ${uiState.dominantAxis}",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        maxLines = 1,
softWrap = false,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                val isInsufficient = modal.excitationSufficiency == ExcitationSufficiency.INSUFFICIENT
                                Text(
                                    text = String.format("%.2f", modal.fundamentalFrequencyHz),
                                    style = if (isInsufficient) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayMedium,
                                    color = if (isInsufficient) Color(0xFF64748B) else Color(0xFF38BDF8),
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Hz",
                                    style = if (isInsufficient) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                    color = Color(0xFF94A3B8),
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Confidence Score: ${(modal.confidence * 100).toInt()}% (SNR & Prominence Fusion)",
                                color = Color(0xFFCBD5E1),
                                fontSize = 13.sp
                            )
                            if (modal.excitationSufficiency == ExcitationSufficiency.INSUFFICIENT) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3F2121)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = "⚠️ INSUFFICIENT AMBIENT EXCITATION",
                                            color = Color(0xFFFCA5A5),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "The detected signal is close to the phone's sensor noise floor — this reading is likely not a genuine structural frequency. Try: measuring during higher activity (traffic, footsteps, wind); verifying the phone is firmly coupled to a rigid surface; recording for a longer duration.",
                                            color = Color(0xFFFECACA),
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            } else if (modal.confidence < BaselineManagerEngine.MIN_QUALITY_CONFIDENCE_THRESHOLD) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Low confidence — verify with another measurement",
                                    color = Color(0xFFFCD34D),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Physics Classification Card
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (modal.confidence < BaselineManagerEngine.MIN_QUALITY_CONFIDENCE_THRESHOLD) {
                                Color(0xFF334155) // Neutral unconfident styling
                            } else {
                                when (modal.classification.classification.name) {
                                    "GLOBAL_MODE" -> Color(0xFF064E3B)
                                    "LOCAL_MODE" -> Color(0xFF78350F)
                                    else -> Color(0xFF450A0A)
                                }
                            }
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "PHYSICS DOMAIN CLASSIFICATION",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color(0xFFE2E8F0),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = modal.classification.classification.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 11.sp,
                                        maxLines = 1,
softWrap = false,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = modal.classification.explanation,
                                color = Color.White,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }

                    // Baseline Comparison Card
                    val baseline = uiState.baselineComparison
                    if (baseline != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (baseline.comparisonSkippedLowQuality) Color(0xFF334155) // Neutral slate
                                    else if (baseline.isCalibrating) Color(0xFF1E293B) // Neutral calibrating slate/dark
                                    else if (baseline.isConfirmedAnomaly) Color(0xFF7F1D1D)
                                    else if (baseline.isAnomaly) Color(0xFF78350F)
                                    else Color(0xFF065F46)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "BASELINE LONGITUDINAL TRACKING",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFFE2E8F0),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (baseline.comparisonSkippedLowQuality) {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "⚠️ LOW QUALITY",
                                                color = Color(0xFFFCD34D),
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    } else if (baseline.isCalibrating) {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "CALIBRATING",
                                                color = Color(0xFFCBD5E1),
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    } else if (baseline.isConfirmedAnomaly) {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "⚠ CONFIRMED ANOMALY",
                                                color = Color(0xFFFCA5A5),
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    } else if (baseline.isAnomaly) {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "MONITORING",
                                                color = Color(0xFFFCD34D),
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    
                                    // Debug/Admin Action: Hidden Baseline Reset
                                    if (com.ronin.phoneshm.feature.analysis.BuildConfig.DEBUG) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "RESET",
                                            color = Color.White.copy(alpha = 0.1f), // Barely visible debug button
                                            fontSize = 9.sp,
                                            modifier = Modifier.clickable {
                                                showResetDialog = true
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = baseline.diagnosticSummary,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }

                    // Adaptive Persistence Tracking Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "ADAPTIVE PEAK PERSISTENCE TRACKING",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF94A3B8),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Sliding Window Persistence",
                                    color = Color.White,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "${(modal.persistence * 100).toInt()}%",
                                    color = Color(0xFF38BDF8),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { modal.persistence.toFloat() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp),
                                color = Color(0xFF38BDF8),
                                trackColor = Color(0xFF334155),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = String.format("Adaptive Matching Tolerance: ±%.3f Hz (max(1%% f₀, 2Δf)) across sequential 5s time windows.", modal.adaptiveToleranceHz),
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Dominant Frequencies Table
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "DOMINANT FREQUENCIES TABLE (ALL AXES)",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF94A3B8),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF334155), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "Rank", color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text(text = "Frequency (Hz)", color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(2f))
                                Text(text = "Power (m²/s⁴)", color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(2f))
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            modal.dominantPeaksTable.forEachIndexed { index, pair ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "#${index + 1}",
                                        color = if (index == 0) Color(0xFF38BDF8) else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = String.format("%.2f Hz", pair.first),
                                        color = if (index == 0) Color(0xFF38BDF8) else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(2f)
                                    )
                                    Text(
                                        text = String.format("%.4f", pair.second),
                                        color = Color(0xFF94A3B8),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(2f)
                                    )
                                }
                                if (index < modal.dominantPeaksTable.lastIndex) {
                                    HorizontalDivider(color = Color(0xFF334155), thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        viewModel.analyzeSessionFileOrDemo(
                            filePath = uiState.analyzedFilePath,
                            buildingType = uiState.buildingType,
                            floors = uiState.floors,
                            buildingHash = uiState.buildingHash
                        )
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(text = "Re-Analyze", color = Color(0xFF38BDF8), fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        val f0 = uiState.fundamentalFrequencyHz
                        val anomaly = uiState.baselineComparison?.isAnomaly ?: false
                        val quality = when {
                            uiState.qualityScorePct >= 85 -> "RESEARCH_GRADE"
                            uiState.qualityScorePct >= 70 -> "GOOD"
                            uiState.qualityScorePct >= 50 -> "FAIR"
                            else -> "UNRELIABLE"
                        }
                        val building = uiState.buildingType
                        onNavigateToReport(f0, anomaly, quality, building)
                    },
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(text = "Citizen Report ->", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Button(
                    onClick = onBackToMeasurement,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(text = "HUD", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
