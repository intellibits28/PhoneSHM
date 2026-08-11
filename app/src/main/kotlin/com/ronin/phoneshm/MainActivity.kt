package com.ronin.phoneshm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ronin.phoneshm.core.database.PhoneShmDatabase
import com.ronin.phoneshm.core.database.repository.ProfileRepositoryImpl
import com.ronin.phoneshm.core.device.AndroidDeviceCapabilityEngine
import com.ronin.phoneshm.feature.analysis.AnalysisScreen
import com.ronin.phoneshm.feature.analysis.AnalysisViewModel
import com.ronin.phoneshm.feature.measurement.MeasurementScreen
import com.ronin.phoneshm.feature.measurement.SessionHistoryScreen
import com.ronin.phoneshm.feature.onboarding.OnboardingScreen
import com.ronin.phoneshm.feature.onboarding.OnboardingViewModel
import com.ronin.phoneshm.feature.report.ReportScreen
import com.ronin.phoneshm.feature.report.ReportViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestPermissionLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
            ) {
                // Permission granted, can fetch real GPS coordinates
            }
        }
        val permissionsToRequest = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
            permissionsToRequest.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())

        val db = PhoneShmDatabase.getDatabase(applicationContext)
        val profileRepo = ProfileRepositoryImpl(db.profileDao(), db.baselineDao(), applicationContext)
        val deviceEngine = AndroidDeviceCapabilityEngine(applicationContext)
        val locationResolver = com.ronin.phoneshm.core.location.AndroidLocationResolver(applicationContext)
        val storageEngine = com.ronin.phoneshm.core.storage.DefaultRawSampleStorageEngine(applicationContext.filesDir)
        val sensorEngine = com.ronin.phoneshm.core.sensor.AndroidVibrationSensorEngine(applicationContext, storageEngine, deviceEngine)

        val onboardingFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return OnboardingViewModel(profileRepo, deviceEngine, locationResolver) as T
            }
        }

        val measurementFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return com.ronin.phoneshm.feature.measurement.MeasurementViewModel(sensorEngine, profileRepo, deviceEngine) as T
            }
        }

        val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val initialBuildingId = prefs.getString("active_building_id", "") ?: ""
        val initialMeasurementId = prefs.getString("active_measurement_id", "") ?: ""
        val initialScreen = if (initialBuildingId.isNotEmpty()) "MEASUREMENT" else "ONBOARDING"

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PhoneShmAppHost(
                        onboardingFactory = onboardingFactory,
                        measurementFactory = measurementFactory,
                        initialScreen = initialScreen,
                        initialBuildingId = initialBuildingId,
                        initialMeasurementId = initialMeasurementId,
                        onSessionUpdated = { bId, mId ->
                            prefs.edit()
                                .putString("active_building_id", bId)
                                .putString("active_measurement_id", mId)
                                .apply()
                        }
                    )
                }
            }
        }
    }
}

fun resolveStartupScreen(
    persistedBuildingId: String?,
    buildingExistsInRoom: Boolean,
    initialScreen: String = "LOADING"
): String {
    return if (!persistedBuildingId.isNullOrEmpty() && buildingExistsInRoom) {
        if (initialScreen == "LOADING") "MEASUREMENT" else initialScreen
    } else {
        "ONBOARDING"
    }
}

