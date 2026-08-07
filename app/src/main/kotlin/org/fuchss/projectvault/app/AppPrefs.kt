package org.fuchss.projectvault.app

import java.io.File
import java.util.Properties

/**
 * Small persistent key/value store for app-level UI preferences (not vault content) — e.g. which
 * onboarding hints the user has dismissed. Lives in the per-user config dir alongside the recent-vault
 * list, so choices like closing a hint survive an app restart. Backed by a `.properties` file.
 */
class AppPrefs(private val storeFile: File) {
    private val props = Properties().apply {
        if (storeFile.exists()) storeFile.inputStream().use { load(it) }
    }

    fun getBool(key: String, default: Boolean): Boolean =
        props.getProperty(key)?.toBooleanStrictOrNull() ?: default

    fun setBool(key: String, value: Boolean) {
        props.setProperty(key, value.toString())
        save()
    }

    fun getString(key: String): String? = props.getProperty(key)?.ifBlank { null }

    fun setString(key: String, value: String) {
        props.setProperty(key, value)
        save()
    }

    fun clear(key: String) {
        if (props.remove(key) != null) save()
    }

    private fun save() {
        storeFile.parentFile?.mkdirs()
        storeFile.outputStream().use { props.store(it, "Project Vault UI preferences") }
    }

    companion object {
        fun default(): AppPrefs = AppPrefs(File(appConfigDir(), "prefs.properties"))

        const val HINT_PROFILES_DISMISSED = "hint.profiles.dismissed"
        const val HINT_ACCOUNTS_DISMISSED = "hint.accounts.dismissed"
        const val LAST_VAULT_PATH = "last.vault.path"
        const val THEME_MODE = "theme.mode"
    }
}
