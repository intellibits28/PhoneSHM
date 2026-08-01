plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.ronin.phoneshm.feature.analysis"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:dsp"))
    implementation(project(":core:modal"))
    implementation(project(":core:physics"))
    implementation(project(":core:baseline"))
    implementation(project(":core:quality"))
    implementation(project(":core:sensor"))
    implementation(project(":core:storage"))
    implementation(project(":core:database"))
    implementation(project(":core:device"))
    implementation(project(":core:audio"))
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation(libs.kotlinx.coroutines.test)
}
