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
                
                Text(
                    text = "• Welch PSD Matrix (1024-bin FFT)\n" +
                           "• Tri-Axial Correlation & SNR Fusion\n" +
                           "• Clock Drift & Jitter Telemetry\n" +
                           "• Acoustic Event Classification Logs\n" +
                           "• Welford Baseline Shift Confidence Intervals",
                    color = Color(0xFF94A3B8),
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(16.dp))
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(Color(0xFF0F172A), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "[ Interactive PSD Chart Placeholder ]",
                        color = Color(0xFF475569),
                        fontSize = 13.sp
                    )
                }
            }
        }
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
