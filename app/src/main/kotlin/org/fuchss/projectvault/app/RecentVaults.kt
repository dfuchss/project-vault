package org.fuchss.projectvault.app

import java.io.File

/**
 * Remembers recently created/opened vaults across launches, most-recent first. This is app-level
 * state (which vaults exist), not vault content, so it lives in a per-user config file outside any
 * vault. Paths are stored absolute, de-duplicated, and capped.
 */
class RecentVaults(private val storeFile: File, private val max: Int = 10) {

    fun list(): List<File> =
        if (storeFile.exists()) {
            storeFile.readLines().map(String::trim).filter { it.isNotEmpty() }.map(::File)
        } else {
            emptyList()
        }

    /** Records [vault] as the most recent (moving it to the top). Returns the updated list. */
    fun add(vault: File): List<File> {
        val abs = vault.absoluteFile
        val updated = (listOf(abs) + list()).distinctBy { it.absoluteFile.path }.take(max)
        write(updated)
        return updated
    }

    fun remove(vault: File): List<File> {
        val updated = list().filterNot { it.absoluteFile.path == vault.absoluteFile.path }
        write(updated)
        return updated
    }

    private fun write(vaults: List<File>) {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText(vaults.joinToString("\n") { it.absoluteFile.path })
    }

    companion object {
        fun default(): RecentVaults = RecentVaults(File(appConfigDir(), "recent.txt"))
    }
}

/** Per-user config directory for app-level state (recent vaults, UI preferences) — never vault data. */
internal fun appConfigDir(): File {
    val home = System.getProperty("user.home")
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") -> File(home, "Library/Application Support/ProjectVault")
        os.contains("win") -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "ProjectVault")
        else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "project-vault")
    }
}
