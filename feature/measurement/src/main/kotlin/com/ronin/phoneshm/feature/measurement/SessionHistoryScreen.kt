package com.ronin.phoneshm.feature.measurement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.ronin.phoneshm.core.sensor.MeasurementSessionMetadata
import com.ronin.phoneshm.core.sensor.SessionMetadataJsonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionHistoryScreen(
    measurementProfileId: String,
    buildingHash: String = "",
    onNavigateBack: () -> Unit,
    onSessionSelected: (String) -> Unit // Passes the .bin file path
) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<Pair<MeasurementSessionMetadata, Boolean>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(measurementProfileId, buildingHash) {
        withContext(Dispatchers.IO) {
            val directoriesToScan = listOfNotNull(
                context.filesDir,
                File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                    "PhoneSHM"
                ).takeIf { it.exists() && it.canRead() }
            )
            val loadedSessions = mutableListOf<Pair<MeasurementSessionMetadata, Boolean>>()
            val seenSessionIds = mutableSetOf<String>()

            directoriesToScan.forEach { dir ->
                val metaFiles = dir.listFiles { _, name -> name.endsWith(".meta.json") }
                metaFiles?.forEach { file ->
                    try {
                        val content = file.readText()
                        val decoded = SessionMetadataJsonCodec.decode(content)
                        if (decoded != null && seenSessionIds.add(decoded.first.sessionId)) {
                            val sMeta = decoded.first
                            val matchesBuilding = buildingHash.isEmpty() || sMeta.buildingHash == null || sMeta.buildingHash == buildingHash
                            val matchesProfile = measurementProfileId.isEmpty() ||
                                sMeta.measurementProfileId == measurementProfileId ||
                                sMeta.measurementProfileId == "ambient_baseline_continuous" ||
                                sMeta.measurementProfileId == "heel_drop_floor_check" ||
                                sMeta.measurementProfileId == "demo_sanity_preview"

                            if (matchesBuilding && matchesProfile) {
                                val isApproximate = sMeta.recordedAtEpochMs == null
                                val meta = if (isApproximate) {
                                    sMeta.copy(recordedAtEpochMs = file.lastModified())
                                } else {
                                    sMeta
                                }
                                loadedSessions.add(Pair(meta, isApproximate))
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore parse errors
                    }
                }
            }

            // Sort newest first
            sessions = loadedSessions.sortedByDescending { it.first.recordedAtEpochMs ?: 0L }
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Past Sessions") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No past sessions found for this location.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions) { (session, isApprox) ->
                    SessionHistoryCard(session = session, isApproximate = isApprox, onClick = {
                        val binFile = File(session.rawStorageFileUri)
                        val resolvedBinPath = if (binFile.exists()) {
                            binFile.absolutePath
                        } else {
                            val downloadsBin = File(
                                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                                "PhoneSHM/${binFile.name}"
                            )
                            if (downloadsBin.exists()) downloadsBin.absolutePath else binFile.absolutePath
                        }
                        onSessionSelected(resolvedBinPath)
                    })
                }
            }
        }
    }
}

@Composable
fun SessionHistoryCard(session: MeasurementSessionMetadata, isApproximate: Boolean, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()) }
    val dateStr = session.recordedAtEpochMs?.let { dateFormat.format(Date(it)) } ?: "Unknown Date"
    val finalDateStr = if (isApproximate) "$dateStr (~Approx)" else dateStr

    val profileTag = when (session.measurementProfileId) {
        "ambient_baseline_continuous" -> "🌊 Ambient Baseline (300s Continuous)"
        "heel_drop_floor_check" -> "⚡ Impulse Check (30s Heel-Drop)"
        "demo_sanity_preview" -> "⏱️ 10s Live Preview"
        else -> {
            val floorStr = session.measurementFloorLevel?.let { "Floor $it" } ?: "Floor N/A"
            val locStr = session.locationType ?: "Adaptive SHM"
            "🏢 $floorStr • $locStr"
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = finalDateStr,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = profileTag,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Duration: ${session.targetDurationSeconds}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            // Quality Gate Status
            if (session.qualityGatePassed == true) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Passed",
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(28.dp)
                )
            } else if (session.qualityGatePassed == false) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Failed",
                    tint = Color.Red,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                Text("Recorded", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}
