import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    // Android target as a KMP library (AGP 9 native plugin
    // com.android.kotlin.multiplatform.library), so it coexists with
    // kotlin.multiplatform without the legacy com.android.library + compat flags.
    android {
        namespace = "im.gar.titanssh.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Desktop (Windows/Linux) target.
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }

        // Intermediate JVM source set shared by Android and desktop (README
        // architecture): pure-Java pieces both JVM targets use, notably the sshj
        // SSH client (ADR-0004). commonMain stays free of sshj.
        val jvmSharedMain = create("jvmSharedMain") {
            dependsOn(getByName("commonMain"))
            dependencies {
                implementation(libs.sshj)
                implementation(libs.eddsa)
            }
        }

        getByName("androidMain").dependsOn(jvmSharedMain)

        // Desktop SecretStore backend (ADR-0001). Android uses the platform
        // KeyStore directly, so this dependency is desktop-only.
        getByName("desktopMain") {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation(libs.java.keyring)
            }
        }

        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
