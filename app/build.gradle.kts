import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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

        // Name the macOS app menu / dock as "Project Vault" (not the main-class "MainKt") for both
        // `:app:run` and the packaged launcher.
        jvmArgs += listOf(
            "-Dapple.awt.application.name=Project Vault",
            "-Xdock:name=Project Vault",
            // Keep slf4j-simple quiet: only surface PDFBox WARN+ (its INFO chatter is noise).
            "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
        )

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
