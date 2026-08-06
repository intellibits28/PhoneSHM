plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.appdistribution)
}

fun getGitCommitHash(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(project.rootDir)
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        process.waitFor()
        val hash = process.inputStream.bufferedReader().readText().trim()
        if (hash.isNotEmpty() && hash.length >= 7) hash else "unknown"
    } catch (e: Exception) {
        "unknown"
    }
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

        val gitHash = getGitCommitHash()
        buildConfigField("String", "GIT_COMMIT_HASH", "\"$gitHash\"")

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
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests.all {
            it.systemProperty("robolectric.use.conscrypt", "false")
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

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.storage.ktx)
    implementation(libs.firebase.config.ktx)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core-ktx:1.5.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}

tasks.withType<Test> {
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
