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
    onBuildingDeleted: () -> Unit = {},
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

        if (state.hasRecordedSessions) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "This building profile has recorded sessions and is locked for editing.",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        when (state.step) {
            1 -> StepOneHardwareEvaluation(state, viewModel)
            2 -> StepTwoBuildingTypology(state, viewModel)
            3 -> StepThreeLocationPrivacy(state, viewModel)
            4 -> StepFourPlacementSetup(state, viewModel)
            5 -> StepFiveSummary(state, viewModel, onFinished, onBuildingDeleted)
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
    var floorsDropdownExpanded by remember { mutableStateOf(false) }
    var yearDropdownExpanded by remember { mutableStateOf(false) }
    var materialDropdownExpanded by remember { mutableStateOf(false) }
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
    val floorOptions = listOf(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
        "12", "15", "20", "25", "30", "40", "50+"
    )
    val constructionYearOptions = listOf(
        "Before 1970", "1970-1980", "1980-1990", "1990-2000",
        "2000-2005", "2005-2010", "2010-2015", "2015-2020",
        "2020-2025", "After 2025"
    )
    val materialOptions = listOf(
        "Reinforced Concrete (RC)", "Prestressed Concrete",
        "Structural Steel (S275/S355)", "Steel-Concrete Composite",
        "Clay Brick Masonry", "Concrete Block Masonry",
        "Timber Frame", "Cross-Laminated Timber (CLT)",
        "Mixed / Hybrid", "Unknown"
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "2. Building Typology Profiler", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = state.buildingName,
                onValueChange = { viewModel.updateBuildingName(it) },
                label = { Text("Building Name / Identifier") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.hasRecordedSessions
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
                if (!state.hasRecordedSessions) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { dropdownExpanded = true }
                    )
                }
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

            // Dropdown Selector for Total Floors
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.floors,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Total Floors") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true
                )
                if (!state.hasRecordedSessions) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { floorsDropdownExpanded = true }
                    )
                }
                DropdownMenu(
                    expanded = floorsDropdownExpanded,
                    onDismissRequest = { floorsDropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    floorOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                viewModel.updateFloors(option)
                                floorsDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // Dropdown Selector for Construction Year
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.constructionYear,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Construction Year") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true
                )
                if (!state.hasRecordedSessions) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { yearDropdownExpanded = true }
                    )
                }
                DropdownMenu(
                    expanded = yearDropdownExpanded,
                    onDismissRequest = { yearDropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    constructionYearOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                viewModel.updateConstructionYear(option)
                                yearDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // Dropdown Selector for Primary Structural Material
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.material,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Primary Structural Material") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true
                )
                if (!state.hasRecordedSessions) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { materialDropdownExpanded = true }
                    )
                }
                DropdownMenu(
                    expanded = materialDropdownExpanded,
                    onDismissRequest = { materialDropdownExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    materialOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                viewModel.updateMaterial(option)
                                materialDropdownExpanded = false
                            }
                        )
                    }
                }
            }

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
                        .clickable(enabled = !state.hasRecordedSessions) { viewModel.updatePrivacyLevel(level) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = state.privacyLevel == level,
                        onClick = { viewModel.updatePrivacyLevel(level) },
                        enabled = !state.hasRecordedSessions
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
                                enabled = !state.hasRecordedSessions,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) {
                                Text("▲")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                OutlinedButton(
                                    onClick = { viewModel.adjustCoordinates(0.0, -0.0001) },
                                    modifier = Modifier.size(50.dp),
                                    enabled = !state.hasRecordedSessions,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                ) {
                                    Text("◀")
                                }
                                Spacer(modifier = Modifier.width(36.dp))
                                OutlinedButton(
                                    onClick = { viewModel.adjustCoordinates(0.0, 0.0001) },
                                    modifier = Modifier.size(50.dp),
                                    enabled = !state.hasRecordedSessions,
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                ) {
                                    Text("▶")
                                }
                            }
                            OutlinedButton(
                                onClick = { viewModel.adjustCoordinates(-0.0001, 0.0) },
                                modifier = Modifier.size(50.dp),
                                enabled = !state.hasRecordedSessions,
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
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT && (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
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

                                    override fun shouldInterceptRequest(
                                        view: android.webkit.WebView?,
                                        request: android.webkit.WebResourceRequest?
                                    ): android.webkit.WebResourceResponse? {
                                        val url = request?.url.toString()
                                        if (url.contains("tile.openstreetmap.org")) {
                                            android.util.Log.d("SHM_MAP_TILE", "Tile request: $url")
                                        }
                                        return super.shouldInterceptRequest(view, request)
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
                                settings.allowFileAccess = true
                                settings.allowFileAccessFromFileURLs = true
                                settings.allowUniversalAccessFromFileURLs = true
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
                                                        console.log("Map container size: " + JSON.stringify(map.getSize()));
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
    var floorLevelExpanded by remember { mutableStateOf(false) }
    var surfaceTypeExpanded by remember { mutableStateOf(false) }
    var locationTypeExpanded by remember { mutableStateOf(false) }
    var placementExpanded by remember { mutableStateOf(false) }

    val floorLevelOptions = listOf(
        "Basement", "Ground Floor", "1", "2", "3", "4", "5",
        "6", "7", "8", "9", "10", "12", "15", "20", "Roof / Terrace"
    )
    val surfaceTypeOptions = listOf(
        "Bare Concrete", "Ceramic / Porcelain Tile", "Marble / Granite",
        "Timber / Hardwood", "Laminate / Vinyl", "Carpet (Thin)",
        "Carpet (Thick)", "Steel Deck", "Raised Access Floor"
    )
    val locationTypeOptions = listOf(
        "Center of Span (Mid-Bay)", "Near Column / Wall", "Quarter Span",
        "Balcony / Cantilever", "Stairwell Landing",
        "Foundation / Ground Level", "Bridge Deck Center",
        "Bridge Pier / Abutment"
    )
    val placementOptions = listOf(
        "Flat on Floor (No Weight)", "Flat on Floor (Weighted)",
        "Mounted on Wall", "Mounted on Column", "Tripod / Rigid Mount",
        "On Desk / Table", "Handheld (Not Recommended)"
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = "4. Measurement Placement Setup", style = MaterialTheme.typography.titleLarge)

            // Dropdown Selector for Floor Level
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.floorLevel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Current Measurement Floor Level") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true
                )
                if (!state.hasRecordedSessions) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { floorLevelExpanded = true }
                    )
                }
                DropdownMenu(
                    expanded = floorLevelExpanded,
                    onDismissRequest = { floorLevelExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    floorLevelOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                viewModel.updateFloorLevel(option)
                                floorLevelExpanded = false
                            }
                        )
                    }
                }
            }

            // Dropdown Selector for Surface Type
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.surfaceType,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Surface Type") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true
                )
                if (!state.hasRecordedSessions) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { surfaceTypeExpanded = true }
                    )
                }
                DropdownMenu(
                    expanded = surfaceTypeExpanded,
                    onDismissRequest = { surfaceTypeExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    surfaceTypeOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                viewModel.updateSurfaceType(option)
                                surfaceTypeExpanded = false
                            }
                        )
                    }
                }
            }

            // Dropdown Selector for Location Type
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.locationType,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Location Type") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true
                )
                if (!state.hasRecordedSessions) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { locationTypeExpanded = true }
                    )
                }
                DropdownMenu(
                    expanded = locationTypeExpanded,
                    onDismissRequest = { locationTypeExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    locationTypeOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                viewModel.updateLocationType(option)
                                locationTypeExpanded = false
                            }
                        )
                    }
                }
            }

            // Dropdown Selector for Phone Placement
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.placement,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Phone Placement") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true
                )
                if (!state.hasRecordedSessions) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { placementExpanded = true }
                    )
                }
                DropdownMenu(
                    expanded = placementExpanded,
                    onDismissRequest = { placementExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    placementOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                viewModel.updatePlacement(option)
                                placementExpanded = false
                            }
                        )
                    }
                }
            }

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
    viewModel: OnboardingViewModel,
    onFinished: (String, String) -> Unit,
    onBuildingDeleted: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (state.hasRecordedSessions && state.savedBuildingId != null) {
                    Button(
                        onClick = { showDeleteConfirm = true },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete this building")
                    }
                } else {
                    OutlinedButton(onClick = { viewModel.setStep(4) }) {
                        Text("<- Back")
                    }
                }
                
                Button(
                    onClick = {
                        if (state.savedBuildingId != null && state.savedMeasurementId != null) {
                            onFinished(state.savedBuildingId, state.savedMeasurementId)
                        }
                    }
                ) {
                    Text("Proceed to Real-Time Recording HUD ->")
                }
            }
        }
    }

    if (showDeleteConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Building Profile?") },
            text = { Text("This will permanently remove the building profile, local session metadata, and all baseline histories from this device. Are you sure you want to delete this building?") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        state.savedBuildingId?.let {
                            viewModel.deleteBuildingProfile(it) {
                                viewModel.resetState()
                                onBuildingDeleted()
                            }
                        }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
