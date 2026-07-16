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
import com.ronin.phoneshm.feature.measurement.MeasurementScreen
import com.ronin.phoneshm.feature.onboarding.OnboardingScreen
import com.ronin.phoneshm.feature.onboarding.OnboardingViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = PhoneShmDatabase.getDatabase(applicationContext)
        val profileRepo = ProfileRepositoryImpl(db.profileDao())
        val deviceEngine = AndroidDeviceCapabilityEngine(applicationContext)

        val onboardingFactory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return OnboardingViewModel(profileRepo, deviceEngine) as T
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PhoneShmAppHost(onboardingFactory = onboardingFactory)
                }
            }
        }
    }
}

@Composable
fun PhoneShmAppHost(onboardingFactory: ViewModelProvider.Factory) {
    var currentScreen by remember { mutableStateOf("ONBOARDING") }
    var activeBuildingId by remember { mutableStateOf("") }
    var activeMeasurementId by remember { mutableStateOf("") }

    val onboardingViewModel: OnboardingViewModel = viewModel(factory = onboardingFactory)

    if (currentScreen == "ONBOARDING") {
        OnboardingScreen(
            viewModel = onboardingViewModel,
            onFinished = { bId, mId ->
                activeBuildingId = bId
                activeMeasurementId = mId
                currentScreen = "MEASUREMENT"
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
                MeasurementScreen(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { currentScreen = "ONBOARDING" }) {
                    Text("<- Back to Profile Wizard")
                }
            }
        }
    }
}
