rootProject.name = "project-vault"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

// Modules are added as they are built out (see the implementation plan).
include(":app")
include(":core:model")
include(":core:data")
include(":core:import")
include(":core:classification")
include(":core:analytics")
include(":core:quotes")
