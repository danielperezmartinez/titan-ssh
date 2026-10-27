import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.UUID
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

// The canary app (ADR-0027) is a separate app: its own package, install folder,
// MSI upgrade code, launcher and icons, so it installs next to the stable one.
val isCanary = rootProject.extra["titanChannel"] == "canary"
val appName = if (isCanary) "titan-ssh-canary" else "titan-ssh"

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

// Windows install folder, under %LOCALAPPDATA% (per-user MSI). Forward slash:
// Compose writes jpackage's args file unescaped, so `\t` would become a tab.
val msiInstallationPath = "Programs/$appName"

// jpackage leaves the folders above the app's own (`Programs`) out of the MSI's
// RemoveFile table, and WiX rejects that in the user profile (ICE64). Its only
// hook is a main.wxs in the resource dir, so take the template of the JDK that
// packages and add a component that removes that folder on uninstall, which
// Windows Installer only does when it is empty. The template is read from the
// JDK, not copied here: it follows the JDK's version and its own license.
val msiWixTemplate = layout.buildDirectory.file("generated/msi/main.wxs")
val generateMsiWixTemplate = tasks.register("generateMsiWixTemplate") {
    description = "Writes jpackage's main.wxs with the removal of the MSI's parent folders."
    val javaHome = compose.desktop.application.javaHome
    val parents = msiInstallationPath.split('/').dropLast(1)
    inputs.property("parents", parents)
    inputs.file("$javaHome/release")
    outputs.file(msiWixTemplate)
    doLast {
        val jrt = URI.create("jrt:/")
        val template = if (File(javaHome).canonicalFile == File(System.getProperty("java.home")).canonicalFile) {
            Files.readString(FileSystems.getFileSystem(jrt).getPath("/modules/jdk.jpackage/jdk/jpackage/internal/resources/main.wxs"))
        } else {
            FileSystems.newFileSystem(jrt, mapOf("java.home" to javaHome)).use {
                Files.readString(it.getPath("/modules/jdk.jpackage/jdk/jpackage/internal/resources/main.wxs"))
            }
        }
        // jpackage names each folder `dir` + a name UUID of `Folder@<its path
        // under TARGETDIR>` (WixAppImageFragmentBuilder.Id). If that changes,
        // light fails with an unresolved DirectoryRef rather than silently.
        val components = parents.indices.joinToString("\n") { i ->
            val path = (listOf("TARGETDIR", "LocalAppDataFolder") + parents.take(i + 1)).joinToString("\\")
            val dirId = "dir" + UUID.nameUUIDFromBytes("Folder@${path.lowercase()}".toByteArray()).toString().replace("-", "")
            val guid = UUID.nameUUIDFromBytes("$appName-msi-parent@${path.lowercase()}".toByteArray())
            """
            |    <DirectoryRef Id="$dirId">
            |      <Component Id="TitanParentFolder$i" Guid="{$guid}">
            |        <RegistryValue Root="HKCU" Key="Software\$(var.JpAppVendor)\$(var.JpAppName)" Name="ParentFolder$i" Type="integer" Value="1" KeyPath="yes"/>
            |        <RemoveFolder Id="TitanRemoveParentFolder$i" On="uninstall"/>
            |      </Component>
            |    </DirectoryRef>
            """.trimMargin()
        }
        val refs = parents.indices.joinToString("") { "\n      <ComponentRef Id=\"TitanParentFolder$it\"/>" }
        fun String.insertOnce(anchor: String, insert: (String) -> String): String {
            check(split(anchor).size == 2) { "jpackage's main.wxs changed: '$anchor' is not there exactly once" }
            return replace(anchor, insert(anchor))
        }
        val patched = template
            .insertOnce("<ComponentGroupRef Id=\"Files\"/>") { it + refs }
            .insertOnce("</Product>") { "$components\n\n  $it" }
        msiWixTemplate.get().asFile.apply { parentFile.mkdirs() }.writeText(patched)
    }
}

