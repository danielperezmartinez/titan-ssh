plugins {
    // AGP 9 ships built-in Kotlin for Android modules, so 'org.jetbrains.kotlin.android'
    // is not applied here (applying it fails since AGP 9.0).
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "io.github.danielperezmartinez.titanssh.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.danielperezmartinez.titanssh"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Single app version, resolved in the root build file (ADR-0011 §3).
        versionCode = rootProject.extra["titanVersionCode"] as Int
        versionName = rootProject.extra["titanVersion"] as String
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material3)
}
