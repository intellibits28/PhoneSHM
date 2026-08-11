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
    onNavigateBack: () -> Unit,
    onSessionSelected: (String) -> Unit // Passes the .bin file path
) {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf<List<Pair<MeasurementSessionMetadata, Boolean>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(measurementProfileId) {
        withContext(Dispatchers.IO) {
            val filesDir = context.filesDir
            val metaFiles = filesDir.listFiles { _, name -> name.endsWith(".meta.json") }
            val loadedSessions = mutableListOf<Pair<MeasurementSessionMetadata, Boolean>>()
            
            metaFiles?.forEach { file ->
                try {
                    val content = file.readText()
                    val decoded = SessionMetadataJsonCodec.decode(content)
                    if (decoded != null && decoded.first.measurementProfileId == measurementProfileId) {
                        // Fallback to lastModified if recordedAtEpochMs is null
                        val isApproximate = decoded.first.recordedAtEpochMs == null
                        val meta = if (isApproximate) {
                            decoded.first.copy(recordedAtEpochMs = file.lastModified())
                        } else {
                            decoded.first
                        }
                        loadedSessions.add(Pair(meta, isApproximate))
                    }
                } catch (e: Exception) {
                    // Ignore parse errors
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
                        val binPath = session.rawStorageFileUri
                        onSessionSelected(binPath)
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
                Spacer(modifier = Modifier.height(4.dp))
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
                Text("Pending", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}