// Compose's packageMsi empties the resource dir it hands jpackage, so that
// main.wxs cannot reach it. The MSI is built here instead, from Compose's own
// app image and with the options of the DSL below; `packageMsi` runs this.
val jpackageMsi = tasks.register<Exec>("jpackageMsi") {
    group = "compose desktop"
    description = "Builds the MSI with jpackage from the app image, with the patched main.wxs."
    onlyIf("jpackage builds an MSI only on Windows") {
        System.getProperty("os.name").startsWith("Windows")
    }
    dependsOn("createDistributable", generateMsiWixTemplate, ":unzipWix")
    val distributions = compose.desktop.application.nativeDistributions
    val appImage = layout.buildDirectory.dir("compose/binaries/main/app/${distributions.packageName}")
    val destination = layout.buildDirectory.dir("compose/binaries/main/msi")
    val wix = rootProject.layout.buildDirectory.dir("wix311")
    inputs.dir(appImage)
    inputs.file(msiWixTemplate)
    outputs.dir(destination)
    doFirst { delete(destination) }
    executable = "${compose.desktop.application.javaHome}/bin/jpackage.exe"
    environment("PATH", "${wix.get().asFile.absolutePath};${System.getenv("PATH")}")
    val windows = distributions.windows
    args(
        "--type", "msi",
        "--app-image", appImage.get().asFile.absolutePath,
        "--resource-dir", msiWixTemplate.get().asFile.parentFile.absolutePath,
        "--dest", destination.get().asFile.absolutePath,
        "--name", distributions.packageName!!,
        "--app-version", windows.msiPackageVersion!!,
        "--vendor", distributions.vendor!!,
        "--description", distributions.description!!,
        "--copyright", distributions.copyright!!,
        "--license-file", distributions.licenseFile.get().asFile.absolutePath,
        "--install-dir", windows.installationPath!!,
        "--win-upgrade-uuid", windows.upgradeUuid!!,
        "--win-menu-group", windows.menuGroup!!,
    )
    if (windows.perUserInstall) args("--win-per-user-install")
    if (windows.dirChooser) args("--win-dir-chooser")
    if (windows.shortcut) args("--win-shortcut")
    if (windows.menu) args("--win-menu")
}
tasks.matching { it.name == "packageMsi" }.configureEach {
    // Compose sets `enabled` itself, so skip it with a predicate instead.
    onlyIf("jpackageMsi builds the MSI") { false }
    dependsOn(jpackageMsi)
}

compose.desktop {
    application {
        mainClass = "io.github.danielperezmartinez.titanssh.desktop.MainKt"

        // No ProGuard: the packages use the plain `package*` tasks. sshj's JCE
        // provider lookups, JNA (java-keyring) and kotlinx.serialization all rely
        // on reflection, and the size saved does not justify the risk.

        nativeDistributions {
            // macOS/iOS están fuera de alcance (ver ADR-0002).
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = appName
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
                iconFile.set(project.file("icons/$appName.ico"))
                // Fixed forever: Windows Installer matches upgrades by this code.
                // Changing it makes new versions install side by side, which is
                // what the canary's own code is for.
                upgradeUuid = if (isCanary) "33d686dd-7a60-40ad-bc76-96f9399e9ba3" else "aa0fe179-f4a1-4db1-92f3-f3b04240e849"
                // Per-user install under %LOCALAPPDATA%: no admin rights, no UAC.
                perUserInstall = true
                // Not %LOCALAPPDATA%\titan-ssh: the agent's state and the app's
                // caches live there, and jpackage's MSI deletes its whole install
                // folder on uninstall, which every upgrade runs (ADR-0026).
                installationPath = msiInstallationPath
                // No folder to pick: the updater installs over the same folder,
                // and jpackage's "folder already exists" prompt goes with it.
                dirChooser = false
                shortcut = true
                menu = true
                menuGroup = appName
            }
            linux {
                debPackageVersion = rootProject.extra["titanDebVersion"] as String
                rpmPackageVersion = rootProject.extra["titanRpmVersion"] as String
                iconFile.set(project.file("icons/$appName.png"))
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
// the hicolor icon theme under `icons/`, ready to copy into `share/`. The
// canary's is the same with `titan-ssh-canary` everywhere.
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
    archiveFileName.set("$appName-$titanVersion-linux-x64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/tar.gz"))
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    from(file("packaging/linux/titan-ssh.desktop")) {
        if (isCanary) {
            rename { "$appName.desktop" }
            filter { line ->
                when {
                    line.startsWith("Name=") -> "Name=titan-ssh canary"
                    line.startsWith("Exec=") -> "Exec=$appName"
                    line.startsWith("Icon=") -> "Icon=$appName"
                    else -> line
                }
            }
        }
    }
    listOf(128, 256, 512).forEach { size ->
        from(rootProject.file("branding/generated/png/$appName-$size.png")) {
            into("icons/hicolor/${size}x$size/apps")
            rename { "$appName.png" }
        }
    }
    from(rootProject.file(if (isCanary) "branding/generated/icon-canary.svg" else "branding/icon.svg")) {
        into("icons/hicolor/scalable/apps")
        rename { "$appName.svg" }
    }
}
