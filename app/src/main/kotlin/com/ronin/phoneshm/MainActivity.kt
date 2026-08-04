package com.ronin.phoneshm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
        val profileRepo = ProfileRepositoryImpl(db.profileDao())
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
                return com.ronin.phoneshm.feature.measurement.MeasurementViewModel(sensorEngine) as T
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PhoneShmAppHost(
                        onboardingFactory = onboardingFactory,
                        measurementFactory = measurementFactory
                    )
                }
            }
        }
    }
}

@Composable
fun PhoneShmAppHost(
    onboardingFactory: ViewModelProvider.Factory,
    measurementFactory: ViewModelProvider.Factory
) {
    var currentScreen by remember { mutableStateOf("ONBOARDING") }
    var activeBuildingId by remember { mutableStateOf("") }
    var activeMeasurementId by remember { mutableStateOf("") }

    val onboardingViewModel: OnboardingViewModel = viewModel(factory = onboardingFactory)
    val measurementViewModel: com.ronin.phoneshm.feature.measurement.MeasurementViewModel = viewModel(factory = measurementFactory)
    val analysisViewModel: AnalysisViewModel = viewModel()
    val reportViewModel: ReportViewModel = viewModel()

    if (currentScreen == "ONBOARDING") {
        OnboardingScreen(
            viewModel = onboardingViewModel,
            onFinished = { bId, mId ->
                activeBuildingId = bId
                activeMeasurementId = mId
                currentScreen = "MEASUREMENT"
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
            onBackToMeasurement = { currentScreen = "MEASUREMENT" },
            onNavigateToReport = { f0, anomaly, quality, building ->
                reportViewModel.setReportSummary(
                    building = building,
                    quality = quality,
                    f0 = f0,
                    anomaly = anomaly
                )
                currentScreen = "REPORT"
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
                        Text(
                            text = "PhoneSHM Active Session HUD",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Building ID: $activeBuildingId | Profile: $activeMeasurementId",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
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
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { currentScreen = "ONBOARDING" }) {
                    Text("<- Back to Profile Wizard")
                }
            }
        }
    }
}
