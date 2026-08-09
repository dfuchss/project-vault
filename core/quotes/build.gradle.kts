plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:model"))
    // JSON element access only (no @Serializable classes), so the serialization
    // compiler plugin is not needed. HTTP comes from the JDK's java.net.http.
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test.junit)
}
