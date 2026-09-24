plugins {
    // AGP 9 ships built-in Kotlin for Android modules, so 'org.jetbrains.kotlin.android'
    // is not applied here (applying it fails since AGP 9.0).
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Release signing key (ADR-0011 §5). Never in the repository: CI passes it through
// environment variables from GitHub Secrets, and a local build can use either
// those or -P properties. Without them the release build is produced unsigned
// and the debug build is unaffected.
fun signingValue(property: String, env: String): String? =
    (findProperty(property) as String?) ?: System.getenv(env)

val releaseKeystore = signingValue("titanKeystoreFile", "TITAN_KEYSTORE_FILE")

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

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingValue("titanKeystorePassword", "TITAN_KEYSTORE_PASSWORD")
                keyAlias = signingValue("titanKeyAlias", "TITAN_KEY_ALIAS")
                keyPassword = signingValue("titanKeyPassword", "TITAN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8 is on: Compose on Android relies on it for acceptable runtime
            // performance. Crypto providers resolved by reflection are kept in
            // proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // The encrypted dependency block AGP adds is opaque to third parties;
    // IzzyOnDroid and F-Droid ask for it to be left out.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
