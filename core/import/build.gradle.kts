plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.pdfbox)
    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}
