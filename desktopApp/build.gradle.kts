import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Align the Java compile target with Kotlin's (the JDK running Gradle is 21, so
// without this compileJava would default to 21 and mismatch compileKotlin at 17).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "io.github.danielperezmartinez.titanssh.desktop.MainKt"

        nativeDistributions {
            // macOS/iOS están fuera de alcance (ver ADR-0002).
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "titan-ssh"
            // Single app version, resolved in the root build file (ADR-0011 §3),
            // mapped per format to what each installer can order correctly.
            packageVersion = rootProject.extra["titanPackageVersion"] as String
            windows {
                msiPackageVersion = rootProject.extra["titanMsiVersion"] as String
            }
            linux {
                debPackageVersion = rootProject.extra["titanDebVersion"] as String
            }
        }
    }
}
