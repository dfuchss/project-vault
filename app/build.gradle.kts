import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:import"))
    implementation(project(":core:classification"))
    implementation(project(":core:analytics"))

    // SLF4J backend so PDFBox's import diagnostics are actually printed (WARN+ to stderr) instead of
    // being swallowed by the no-op logger.
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.kotlin.test.junit)
}

compose.desktop {
    application {
        mainClass = "org.fuchss.projectvault.app.MainKt"

        // Keep slf4j-simple quiet: only surface PDFBox WARN+ (its INFO chatter is noise).
        jvmArgs += "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn"

        // Name the macOS app menu / dock as "Project Vault" (not the main-class "MainKt") for both
        // `:app:run` and the packaged launcher — but ONLY on macOS. `-Xdock:name` is a mac-only JVM
        // option; baking it into the Windows/Linux launchers makes the JVM abort at startup with
        // "Unrecognized option", which on Windows' GUI launcher shows up as the app silently never
        // starting. Each OS is packaged on its own runner, so gate these on the build host.
        if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
            jvmArgs += listOf(
                "-Dapple.awt.application.name=Project Vault",
                "-Xdock:name=Project Vault",
            )
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Project Vault"
            // macOS requires MAJOR > 0 for the installer version; product milestone (0.1) is tracked separately.
            packageVersion = "1.0.0"
            description = "Local-first personal finance analyzer"
            vendor = "Project Vault"
            // Bundle the full JDK module set so native deps (SQLite JDBC, onnxruntime, JNA) work in the
            // packaged runtime without hunting individual modules.
            includeAllModules = true
            macOS {
                bundleID = "org.fuchss.projectvault.app"
                iconFile.set(project.file("icons/app-icon.icns"))
            }
            linux { iconFile.set(project.file("icons/app-icon.png")) }
            windows { iconFile.set(project.file("icons/app-icon.ico")) }
        }
    }
}

// `iconFile` above only reaches the .app inside the DMG. The disk image itself keeps Java's
// stock artwork: jpackage copies its bundled JavaApp.icns into the volume as `.VolumeIcon.icns`
// (so the install window is branded with a coffee cup) and the .dmg file gets no Finder icon at
// all. The supported fix — a `<PackageName>-volume.icns` in jpackage's `--resource-dir` — is out
// of reach because the Compose plugin owns that directory and wipes it inside the task action
// (AbstractJPackageTask.prepareWorkingDir), leaving no window to drop a file in. So we re-brand
// the finished image instead; see app/scripts/set-dmg-icon.sh.
//
// This is a `doLast` on the packaging task rather than a follow-up task on purpose: Gradle
// snapshots outputs after all actions run, so the re-branded DMG *is* the recorded output and a
// second `packageDmg` still resolves to UP-TO-DATE.
if (System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) {
    val brandDmgScript = project.file("scripts/set-dmg-icon.sh")
    val brandDmgIcon = project.file("icons/app-icon.icns")

    // Compose registers packageDmg/packageReleaseDmg late (afterEvaluate), so match by type and
    // filter on the format rather than looking the names up eagerly.
    tasks.withType<AbstractJPackageTask>().configureEach {
        if (targetFormat != TargetFormat.Dmg) return@configureEach

        inputs.file(brandDmgScript).withPropertyName("dmgIconScript")
        inputs.file(brandDmgIcon).withPropertyName("dmgVolumeIcon")

        doLast {
            val dmgs = destinationDir.get().asFile.listFiles { f: File -> f.extension == "dmg" }.orEmpty()
            if (dmgs.isEmpty()) throw GradleException("$name produced no .dmg to brand")

            dmgs.forEach { dmg ->
                val process = ProcessBuilder(
                    "/bin/bash",
                    brandDmgScript.absolutePath,
                    dmg.absolutePath,
                    brandDmgIcon.absolutePath,
                ).redirectErrorStream(true).start()
                process.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
                if (process.waitFor() != 0) {
                    throw GradleException("Could not set the DMG icon on ${dmg.name}")
                }
            }
        }
    }
}
