package com.ronin.phoneshm.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ronin.phoneshm.core.location.PrivacyLevel

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        if (state.deviceReport == null && !state.isInspectingDevice) {
            viewModel.inspectDevice()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "PhoneSHM Phase 2 Wizard",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Citizen-Scale Structural Health Monitoring Setup (Step ${state.step} of 5)",
            style = MaterialTheme.typography.bodyMedium
        )

        state.errorMessage?.let { err ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        when (state.step) {
            1 -> StepOneHardwareEvaluation(state, viewModel)
            2 -> StepTwoBuildingTypology(state, viewModel)
            3 -> StepThreeLocationPrivacy(state, viewModel)
            4 -> StepFourPlacementSetup(state, viewModel)
            5 -> StepFiveSummary(state, onFinished)
        }
    }
}

@Composable
private fun StepOneHardwareEvaluation(
    state: OnboardingState,
    viewModel: OnboardingViewModel
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "1. Sensor & Device Verification", style = MaterialTheme.typography.titleLarge)

            state.deviceReport?.let { report ->
                Text(text = "Model: ${report.deviceModel}")
                Text(text = "Sensor: ${report.sensorVendor}")
                Text(text = "Max Sample Rate: ${report.maxSupportedSampleRateHz} Hz")
                Text(text = "Estimated Noise Floor: ${report.estimatedNoiseFloorMg} mg")
                Text(text = "Quality Tier: ${report.qualityTier.name}", fontWeight = FontWeight.SemiBold)
            } ?: Text(text = "Inspecting native accelerometer capabilities...")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.inspectDevice() }) {
                    Text("Re-Check Sensor")
                }
                Button(onClick = { viewModel.runCalibration() }) {
                    Text(if (state.isDeviceCalibrated) "Recalibrate Zero-Velocity" else "Run Zero-Velocity Calibration")
                }
            }

            if (state.isDeviceCalibrated) {
                Text(text = "Calibration Bias [X, Y, Z]: [${state.calibrationBias[0]}, ${state.calibrationBias[1]}, ${state.calibrationBias[2]}]")
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { viewModel.setStep(2) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Next: Building Typology ->")
            }
        }
    }
}

@Composable
private fun StepTwoBuildingTypology(
    state: OnboardingState,
    viewModel: OnboardingViewModel
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    val buildingTypes = listOf(
        "RC Frame (Concrete Frame)",
        "RC Shear Wall (Concrete with Shear Wall)",
        "Steel Frame",
        "Steel Braced Frame",
        "Masonry (Brick/Block Wall)",
        "Timber / Wood",
        "Composite (Steel + Concrete)",
        "Others / Unknown"
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "2. Building Typology Profiler", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = state.buildingName,
                onValueChange = { viewModel.updateBuildingName(it) },
                label = { Text("Building Name / Identifier") },
                modifier = Modifier.fillMaxWidth()
            )

            // Dropdown Selector for Building Type
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.buildingType,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Building Structural Typology") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { dropdownExpanded = true }
                )
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    buildingTypes.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type) },
                            onClick = {
                                viewModel.updateBuildingType(type)
                                dropdownExpanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.floors,
                onValueChange = { viewModel.updateFloors(it) },
                label = { Text("Total Floors") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.constructionYear,
                onValueChange = { viewModel.updateConstructionYear(it) },
                label = { Text("Construction Year") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.material,
                onValueChange = { viewModel.updateMaterial(it) },
                label = { Text("Primary Structural Material") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { viewModel.setStep(1) }) {
                    Text("<- Back")
                }
                Button(
                    onClick = { viewModel.setStep(3) },
                    enabled = state.buildingName.isNotBlank()
                ) {
                    Text("Next: Location & Privacy ->")
                }
            }
        }
    }
}

