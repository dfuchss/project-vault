plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)
}

sqldelight {
    databases {
        create("VaultDatabase") {
            packageName.set("org.fuchss.projectvault.data.db")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.sqlite.driver)
    testImplementation(libs.kotlin.test.junit)
}
