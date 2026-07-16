plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.jetbrains.kotlin.jvm) apply false
}

val termuxAapt2 = file("/data/data/com.termux/files/usr/bin/aapt2")
if (termuxAapt2.exists()) {
    allprojects {
        extra.set("android.aapt2FromMavenOverride", termuxAapt2.absolutePath)
    }
}
