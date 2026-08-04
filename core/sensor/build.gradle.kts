plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
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
    namespace = "com.ronin.phoneshm.core.sensor"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val gitHash = getGitCommitHash()
        buildConfigField("String", "GIT_COMMIT_HASH", "\"$gitHash\"")
        buildConfigField("String", "VERSION_NAME", "\"1.2.0-research-grade\"")
        buildConfigField("int", "VERSION_CODE", "1")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":core:storage"))
    implementation(project(":core:device"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20231013")
}
