@file:OptIn(ExperimentalMaterial3Api::class)

package org.fuchss.projectvault.app

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.fuchss.projectvault.data.VaultManager
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun main() {
    // Name the macOS application menu / dock "Project Vault" (not the main-class "MainKt"). Must be set
    // before AWT initializes, so do it first thing in main(). The bold app-menu label itself is driven
    // by `-Xdock:name` (dev run, see build.gradle) or the bundle's CFBundleName (packaged app).
    System.setProperty("apple.awt.application.name", "Project Vault")
    // Replace the default macOS "About java" item with our own, so About identifies as Project Vault.
    runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        if (desktop.isSupported(java.awt.Desktop.Action.APP_ABOUT)) {
            desktop.setAboutHandler {
                javax.swing.JOptionPane.showMessageDialog(
                    null,
                    "Project Vault\nLocal-first, privacy-first personal finance analyzer.",
                    "About Project Vault",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE,
                )
            }
        }
    }

    val prefs = AppPrefs.default()

    application {
        val state = rememberWindowState(size = DpSize(1280.dp, 860.dp))
        var themeMode by remember {
            mutableStateOf(runCatching { ThemeMode.valueOf(prefs.getString(AppPrefs.THEME_MODE) ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM))
        }
        // Reopen the vault that was open at the last exit. It's cleared on an explicit "close vault",
        // so only a vault left open when the app quit is restored here.
        var vault by remember {
            mutableStateOf(
                prefs.getString(AppPrefs.LAST_VAULT_PATH)
                    ?.let { File(it).takeIf(File::exists) }
                    ?.let { runCatching { VaultManager.open(it) }.getOrNull() },
            )
        }
        fun closeVault() { vault?.close(); vault = null; prefs.clear(AppPrefs.LAST_VAULT_PATH) }

        Window(onCloseRequest = ::exitApplication, state = state, title = "Project Vault", icon = rememberClasspathPainter("branding/app-icon.png")) {
            VaultTheme(themeMode) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val current = vault
                    if (current == null) {
                        VaultPicker(prefs = prefs, onOpened = { vault = it })
                    } else {
                        MainScreen(
                            vault = current,
                            prefs = prefs,
                            themeMode = themeMode,
                            onThemeChange = { themeMode = it; prefs.setString(AppPrefs.THEME_MODE, it.name) },
                            onClose = ::closeVault,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- AWT file dialogs

internal fun openFileDialog(title: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory ?: ".", name)
}

/** Multi-select open dialog — returns all chosen files (empty if cancelled). */
internal fun openFileDialogs(title: String): List<File> {
    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".pdf") || it.endsWith(".csv") } }
    dialog.isVisible = true
    return dialog.files?.toList().orEmpty()
}

internal fun saveFileDialog(title: String, defaultName: String): File? {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory ?: ".", name)
}
