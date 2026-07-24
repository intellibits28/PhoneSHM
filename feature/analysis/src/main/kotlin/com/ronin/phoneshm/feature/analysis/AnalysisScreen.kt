package com.ronin.phoneshm.feature.analysis

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AnalysisScreen(
    viewModel: AnalysisViewModel,
    onBackToMeasurement: () -> Unit = {},
    onNavigateToReport: (f0: Double, anomaly: Boolean, quality: String, building: String) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

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
                                Text(
                                    text = String.format("%.2f", modal.fundamentalFrequencyHz),
                                    style = MaterialTheme.typography.displayMedium,
                                    color = Color(0xFF38BDF8),
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Hz",
                                    style = MaterialTheme.typography.titleLarge,
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
                        }
                    }

                    // Physics Classification Card
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = when (modal.classification.classification.name) {
                                "GLOBAL_MODE" -> Color(0xFF064E3B)
                                "LOCAL_MODE" -> Color(0xFF78350F)
                                else -> Color(0xFF450A0A)
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
                                containerColor = if (baseline.isConfirmedAnomaly) Color(0xFF7F1D1D)
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
                                    if (baseline.isConfirmedAnomaly) {
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
