package org.fuchss.projectvault.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.fuchss.projectvault.data.db.Category
import org.fuchss.projectvault.data.db.Profile
import org.fuchss.projectvault.model.AccountType
import org.fuchss.projectvault.model.CategoryKind

@Composable
internal fun AddProfileDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(ProfilePalette.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add profile") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.height(14.dp))
                Text("Colour", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                FlowRowChips {
                    ProfilePalette.forEach { hex ->
                        val c = parseHexColor(hex)
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(c)
                                .clickable { color = hex }
                                .then(if (color == hex) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onAdd(name.trim(), color) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Rename or delete profiles. Deleting only unassigns the profile from accounts — the accounts stay. */
@Composable
internal fun ManageProfilesDialog(
    profiles: List<Profile>,
    accountCountFor: (Profile) -> Int,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<Profile?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage profiles") },
        text = {
            if (profiles.isEmpty()) {
                Text("No profiles.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    profiles.forEach { profile ->
                        var name by remember(profile.id) { mutableStateOf(profile.name) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Dot(parseHexColor(profile.color))
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                trailingIcon = {
                                    if (name.trim().isNotBlank() && name.trim() != profile.name) {
                                        TextButton(onClick = { onRename(profile.id, name.trim()) }) { Text("Save") }
                                    }
                                },
                            )
                            TextButton(onClick = { confirmDelete = profile }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    val deleting = confirmDelete
    if (deleting != null) {
        val n = accountCountFor(deleting)
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete profile “${deleting.name}”?") },
            text = {
                Text(
                    if (n == 0) "This profile owns no accounts. It will be removed."
                    else "It will be removed as an owner from $n account(s); those accounts and their data stay.",
                )
            },
            confirmButton = { TextButton(onClick = { onDelete(deleting.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** Assign an account to one or more profiles (joint ownership = several). */
@Composable
internal fun EditOwnersDialog(
    accountName: String,
    profiles: List<Profile>,
    currentOwnerIds: Set<String>,
    onAddProfile: () -> Unit,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember { mutableStateListOf<String>().apply { addAll(currentOwnerIds) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Owners of “$accountName”") },
        text = {
            if (profiles.isEmpty()) {
                Column {
                    Text("No profiles yet — add one first, then assign it here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onAddProfile) { Text("+ Add profile") }
                }
            } else {
                Column(Modifier.width(320.dp)) {
                    Text("Tick everyone who owns this account (joint = several).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    profiles.forEach { profile ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (selected.contains(profile.id)) selected.remove(profile.id) else selected.add(profile.id)
                            },
                        ) {
                            Checkbox(checked = selected.contains(profile.id), onCheckedChange = {
                                if (it) selected.add(profile.id) else selected.remove(profile.id)
                            })
                            Dot(parseHexColor(profile.color)); Spacer(Modifier.width(6.dp)); Text(profile.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (profiles.isNotEmpty()) TextButton(onClick = { onSave(selected.toList()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun AddCategoryDialog(
    initialKind: CategoryKind = CategoryKind.EXPENSE,
    allowedKinds: List<CategoryKind> = CategoryKind.entries.toList(),
    onDismiss: () -> Unit,
    onAdd: (String, CategoryKind, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(initialKind) }
    var color by remember { mutableStateOf(ProfilePalette.first()) }
    var kindMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add category") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Box {
                    // Kinds are limited to those valid for the transaction's sign (income/transfer for a
                    // credit, expense/transfer for a debit), matching the picker's filtering.
                    OutlinedButton(onClick = { kindMenu = true }, enabled = allowedKinds.size > 1) { Text("Kind: ${kind.name}") }
                    DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                        allowedKinds.forEach { k ->
                            DropdownMenuItem(text = { Text(k.name) }, onClick = { kind = k; kindMenu = false })
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Colour", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                FlowRowChips {
                    ProfilePalette.forEach { hex ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(parseHexColor(hex))
                                .clickable { color = hex }
                                .then(if (color == hex) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onAdd(name.trim(), kind, color) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Lists all categories; the built-in (system) ones are labelled and protected, while user-created
 * categories can be deleted. Deleting uncategorizes any transactions that used it (see
 * [org.fuchss.projectvault.data.VaultRepository.deleteCategory]).
 */
@Composable
internal fun ManageCategoriesDialog(categories: List<Category>, onDelete: (String) -> Unit, onDismiss: () -> Unit) {
    var confirmDelete by remember { mutableStateOf<Category?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage categories") },
        text = {
            Column(Modifier.width(380.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                categories.forEach { c ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Dot(parseHexColor(c.color))
                        Text(c.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Badge(c.kind.name.lowercase())
                        if (c.isSystem == 1L) {
                            Text("built-in", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            TextButton(onClick = { confirmDelete = c }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    val deleting = confirmDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete category “${deleting.name}”?") },
            text = { Text("Transactions in this category become uncategorized and its learned rules are removed. This can't be undone.") },
            confirmButton = { TextButton(onClick = { onDelete(deleting.id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun AddAccountDialog(
    profiles: List<Profile>,
    onDismiss: () -> Unit,
    onAdd: (String, AccountType, String, String, List<String>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(AccountType.GIRO) }
    var institution by remember { mutableStateOf(ImportSupport.defaultBank(AccountType.GIRO)) }
    var iban by remember { mutableStateOf("") }
    var typeMenu by remember { mutableStateOf(false) }
    val ownerIds = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add account") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Box {
                    OutlinedButton(onClick = { typeMenu = true }) { Text("Type: ${accountTypeLabel(type)}") }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        AccountType.entries.forEach { t ->
                            DropdownMenuItem(
                                text = {
                                    Text(accountTypeLabel(t) + if (ImportSupport.isSupported(t)) "" else "  ·  no import")
                                },
                                onClick = {
                                    // Pre-fill the bank when the field is empty or still the previous type's default.
                                    if (institution.isBlank() || institution == ImportSupport.defaultBank(type)) {
                                        institution = ImportSupport.defaultBank(t)
                                    }
                                    type = t; typeMenu = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(ImportSupport.hint(type), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = institution, onValueChange = { institution = it }, label = { Text("Bank (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = iban, onValueChange = { iban = it }, label = { Text("IBAN (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (profiles.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Owners (joint = several)", style = MaterialTheme.typography.labelMedium)
                    profiles.forEach { profile ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                            if (ownerIds.contains(profile.id)) ownerIds.remove(profile.id) else ownerIds.add(profile.id)
                        }) {
                            Checkbox(checked = ownerIds.contains(profile.id), onCheckedChange = {
                                if (it) ownerIds.add(profile.id) else ownerIds.remove(profile.id)
                            })
                            Dot(parseHexColor(profile.color)); Spacer(Modifier.width(6.dp)); Text(profile.name)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onAdd(name.trim(), type, institution, iban, ownerIds.toList()) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ImportReviewDialog(previews: List<ImportPreview>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val multi = previews.size > 1
    val totalItems = previews.sumOf { it.rowCount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (multi) "Review import · ${previews.size} files" else "Review import") },
        text = {
            Column {
                if (multi) {
                    Text("$totalItems items across ${previews.size} files", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(4.dp))
                }
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    previews.forEachIndexed { index, preview ->
                        item {
                            Column(Modifier.padding(top = if (index == 0) 0.dp else 10.dp)) {
                                Text(preview.sourceName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(preview.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                preview.warnings.forEach { warning ->
                                    Text("⚠ $warning", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                }
                                if (!preview.ok) {
                                    if (preview.verifiable) {
                                        Text("Does not reconcile (may be incomplete/redacted) — you can still import.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                    } else {
                                        Text("Balance not verifiable from this export (no opening balance) — safe to import.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                        when (preview) {
                            is ImportPreview.Transactions -> items(preview.rows) { row ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Text(row.bookingDate.toString(), Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
                                    Text(row.counterparty ?: row.purpose, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    Text(formatCents(row.amountCents), style = MaterialTheme.typography.bodySmall, color = if (row.amountCents < 0) MoneyNegative else MoneyPositive)
                                }
                            }
                            is ImportPreview.Depot -> items(preview.rows) { row ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Text(row.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    Text(formatCents(row.marketValueCents), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(if (multi) "Import all" else "Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