@Composable
fun PhoneShmAppHost(
    onboardingFactory: ViewModelProvider.Factory,
    measurementFactory: ViewModelProvider.Factory,
    initialScreen: String = "ONBOARDING",
    initialBuildingId: String = "",
    initialMeasurementId: String = "",
    onSessionUpdated: (String, String) -> Unit = { _, _ -> }
) {
    var currentScreen by remember { mutableStateOf(if (initialBuildingId.isNotEmpty()) "LOADING" else "ONBOARDING") }
    var activeBuildingId by remember { mutableStateOf(initialBuildingId) }
    var activeMeasurementId by remember { mutableStateOf(initialMeasurementId) }

    val onboardingViewModel: OnboardingViewModel = viewModel(factory = onboardingFactory)
    val measurementViewModel: com.ronin.phoneshm.feature.measurement.MeasurementViewModel = viewModel(factory = measurementFactory)
    val analysisViewModel: AnalysisViewModel = viewModel()
    val reportViewModel: ReportViewModel = viewModel()

    var showSwitcherDialog by remember { mutableStateOf(false) }
    var showLocationDialog by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(activeBuildingId) {
        if (activeBuildingId.isNotEmpty()) {
            val exists = onboardingViewModel.checkBuildingExists(activeBuildingId)
            
            if (!exists) {
                // If it doesn't exist, it means it was deleted or invalid. Try to find a fallback.
                val allProfiles = onboardingViewModel.getAllBuildingProfiles().first()
                if (allProfiles.isNotEmpty()) {
                    val fallback = allProfiles.first()
                    val measurementProfiles = onboardingViewModel.getMeasurementProfilesForBuilding(fallback.buildingHash)
                    val mId = measurementProfiles.firstOrNull()?.id ?: ""
                    
                    activeBuildingId = fallback.buildingHash
                    activeMeasurementId = mId
                    onSessionUpdated(activeBuildingId, mId)
                    currentScreen = "MEASUREMENT"
                } else {
                    currentScreen = "ONBOARDING"
                    onSessionUpdated("", "")
                    activeBuildingId = ""
                    activeMeasurementId = ""
                }
            } else {
                // If it exists, and we are currently LOADING, transition to MEASUREMENT
                if (currentScreen == "LOADING") {
                    currentScreen = "MEASUREMENT"
                }
            }
        } else {
            // Also if activeBuildingId is empty (e.g. just deleted the last profile), try to auto-load a profile
            val allProfiles = onboardingViewModel.getAllBuildingProfiles().first()
            if (allProfiles.isNotEmpty()) {
                val fallback = allProfiles.first()
                val measurementProfiles = onboardingViewModel.getMeasurementProfilesForBuilding(fallback.buildingHash)
                val mId = measurementProfiles.firstOrNull()?.id ?: ""
                
                activeBuildingId = fallback.buildingHash
                activeMeasurementId = mId
                onSessionUpdated(activeBuildingId, mId)
                currentScreen = "MEASUREMENT"
            } else {
                currentScreen = "ONBOARDING"
            }
        }
    }

    if (currentScreen == "LOADING") {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
    } else if (currentScreen == "ONBOARDING") {
        OnboardingScreen(
            viewModel = onboardingViewModel,
            onFinished = { bId, mId ->
                activeBuildingId = bId
                activeMeasurementId = mId
                onSessionUpdated(bId, mId)
                currentScreen = "MEASUREMENT"
            },
            onBuildingDeleted = {
                activeBuildingId = ""
                activeMeasurementId = ""
                onSessionUpdated("", "")
            }
        )
    } else if (currentScreen == "REPORT") {
        ReportScreen(
            onBack = { currentScreen = "ANALYSIS" },
            viewModel = reportViewModel
        )
    } else if (currentScreen == "ANALYSIS") {
        AnalysisScreen(
            viewModel = analysisViewModel,
            buildingHash = activeBuildingId,
            onBackToMeasurement = { currentScreen = "MEASUREMENT" },
            onNavigateToReport = { state ->
                val quality = when {
                    state.qualityReport == null -> "UNAVAILABLE (Missing Metadata)"
                    state.qualityScorePct >= 85 -> "RESEARCH_GRADE"
                    state.qualityScorePct >= 70 -> "GOOD"
                    state.qualityScorePct >= 50 -> "FAIR"
                    else -> "UNRELIABLE"
                }
                reportViewModel.setReportSummary(
                    building = state.buildingType,
                    quality = quality,
                    f0 = state.fundamentalFrequencyHz,
                    anomaly = state.baselineComparison?.isAnomaly ?: false
                )
                reportViewModel.setAdvancedDiagnostics(
                    spectrum = state.spectrum,
                    sessionMeta = state.sessionMeta,
                    deviceReport = state.deviceReport,
                    welfordBaselineShiftPct = state.baselineComparison?.baselineShiftPct ?: 0.0
                )
                currentScreen = "REPORT"
            }
        )
    } else if (currentScreen == "HISTORY") {
        SessionHistoryScreen(
            measurementProfileId = activeMeasurementId,
            onNavigateBack = { currentScreen = "MEASUREMENT" },
            onSessionSelected = { fileUri ->
                val onboardState = onboardingViewModel.state.value
                val bType = onboardState.buildingType
                val bFloors = onboardState.floors.toIntOrNull() ?: 3
                val bHash = onboardState.resolvedBuildingHash ?: activeBuildingId
                analysisViewModel.analyzeSessionFileOrDemo(
                    filePath = fileUri,
                    buildingType = bType,
                    floors = bFloors,
                    buildingHash = bHash
                )
                currentScreen = "ANALYSIS"
            }
        )
    } else {
        Scaffold(
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PhoneSHM Session HUD",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        onboardingViewModel.loadProfileForEditing(activeBuildingId, activeMeasurementId)
                                        currentScreen = "ONBOARDING"
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text("Edit")
                                }
                                Button(
                                    onClick = { showLocationDialog = true },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text("Locations")
                                }
                                Button(
                                    onClick = { showSwitcherDialog = true },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text("Switch")
                                }
                            }
                        }
                        Text(
                            text = "Building ID: ${activeBuildingId.take(8)}.. | Session: $activeMeasurementId",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                MeasurementScreen(
                    viewModel = measurementViewModel,
                    activeBuildingHash = activeBuildingId,
                    activeMeasurementId = activeMeasurementId,
                    modifier = Modifier.weight(1f),
                    onNavigateToAnalysis = { fileUri ->
                        val onboardState = onboardingViewModel.state.value
                        val bType = onboardState.buildingType
                        val bFloors = onboardState.floors.toIntOrNull() ?: 3
                        val bHash = onboardState.resolvedBuildingHash ?: activeBuildingId
                        analysisViewModel.analyzeSessionFileOrDemo(
                            filePath = fileUri,
                            buildingType = bType,
                            floors = bFloors,
                            buildingHash = bHash
                        )
                        currentScreen = "ANALYSIS"
                    },
                    onNavigateToHistory = {
                        currentScreen = "HISTORY"
                    }
                )
            }
        }
        
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        if (showSwitcherDialog) {
            val profiles by onboardingViewModel.getAllBuildingProfiles().collectAsState(initial = emptyList())
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSwitcherDialog = false },
                title = { Text("Select Building Profile") },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(profiles.size) { index ->
                            val profile = profiles[index]
                            androidx.compose.material3.Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        activeBuildingId = profile.buildingHash
                                        // Auto-select the first measurement profile when switching buildings
                                        scope.launch {
                                            val measurementProfiles = onboardingViewModel.getMeasurementProfilesForBuilding(activeBuildingId)
                                            val mId = measurementProfiles.firstOrNull()?.id ?: ""
                                            activeMeasurementId = mId
                                            onSessionUpdated(activeBuildingId, activeMeasurementId)
                                            showSwitcherDialog = false
                                        }
                                    },
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = if (profile.buildingHash == activeBuildingId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(profile.displayName, fontWeight = FontWeight.Bold)
                                    Text("${profile.buildingType} - ${profile.buildingHash}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { 
                        showSwitcherDialog = false
                        onboardingViewModel.resetState()
                        currentScreen = "ONBOARDING" 
                    }) {
                        Text("New Building")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showSwitcherDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showLocationDialog) {
            var measurementProfiles by remember { mutableStateOf<List<com.ronin.phoneshm.core.database.model.MeasurementProfile>>(emptyList()) }
            androidx.compose.runtime.LaunchedEffect(activeBuildingId) {
                measurementProfiles = onboardingViewModel.getMeasurementProfilesForBuilding(activeBuildingId)
            }

            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showLocationDialog = false },
                title = { Text("Select Measurement Location") },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn {
                        items(measurementProfiles.size) { index ->
                            val profile = measurementProfiles[index]
                            androidx.compose.material3.Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        activeMeasurementId = profile.id
                                        onSessionUpdated(activeBuildingId, activeMeasurementId)
                                        showLocationDialog = false
                                    },
                                colors = androidx.compose.material3.CardDefaults.cardColors(
                                    containerColor = if (profile.id == activeMeasurementId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Floor ${profile.floorLevel} · ${profile.locationType} · ${profile.placement}", fontWeight = FontWeight.Bold)
                                    Text("ID: ${profile.id}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { 
                        showLocationDialog = false
                        onboardingViewModel.startNewMeasurementLocation(activeBuildingId)
                        currentScreen = "ONBOARDING" 
                    }) {
                        Text("Add New Location")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showLocationDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