@Composable
private fun StepThreeLocationPrivacy(
    state: OnboardingState,
    viewModel: OnboardingViewModel
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "3. Spatial Location & Privacy Tiers", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Select how spatial coordinates are clustered and aggregated for global citizen-science monitoring.",
                style = MaterialTheme.typography.bodySmall
            )

            PrivacyLevel.values().forEach { level ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewModel.updatePrivacyLevel(level) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = state.privacyLevel == level,
                        onClick = { viewModel.updatePrivacyLevel(level) }
                    )
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(text = level.name, fontWeight = FontWeight.Bold)
                        val desc = when (level) {
                            PrivacyLevel.EXACT_LOCATION -> "Exact GPS coordinates for engineering research precision."
                            PrivacyLevel.APPROXIMATE_LOCATION -> "Truncated to 3 decimal places (~110 meters accuracy) for spatial clustering privacy."
                            PrivacyLevel.LOCAL_ONLY -> "Completely anonymous. Coordinates are zeroed, no spatial correlation."
                        }
                        Text(text = desc, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Button(
                onClick = { viewModel.resolveCurrentLocation() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Resolve coordinates & spatial grid")
            }

            if (state.resolvedLatitude != null && state.resolvedLongitude != null) {
                Text(text = "Coordinates: [Lat: ${String.format("%.6f", state.resolvedLatitude)}, Lon: ${String.format("%.6f", state.resolvedLongitude)}]")
                Text(text = "Spatial Building Hash: ${state.resolvedBuildingHash}", fontWeight = FontWeight.SemiBold)

                // Map Pin manual fine-tuning controls
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "GPS Fine-Tuning: Adjust coordinates (±0.0001 deg ≈ 10m) to correctly position the building marker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            OutlinedButton(
                                onClick = { viewModel.adjustCoordinates(0.0001, 0.0) },
                                modifier = Modifier.size(50.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) {
                                Text("▲")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                OutlinedButton(
                                    onClick = { viewModel.adjustCoordinates(0.0, -0.0001) },
                                    modifier = Modifier.size(50.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                ) {
                                    Text("◀")
                                }
                                Spacer(modifier = Modifier.width(36.dp))
                                OutlinedButton(
                                    onClick = { viewModel.adjustCoordinates(0.0, 0.0001) },
                                    modifier = Modifier.size(50.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                ) {
                                    Text("▶")
                                }
                            }
                            OutlinedButton(
                                onClick = { viewModel.adjustCoordinates(-0.0001, 0.0) },
                                modifier = Modifier.size(50.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) {
                                Text("▼")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Spacer(modifier = Modifier.height(8.dp))

                var lastCoordinates by remember { mutableStateOf(Pair(0.0, 0.0)) }

                // Interactive Live Leaflet OpenStreetMap WebView Map
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                ) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                                    android.webkit.WebView.setWebContentsDebuggingEnabled(true)
                                }
                                webViewClient = object : android.webkit.WebViewClient() {
                                    override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        android.util.Log.d("SHM_MAP_LOG", "Page started loading: ${url}")
                                    }

                                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                        android.util.Log.d("SHM_MAP_LOG", "Page finished loading: ${url}")
                                    }

                                    override fun onReceivedError(
                                        view: android.webkit.WebView?,
                                        request: android.webkit.WebResourceRequest?,
                                        error: android.webkit.WebResourceError?
                                    ) {
                                        android.util.Log.e("SHM_MAP_LOG", "Resource Error: ${request?.url} -> ${error?.description} (${error?.errorCode})")
                                    }

                                    override fun onReceivedSslError(
                                        view: android.webkit.WebView?,
                                        handler: android.webkit.SslErrorHandler?,
                                        error: android.net.http.SslError?
                                    ) {
                                        android.util.Log.e("SHM_MAP_LOG", "SSL Error: ${error?.toString()}")
                                        handler?.proceed()
                                    }
                                }
                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                                        consoleMessage?.let {
                                            android.util.Log.d(
                                                "SHM_MAP_JS",
                                                "${it.message()} -- ${it.sourceId()}:${it.lineNumber()}"
                                            )
                                        }
                                        return true
                                    }
                                }
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                }
                            }
                        },
                        update = { webView ->
                            val current = Pair(state.resolvedLatitude ?: 0.0, state.resolvedLongitude ?: 0.0)
                            if (current != lastCoordinates && state.resolvedLatitude != null && state.resolvedLongitude != null) {
                                lastCoordinates = current
                                val lat = current.first
                                val lon = current.second
                                val html = """
                                    <!DOCTYPE html>
                                    <html>
                                    <head>
                                        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                                        <link rel="stylesheet" href="leaflet.min.css" />
                                        <script src="leaflet.min.js"></script>
                                        <style>
                                            body, html, #map { margin: 0; padding: 0; height: 100%; width: 100%; background-color: #f4f6fa; }
                                            .custom-svg-marker { display: flex; justify-content: center; align-items: center; }
                                        </style>
                                    </head>
                                    <body>
                                        <div id="map"></div>
                                        <script>
                                            console.log("Inline script started with local Leaflet assets");
                                            document.addEventListener("DOMContentLoaded", function() {
                                                console.log("DOMContentLoaded triggered, checking Leaflet");
                                                if (typeof L !== 'undefined') {
                                                    var map = L.map('map').setView([$lat, $lon], 16);
                                                    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                                                        maxZoom: 19,
                                                        attribution: '© OpenStreetMap'
                                                    }).addTo(map);
                                                    
                                                    // Resolve hidden container zero-size leaflet bug
                                                    setTimeout(function() {
                                                        map.invalidateSize();
                                                        console.log("Map size invalidated/recalculated");
                                                    }, 250);

                                                    var customIcon = L.divIcon({
                                                        html: '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="M12 2C8.13 2 5 5.13 5 9C5 14.25 12 22 12 22C12 22 19 14.25 19 9C19 5.13 15.87 2 12 2ZM12 11.5C10.62 11.5 9.5 10.38 9.5 9C9.5 7.62 10.62 6.5 12 6.5C13.38 6.5 14.5 7.62 14.5 9C14.5 10.38 13.38 11.5 12 11.5Z" fill="#E53935"/></svg>',
                                                        className: 'custom-svg-marker',
                                                        iconSize: [24, 24],
                                                        iconAnchor: [12, 22]
                                                    });

                                                    var marker = L.marker([$lat, $lon], { icon: customIcon, draggable: false }).addTo(map);
                                                    
                                                    window.updateMarker = function(newLat, newLon) {
                                                        marker.setLatLng([newLat, newLon]);
                                                        map.setView([newLat, newLon]);
                                                    };
                                                    console.log("Leaflet Map initialized successfully");
                                                } else {
                                                    console.error("Leaflet library L is undefined!");
                                                }
                                            });
                                        </script>
                                    </body>
                                    </html>
                                """.trimIndent()
                                webView.loadDataWithBaseURL("file:///android_asset/leaflet/", html, "text/html", "UTF-8", null)
                            } else if (state.resolvedLatitude != null && state.resolvedLongitude != null) {
                                webView.loadUrl("javascript:if(typeof updateMarker !== 'undefined') { updateMarker(${state.resolvedLatitude}, ${state.resolvedLongitude}); }")
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { viewModel.setStep(2) }) {
                    Text("<- Back")
                }
                Button(onClick = { viewModel.setStep(4) }) {
                    Text("Next: Placement Setup ->")
                }
            }
        }
    }
}

