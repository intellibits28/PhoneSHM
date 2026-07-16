plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.ronin.phoneshm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ronin.phoneshm"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.2.0-research-grade"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:device"))
    implementation(project(":core:physics"))
    implementation(project(":core:baseline"))
    implementation(project(":core:sensor"))
    implementation(project(":core:dsp"))
    implementation(project(":core:modal"))
    implementation(project(":core:location"))
    implementation(project(":core:audio"))
    implementation(project(":core:quality"))
    implementation(project(":core:database"))
    implementation(project(":core:storage"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:measurement"))
    implementation(project(":feature:analysis"))
    implementation(project(":feature:report"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
