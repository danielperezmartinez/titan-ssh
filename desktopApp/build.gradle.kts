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

val titanVersion = rootProject.extra["titanVersion"] as String

// LICENSE and THIRD_PARTY_NOTICES.md shipped as plain files inside every package
// (MSI, deb, rpm, tar.gz), next to the app. `common/` is the Compose layout for
// app resources shared by every OS.
val appResourcesDir = layout.buildDirectory.dir("generated/appResources")
val syncAppResources = tasks.register<Sync>("syncAppResources") {
    description = "Collects the legal texts shipped inside every desktop package."
    from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY_NOTICES.md"))
    into(appResourcesDir.map { it.dir("common") })
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(syncAppResources) }

compose.desktop {
    application {
        mainClass = "io.github.danielperezmartinez.titanssh.desktop.MainKt"

        // No ProGuard: the packages use the plain `package*` tasks. sshj's JCE
        // provider lookups, JNA (java-keyring) and kotlinx.serialization all rely
        // on reflection, and the size saved does not justify the risk.

        nativeDistributions {
            // macOS/iOS están fuera de alcance (ver ADR-0002).
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "titan-ssh"
            // Single app version, resolved in the root build file (ADR-0011 §3),
            // mapped per format to what each installer can order correctly.
            packageVersion = rootProject.extra["titanPackageVersion"] as String
            vendor = "Daniel Pérez Martínez"
            description = "Resilient multi-platform SSH client"
            copyright = "Copyright (C) 2026 Daniel Pérez Martínez. GPL-3.0-or-later."
            licenseFile.set(rootProject.file("LICENSE"))
            appResourcesRootDir.set(appResourcesDir)

            // jpackage trims the bundled JRE to these modules. The first five come
            // from `suggestRuntimeModules`; jdk.crypto.ec (ECDH/ECDSA, used by
            // modern OpenSSH key exchange) and java.naming are loaded reflectively,
            // so jdeps cannot see them.
            modules(
                "java.instrument",
                "java.security.jgss",
                "java.sql",
                "jdk.security.auth",
                "jdk.unsupported",
                "jdk.crypto.ec",
                "java.naming",
            )

            windows {
                msiPackageVersion = rootProject.extra["titanMsiVersion"] as String
                // Generated from branding/icon.svg by branding/RenderIcon.java.
                iconFile.set(project.file("icons/titan-ssh.ico"))
                // Fixed forever: Windows Installer matches upgrades by this code.
                // Changing it makes new versions install side by side.
                upgradeUuid = "aa0fe179-f4a1-4db1-92f3-f3b04240e849"
                // Per-user install under %LOCALAPPDATA%: no admin rights, no UAC.
                perUserInstall = true
                dirChooser = true
                shortcut = true
                menu = true
                menuGroup = "titan-ssh"
            }
            linux {
                debPackageVersion = rootProject.extra["titanDebVersion"] as String
                rpmPackageVersion = rootProject.extra["titanRpmVersion"] as String
                iconFile.set(project.file("icons/titan-ssh.png"))
                // jpackage prepends the vendor: this is only the address.
                debMaintainer = "danielperezmartinez@users.noreply.github.com"
                appCategory = "Network"
                menuGroup = "Network"
                rpmLicenseType = "GPL-3.0-or-later"
                shortcut = true
            }
        }
    }
}

// tar.gz of the Linux app image, for AUR and Flatpak (Compose does not build one).
// Holds `titan-ssh/` (launcher, runtime, app), the desktop entry at the root and
// the hicolor icon theme under `icons/`, ready to copy into `share/`.
tasks.register<Tar>("packageTarGz") {
    group = "compose desktop"
    description = "Packs the Linux app image from createDistributable into a tar.gz."
    onlyIf("jpackage builds a Linux image only on Linux") {
        System.getProperty("os.name").startsWith("Linux")
    }
    dependsOn("createDistributable")
    compression = Compression.GZIP
    // Gradle 9 normalizes archive permissions; keep the launcher and the
    // runtime's helpers (jspawnhelper) executable.
    useFileSystemPermissions()
    archiveFileName.set("titan-ssh-$titanVersion-linux-x64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/tar.gz"))
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    from(file("packaging/linux/titan-ssh.desktop"))
    listOf(128, 256, 512).forEach { size ->
        from(rootProject.file("branding/generated/png/titan-ssh-$size.png")) {
            into("icons/hicolor/${size}x$size/apps")
            rename { "titan-ssh.png" }
        }
    }
    from(rootProject.file("branding/icon.svg")) {
        into("icons/hicolor/scalable/apps")
        rename { "titan-ssh.svg" }
    }
}