@Composable
private fun StepFourPlacementSetup(
    state: OnboardingState,
    viewModel: OnboardingViewModel
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "4. Measurement Placement Setup", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = state.floorLevel,
                onValueChange = { viewModel.updateFloorLevel(it) },
                label = { Text("Current Measurement Floor Level") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.surfaceType,
                onValueChange = { viewModel.updateSurfaceType(it) },
                label = { Text("Surface Type (CONCRETE, CERAMIC_TILE, TIMBER, CARPET)") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.locationType,
                onValueChange = { viewModel.updateLocationType(it) },
                label = { Text("Location Type (CENTER_SPAN, NEAR_COLUMN, BALCONY, FOUNDATION)") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.placement,
                onValueChange = { viewModel.updatePlacement(it) },
                label = { Text("Phone Placement (FLAT_ON_FLOOR, FLAT_WITH_WEIGHT, MOUNTED_WALL)") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { viewModel.setStep(3) }) {
                    Text("<- Back")
                }
                Button(onClick = { viewModel.saveProfileAndFinish() }, enabled = !state.isSaving) {
                    Text(if (state.isSaving) "Saving..." else "Complete Setup & Save ->")
                }
            }
        }
    }
}

@Composable
private fun StepFiveSummary(
    state: OnboardingState,
    onFinished: (String, String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "5. Profile Configuration Complete!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(text = "Your citizen-scale SHM baseline profile and session placement parameters have been persisted to Room DB.")

            Text(text = "Building Name: ${state.buildingName}")
            Text(text = "Typology: ${state.buildingType} (${state.floors} Floors)")
            Text(text = "Spatial Hash: ${state.resolvedBuildingHash ?: "LOCAL_ONLY"}")
            Text(text = "Building ID: ${state.savedBuildingId ?: "N/A"}")
            Text(text = "Measurement Profile ID: ${state.savedMeasurementId ?: "N/A"}")

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    if (state.savedBuildingId != null && state.savedMeasurementId != null) {
                        onFinished(state.savedBuildingId, state.savedMeasurementId)
                    }
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Proceed to Real-Time Recording HUD ->")
            }
        }
    }
}
