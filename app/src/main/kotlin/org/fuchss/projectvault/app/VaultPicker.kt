@file:OptIn(ExperimentalMaterial3Api::class)

package org.fuchss.projectvault.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.fuchss.projectvault.data.Vault
import org.fuchss.projectvault.data.VaultManager
import java.io.File

@Composable
internal fun VaultPicker(prefs: AppPrefs, onOpened: (Vault) -> Unit) {
    val recentStore = remember { RecentVaults.default() }
    var recents by remember { mutableStateOf(recentStore.list()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun openVault(file: File, allowCreate: Boolean) {
        runCatching {
            when {
                file.exists() -> VaultManager.open(file)
                allowCreate -> VaultManager.create(file)
                else -> error("Vault not found: ${file.name}")
            }
        }.onSuccess {
            recents = recentStore.add(file)
            prefs.setString(AppPrefs.LAST_VAULT_PATH, file.absolutePath) // reopen this vault next launch
            onOpened(it)
        }.onFailure { error = it.message }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 460.dp), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(rememberClasspathPainter("branding/app-icon.png"), contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(13.dp)))
                    Spacer(Modifier.width(16.dp))
                    Wordmark(MaterialTheme.typography.headlineMedium)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Local-first personal finance. Your data stays on your machine.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = {
                        val chosen = saveFileDialog("Create vault", "household.pvault") ?: return@Button
                        val file = if (chosen.name.endsWith(".pvault")) chosen else File(chosen.parentFile, chosen.name + ".pvault")
                        openVault(file, allowCreate = true)
                    }, modifier = Modifier.weight(1f)) { Text("Create vault") }
                    OutlinedButton(onClick = {
                        val file = openFileDialog("Open vault") ?: return@OutlinedButton
                        openVault(file, allowCreate = false)
                    }, modifier = Modifier.weight(1f)) { Text("Open…") }
                }
                if (recents.isNotEmpty()) {
                    Spacer(Modifier.height(22.dp))
                    Text("RECENT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        recents.forEach { file ->
                            RecentRow(
                                file = file,
                                onOpen = { openVault(file, allowCreate = false) },
                                onForget = { recents = recentStore.remove(file); error = null },
                            )
                        }
                    }
                }
                error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RecentRow(file: File, onOpen: () -> Unit, onForget: () -> Unit) {
    val exists = file.exists()
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).clickable(enabled = exists, onClick = onOpen).padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                Text(
                    file.name.removeSuffix(".pvault"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (exists) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    file.parent ?: file.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!exists) {
                Text("missing", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(6.dp))
            }
            TextButton(onClick = onForget, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Forget") }
        }
    }
}
