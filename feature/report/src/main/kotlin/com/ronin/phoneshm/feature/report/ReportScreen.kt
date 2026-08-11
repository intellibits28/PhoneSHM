package com.ronin.phoneshm.feature.report

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.with
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ReportViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        // Top App Bar
        TopAppBar(
            title = {
                Text(
                    text = "Analysis Report",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF0F172A)
            )
        )

        // Mode Switcher (Tabs)
        TabRow(
            selectedTabIndex = if (uiState.mode == ReportMode.CITIZEN_SUMMARY) 0 else 1,
            containerColor = Color(0xFF0F172A),
            contentColor = Color.White,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[if (uiState.mode == ReportMode.CITIZEN_SUMMARY) 0 else 1]),
                    color = Color(0xFF38BDF8)
                )
            }
        ) {
            Tab(
                selected = uiState.mode == ReportMode.CITIZEN_SUMMARY,
                onClick = { viewModel.toggleMode(ReportMode.CITIZEN_SUMMARY) },
                text = { Text("Citizen Science", fontWeight = FontWeight.Bold) }
            )
            Tab(
                selected = uiState.mode == ReportMode.ENGINEER_ADVANCED,
                onClick = { viewModel.toggleMode(ReportMode.ENGINEER_ADVANCED) },
                text = { Text("Engineer Mode", fontWeight = FontWeight.Bold) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Animated Content based on Mode
        AnimatedContent(
            targetState = uiState.mode,
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) with fadeOut(animationSpec = tween(300))
            },
            label = "ReportModeAnimation"
        ) { mode ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (mode) {
                    ReportMode.CITIZEN_SUMMARY -> CitizenSummaryView(uiState)
                    ReportMode.ENGINEER_ADVANCED -> EngineerAdvancedView(uiState)
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun CitizenSummaryView(uiState: ReportUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // C6 Citizen Science Disclaimer (CRITICAL for Phase 8)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF451A18)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Disclaimer",
                    tint = Color(0xFFFCA5A5),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "PhoneSHM provides screening-level structural frequency monitoring. Results do not substitute for professional structural engineering inspection.",
                    color = Color(0xFFFCA5A5),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 18.sp
                )
            }
        }

        // Summary Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Building Health Summary",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                ResultRow("Building Profile:", uiState.buildingName.ifEmpty { "N/A" })
                ResultRow("Fundamental Frequency (f₀):", "${String.format("%.2f", uiState.f0Hz)} Hz")
                ResultRow("Measurement Quality:", uiState.qualityCategory)
                
                Spacer(modifier = Modifier.height(12.dp))
                Divider(color = Color(0xFF334155))
                Spacer(modifier = Modifier.height(12.dp))

                val statusText = if (uiState.anomalyDetected) {
                    "Anomaly Detected — Consult Professional"
                } else {
                    "Structural Profile Stable"
                }
                
                val statusColor = if (uiState.anomalyDetected) Color(0xFFEF4444) else Color(0xFF10B981)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
fun EngineerAdvancedView(uiState: ReportUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Engineer",
                        tint = Color(0xFF38BDF8)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Advanced Diagnostics",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                val telemetryStr = StringBuilder()
                telemetryStr.append("• Welch PSD Matrix (1024-bin FFT)\n")
                if (uiState.spectrum != null) {
                    telemetryStr.append("• Tri-Axial SNR Fusion: X=%.1f dB, Y=%.1f dB, Z=%.1f dB\n".format(
                        10 * kotlin.math.log10(uiState.spectrum.psdX.powerSpectralDensity.average().coerceAtLeast(1e-9)),
                        10 * kotlin.math.log10(uiState.spectrum.psdY.powerSpectralDensity.average().coerceAtLeast(1e-9)),
                        10 * kotlin.math.log10(uiState.spectrum.psdZ.powerSpectralDensity.average().coerceAtLeast(1e-9))
                    ))
                }
                if (uiState.sessionMeta != null) {
                    telemetryStr.append("• Clock Drift: %.1f PPM, Jitter: %.2f ms\n".format(
                        uiState.sessionMeta.clockDriftPpm,
                        uiState.sessionMeta.sampleJitterStdMs
                    ))
                }
                telemetryStr.append("• Acoustic Event Classification Logs (Coming Soon)\n")
                telemetryStr.append("• Welford Baseline Shift Confidence Intervals (Shift: %.1f%%)".format(uiState.welfordBaselineShiftPct * 100))

                Text(
                    text = telemetryStr.toString(),
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(16.dp))
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    if (uiState.spectrum != null) {
                        PsdChartView(spectrum = uiState.spectrum, modifier = Modifier.fillMaxSize())
                    } else {
                        Text(
                            text = "[ Interactive PSD Chart Placeholder - Data Unavailable ]",
                            color = Color(0xFF475569),
                            fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PsdChartView(spectrum: com.ronin.phoneshm.core.dsp.MultiAxisSpectrumResult, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        val xColor = Color(0xFF2196F3)
        val yColor = Color(0xFF4CAF50)
        val zColor = Color(0xFFFF9800)
        val magColor = Color.White

        // Find max power for scaling
        var maxPower = 1e-9f
        spectrum.psdMagnitude.powerSpectralDensity.forEach { if (it > maxPower) maxPower = it }
        val logMaxPower = kotlin.math.log10(maxPower.toDouble())
        val logMinPower = logMaxPower - 4.0 // 4 decades

        fun getY(power: Float): Float {
            val logP = kotlin.math.log10(power.coerceAtLeast(1e-9f).toDouble())
            val normalized = ((logP - logMinPower) / (logMaxPower - logMinPower)).toFloat().coerceIn(0f, 1f)
            return height - (normalized * height)
        }

        // Draw grids
        val numGridLines = 5
        for (i in 0..numGridLines) {
            val yPos = height * i / numGridLines
            drawLine(
                color = Color(0xFF334155),
                start = androidx.compose.ui.geometry.Offset(0f, yPos),
                end = androidx.compose.ui.geometry.Offset(width, yPos),
                strokeWidth = 1f
            )
        }

        val freqs = spectrum.psdMagnitude.frequencies
        if (freqs.isEmpty()) return@Canvas

        val maxFreq = freqs.last().coerceAtMost(50f) // Show up to 50Hz
        val maxIndex = freqs.indexOfFirst { it > maxFreq }.takeIf { it > 0 } ?: (freqs.size - 1)

        val stepX = width / maxIndex.coerceAtLeast(1)

        fun drawAxisLine(psd: FloatArray, color: Color, stroke: Float) {
            val path = androidx.compose.ui.graphics.Path()
            path.moveTo(0f, getY(psd[0]))
            for (i in 1..maxIndex) {
                if (i < psd.size) {
                    path.lineTo(i * stepX, getY(psd[i]))
                }
            }
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
        }

        drawAxisLine(spectrum.psdX.powerSpectralDensity, xColor.copy(alpha = 0.5f), 2f)
        drawAxisLine(spectrum.psdY.powerSpectralDensity, yColor.copy(alpha = 0.5f), 2f)
        drawAxisLine(spectrum.psdZ.powerSpectralDensity, zColor.copy(alpha = 0.5f), 2f)
        drawAxisLine(spectrum.psdMagnitude.powerSpectralDensity, magColor, 4f)
    }
}

@Composable
fun ResultRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color(0xFF94A3B8), fontSize = 14.sp)
        Text(text = value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
