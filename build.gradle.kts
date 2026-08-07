// Root build script. Plugins are declared here once (apply false) so they load on a
// single classpath, then each module applies the ones it needs without repeating versions.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.sqldelight) apply false
}
