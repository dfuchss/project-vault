@file:OptIn(ExperimentalMaterial3Api::class)

package org.fuchss.projectvault.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fuchss.projectvault.classification.DjlEmbedder
import org.fuchss.projectvault.data.Vault
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.data.db.Account
import org.fuchss.projectvault.data.db.Category
import org.fuchss.projectvault.data.db.ImportBatch
import org.fuchss.projectvault.data.db.Profile
import org.fuchss.projectvault.data.db.Txn
import org.fuchss.projectvault.model.AccountType
import org.fuchss.projectvault.quotes.BoerseFrankfurtQuoteProvider

/**
 * A pending "apply to matching transactions?" confirmation before a bulk reclassification.
 *
 * [keyword] is the token the correction would learn — carried here so the dialog can name it. It is
 * usually the merchant, but on a statement line with no counterparty it comes from the purpose, so
 * the dialog must show what it actually is rather than claim "this merchant".
 */
private data class PendingReclassify(
    val accountId: String,
    val txn: Txn,
    val categoryId: String,
    val otherCount: Int,
    val keyword: String,
)

// ---------------------------------------------------------------- Main screen

@Composable
internal fun MainScreen(
    vault: Vault,
    initialAccountId: String? = null,
    prefs: AppPrefs = AppPrefs.default(),
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeChange: (ThemeMode) -> Unit = {},
    language: AppLanguage = AppLanguage.EN,
    onLanguageChange: (AppLanguage) -> Unit = {},
    onClose: () -> Unit,
) {
    val strings = LocalStrings.current
    val repo = remember(vault) { VaultRepository(vault) }
    val importService = remember(repo) { ImportService(repo) }
    // One embedder instance for the whole session: the Categorizer classifies with it and the
    // Classification screen reports on it, so "model loaded" there means the model that actually runs.
    val embedder = remember { DjlEmbedder() }
    val categorizer = remember(repo, embedder) { Categorizer(repo, embedder) }
    // Bulk category assignment + its undo, used by both transaction lists (account view and inbox).
    val bulk = remember(repo, categorizer) { BulkAssign(repo, categorizer) }
    // Constructed eagerly, but inert until an account opts in — see QuoteRefreshService.refresh.
    val quoteRefresh = remember(repo) { QuoteRefreshService(repo, BoerseFrankfurtQuoteProvider()) }
    remember(repo) { categorizer.ensureSeeded() } // install seed categories/rules on first open
    var refresh by remember { mutableStateOf(0) }

    val profiles = remember(refresh) { repo.profiles() }
    val accounts = remember(refresh) { repo.accounts() }
    val owners = remember(refresh) { accounts.associate { it.id to repo.profilesForAccount(it.id) } }
    val balances = remember(refresh) { accounts.associate { it.id to repo.currentBalanceCents(it.id) } }
    val categoryById = remember(refresh) { repo.categories().associateBy { it.id } }

    var filterProfileId by remember { mutableStateOf<String?>(null) }
    var selectedAccountId by remember { mutableStateOf(initialAccountId) }
    var showDashboard by remember { mutableStateOf(initialAccountId == null) }
    var showClassification by remember { mutableStateOf(false) }
    var showTriage by remember { mutableStateOf(false) }
    var showAddAccount by remember { mutableStateOf(false) }
    var showAddProfile by remember { mutableStateOf(false) }
    var showManageProfiles by remember { mutableStateOf(false) }
    var editOwnersAccount by remember { mutableStateOf<Account?>(null) }
    var showAddCategory by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf<Category?>(null) }
    var showManageCategories by remember { mutableStateOf(false) }
    var pendingReclassify by remember { mutableStateOf<PendingReclassify?>(null) }
    var pendingDeleteBatch by remember { mutableStateOf<ImportBatch?>(null) }
    var pendingDeleteAccount by remember { mutableStateOf<Account?>(null) }
    var pendingEnableQuotes by remember { mutableStateOf<Account?>(null) }
    var previews by remember { mutableStateOf<List<ImportPreview>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) } // non-null = a long task is running
    val scope = rememberCoroutineScope()

    // Live securities prices. Reached only from the Depot pane's refresh button — there is no
    // automatic or background fetch — and kept off the UI thread since it does one HTTP call per
    // position. A failed lookup changes nothing; it just reports back in the status line.
    fun refreshQuotes(accountId: String) {
        busy = strings.fetchingQuotes
        scope.launch {
            val result = withContext(Dispatchers.IO) { quoteRefresh.refresh(accountId) }
            status = when (result) {
                is QuoteRefreshService.Result.Refreshed ->
                    strings.quotesRefreshed(result.repriced, result.carried, formatCents(result.totalCents))
                QuoteRefreshService.Result.NoStatement -> strings.quotesNoStatement
                QuoteRefreshService.Result.StatementToday -> strings.quotesStatementToday
                QuoteRefreshService.Result.NoQuotes, QuoteRefreshService.Result.Unavailable -> strings.quotesUnavailable
            }
            refresh++
            busy = null
        }
    }

    // A correction spreads by the **keyword** it would teach, not by the merchant as such: the
    // keyword is the merchant when the statement names one, and a word from the purpose otherwise,
    // and it is matched against counterparty and purpose alike. The bulk apply is offered only when
    // it would really change something, and the dialog names the keyword so its reach is visible.
    fun proposeCategory(accountId: String, txn: Txn, categoryId: String) {
        val keyword = categorizer.learnedKeywordFor(txn)
        val others = if (keyword == null) 0 else categorizer.otherMatchesCount(accountId, txn, categoryId)
        if (keyword != null && others > 0) {
            pendingReclassify = PendingReclassify(accountId, txn, categoryId, others, keyword)
        } else {
            categorizer.applyToOne(txn, categoryId)
            refresh++
        }
    }

    // Re-running the classifier walks every transaction of every account and can load the embedding
    // model, so it goes off the UI thread behind the busy scrim exactly like a categorize pass does.
    fun reclassify(reclassifyScope: ReclassifyScope) {
        busy = strings.reclassifyRunning
        scope.launch {
            // The scrim is cleared in a `finally`: the pass can throw (loading the embedding model
            // is the likeliest way), and without this the window would stay blocked with no way out
            // but restarting the app.
            try {
                val r = withContext(Dispatchers.IO) { categorizer.reclassify(reclassifyScope) }
                status = strings.reclassifyResult(r.cleared, r.committed, r.suggested)
            } catch (e: Exception) {
                status = strings.reclassifyFailed(e.message ?: e.toString())
            } finally {
                refresh++
                busy = null
            }
        }
    }

    // `prefs` (passed in) persists onboarding-hint dismissals + theme/last-vault across restarts.
    var profilesHintVisible by remember { mutableStateOf(!prefs.getBool(AppPrefs.HINT_PROFILES_DISMISSED, false)) }
    var accountsHintVisible by remember { mutableStateOf(!prefs.getBool(AppPrefs.HINT_ACCOUNTS_DISMISSED, false)) }

    val visibleAccounts = accounts.filter { acc ->
        filterProfileId == null || owners[acc.id].orEmpty().any { it.id == filterProfileId }
    }
    // The inbox badge. A COUNT per visible account (see VaultRepository.uncategorizedCount) rather
    // than reading every transaction, since it is recomputed on every refresh.
    val reviewPending = remember(refresh, visibleAccounts) {
        visibleAccounts.filter { it.type != AccountType.DEPOT }.sumOf { repo.uncategorizedCount(it.id) }.toInt()
    }
    val selected = accounts.firstOrNull { it.id == selectedAccountId }

    // The whole window sits on a softly graded backdrop; panels lift off it with their own fills.
    Box(Modifier.fillMaxSize().background(appBackgroundBrush())) {
      Column(Modifier.fillMaxSize()) {
        // top bar — a translucent band so the backdrop's glow shows through behind the wordmark
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(rememberClasspathPainter("branding/app-icon.png"), contentDescription = null, modifier = Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)))
            Spacer(Modifier.width(10.dp))
            Wordmark(MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Text(vault.path.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            LanguageToggle(language, onLanguageChange)
            Spacer(Modifier.width(4.dp))
            ThemeToggle(themeMode, onThemeChange)
            Spacer(Modifier.width(4.dp))
            IconAction(onClick = onClose) { LogoutGlyph(it) }
        }
        HorizontalDivider(color = hairline())

        Row(Modifier.fillMaxSize()) {
            Sidebar(
                profiles = profiles,
                accounts = visibleAccounts,
                owners = owners,
                balances = balances,
                filterProfileId = filterProfileId,
                selectedAccountId = if (showDashboard || showClassification || showTriage) null else selectedAccountId,
                dashboardSelected = showDashboard,
                classificationSelected = showClassification,
                triageSelected = showTriage,
                reviewPending = reviewPending,
                onOverview = { showDashboard = true; showClassification = false; showTriage = false; selectedAccountId = null; status = null },
                onClassification = { showClassification = true; showDashboard = false; showTriage = false; selectedAccountId = null; status = null },
                onTriage = { showTriage = true; showDashboard = false; showClassification = false; selectedAccountId = null; status = null },
                onFilter = { filterProfileId = it },
                onSelectAccount = { selectedAccountId = it; showDashboard = false; showClassification = false; showTriage = false; status = null },
                onAddAccount = { showAddAccount = true },
                onAddProfile = { showAddProfile = true },
                onManageProfiles = { showManageProfiles = true },
                profilesHintVisible = profilesHintVisible,
                accountsHintVisible = accountsHintVisible,
                onDismissProfilesHint = { profilesHintVisible = false; prefs.setBool(AppPrefs.HINT_PROFILES_DISMISSED, true) },
                onDismissAccountsHint = { accountsHintVisible = false; prefs.setBool(AppPrefs.HINT_ACCOUNTS_DISMISSED, true) },
            )
            VerticalDivider(color = hairline())
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
              Box(Modifier.widthIn(max = 1200.dp).fillMaxWidth().fillMaxHeight().padding(20.dp)) {
                if (showTriage) {
                    TriageScreen(
                        repo = repo,
                        accounts = visibleAccounts,
                        // Only enabled categories are selectable, as in the account view.
                        categories = categoryById.values.filter { it.enabled == 1L }.toList(),
                        categoryById = categoryById,
                        bulk = bulk,
                        refreshKey = refresh,
                        status = status,
                        // A single correction from the inbox behaves exactly as it does in the
                        // account view — including the offer to learn a rule from it.
                        onSetCategory = { accountId, txn, categoryId -> proposeCategory(accountId, txn, categoryId) },
                        onAcceptSuggestion = { accountId, txn, categoryId -> proposeCategory(accountId, txn, categoryId) },
                        onDismissSuggestion = { txn -> categorizer.dismissSuggestion(txn); refresh++ },
                        onManageCategories = { showManageCategories = true },
                        onChanged = { refresh++ },
                    )
                } else if (showClassification) {
                    ClassificationScreen(
                        repo = repo,
                        categorizer = categorizer,
                        embedder = embedder,
                        // Only enabled categories can be targeted by a rule — a disabled one is
                        // ignored by the engine anyway (see Categorizer.ruleEngine).
                        categories = categoryById.values.filter { it.enabled == 1L }.sortedBy { it.name },
                        categoryById = categoryById,
                        accountCount = accounts.size,
                        refreshKey = refresh,
                        status = status,
                        onRulesChanged = { refresh++ },
                        onReclassify = ::reclassify,
                    )
                } else if (showDashboard) {
                    DashboardScreen(visibleAccounts, repo, categoryById, balances, refresh)
                } else if (selected == null) {
                    EmptyHint(if (accounts.isEmpty()) strings.addAccountToStart else strings.selectAccount)
                } else {
                    AccountDetail(
                        account = selected,
                        repo = repo,
                        owners = owners[selected.id].orEmpty(),
                        balance = balances[selected.id],
                        refreshKey = refresh,
                        status = status,
                        // Only enabled categories are selectable/filterable; categoryById keeps the full
                        // set so chips on any lingering reference still render.
                        categories = categoryById.values.filter { it.enabled == 1L },
                        categoryById = categoryById,
                        bulk = bulk,
                        onImport = {
                            val files = openFileDialogs(strings.importStatementsDialogTitle)
                            if (files.isNotEmpty()) {
                                busy = strings.readingStatements(files.size)
                                scope.launch {
                                    // PDF parsing is heavy — run it off the UI thread so the app stays responsive.
                                    val results = withContext(Dispatchers.IO) { files.map { runCatching { importService.preview(selected, it) } } }
                                    previews = results.mapNotNull { it.getOrNull() }
                                    val failed = results.count { it.isFailure }
                                    status = when {
                                        failed == 0 -> null
                                        previews.isEmpty() -> strings.importFailed(results.first { it.isFailure }.exceptionOrNull()?.message)
                                        else -> strings.filesSkipped(failed)
                                    }
                                    busy = null
                                }
                            }
                        },
                        // A single correction only touches this transaction. Applying it to every
                        // matching transaction (and learning a rule) is offered explicitly when there
                        // are siblings — never silently, so one merchant's varied purchases don't get
                        // mass-miscategorized. See proposeCategory.
                        onSetCategory = { txn, categoryId -> proposeCategory(selected.id, txn, categoryId) },
                        onAcceptSuggestion = { txn, categoryId -> proposeCategory(selected.id, txn, categoryId) },
                        onDismissSuggestion = { txn -> categorizer.dismissSuggestion(txn); refresh++ },
                        onDeleteBatch = { batch -> pendingDeleteBatch = batch },
                        // Live prices only ever run from this button — never on open, never on a timer.
                        onRefreshQuotes = { refreshQuotes(selected.id) },
                        onEnableQuotes = { pendingEnableQuotes = selected },
                        onDeleteAccount = { pendingDeleteAccount = selected },
                        onEditOwners = { editOwnersAccount = selected },
                        onManageCategories = { showManageCategories = true },
                        onClassify = {
                            busy = strings.categorizing
                            scope.launch {
                                // Categorization can load the embedding model — keep it off the UI thread.
                                val r = withContext(Dispatchers.IO) { categorizer.classifyAccount(selected.id) }
                                status = strings.categorizeResult(r.committed, r.suggested)
                                refresh++; busy = null
                            }
                        },
                        onChanged = { refresh++ },
                    )
                }
              }
            }
        }
      }

      val busyMessage = busy
      if (busyMessage != null) {
          Box(
              Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                  // Consume clicks so the (busy) UI behind the scrim can't be interacted with.
                  .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
              contentAlignment = Alignment.Center,
          ) {
              VaultCard(padding = PaddingValues(24.dp)) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                      CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                      Spacer(Modifier.width(16.dp))
                      Text(busyMessage, style = MaterialTheme.typography.bodyMedium)
                  }
              }
          }
      }
    }

    if (showAddProfile) {
        AddProfileDialog(onDismiss = { showAddProfile = false }, onAdd = { name, color ->
            repo.addProfile(name, color); refresh++; showAddProfile = false
        })
    }
    if (showManageProfiles) {
        ManageProfilesDialog(
            profiles = profiles,
            accountCountFor = { p -> accounts.count { acc -> owners[acc.id].orEmpty().any { it.id == p.id } } },
            onRename = { id, name -> repo.updateProfile(id, name, profiles.first { it.id == id }.color); refresh++ },
            onDelete = { id ->
                repo.deleteProfile(id)
                if (filterProfileId == id) filterProfileId = null
                refresh++
            },
            onDismiss = { showManageProfiles = false },
        )
    }
    val ownersAccount = editOwnersAccount
    if (ownersAccount != null) {
        EditOwnersDialog(
            accountName = ownersAccount.name,
            profiles = profiles,
            currentOwnerIds = owners[ownersAccount.id].orEmpty().map { it.id }.toSet(),
            onAddProfile = { showAddProfile = true },
            onSave = { ids -> repo.setAccountOwners(ownersAccount.id, ids); editOwnersAccount = null; refresh++ },
            onDismiss = { editOwnersAccount = null },
        )
    }
    val deleteAccount = pendingDeleteAccount
    if (deleteAccount != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteAccount = null },
            title = { Text(strings.deleteAccountTitle) },
            text = { Text(strings.deleteAccountBody(deleteAccount.name)) },
            confirmButton = {
                TextButton(onClick = {
                    repo.deleteAccount(deleteAccount.id)
                    if (selectedAccountId == deleteAccount.id) { selectedAccountId = null; showDashboard = true }
                    pendingDeleteAccount = null; status = null; refresh++
                }) { Text(strings.delete) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteAccount = null }) { Text(strings.cancel) } },
        )
    }
    val enableQuotesFor = pendingEnableQuotes
    if (enableQuotesFor != null) {
        // The one-time consent step: nothing has touched the network before this is confirmed.
        AlertDialog(
            onDismissRequest = { pendingEnableQuotes = null },
            title = { Text(strings.enableLiveQuotesTitle) },
            text = { Text(strings.enableLiveQuotesBody) },
            confirmButton = {
                TextButton(onClick = {
                    repo.setLiveQuotesEnabled(enableQuotesFor.id, true)
                    pendingEnableQuotes = null
                    refresh++
                    refreshQuotes(enableQuotesFor.id)
                }) { Text(strings.enableLiveQuotesConfirm) }
            },
            dismissButton = { TextButton(onClick = { pendingEnableQuotes = null }) { Text(strings.cancel) } },
        )
    }
    val deleteBatch = pendingDeleteBatch
    if (deleteBatch != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteBatch = null },
            title = { Text(strings.undoImportTitle) },
            text = { Text(strings.undoImportBody(deleteBatch.sourceName, deleteBatch.itemCount.toInt())) },
            confirmButton = {
                TextButton(onClick = { repo.deleteBatch(deleteBatch.id); pendingDeleteBatch = null; status = strings.removedImport(deleteBatch.sourceName); refresh++ }) { Text(strings.remove) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteBatch = null }) { Text(strings.cancel) } },
        )
    }
    val reclassify = pendingReclassify
    if (reclassify != null) {
        ApplyToMatchingDialog(
            initialKeyword = reclassify.keyword,
            categoryName = categoryById[reclassify.categoryId]?.name ?: strings.thisCategoryFallback,
            // One read of the account's transactions, reused for every keystroke in the keyword field.
            countMatches = remember(reclassify) {
                categorizer.keywordMatchCounter(reclassify.accountId, reclassify.txn, reclassify.categoryId)
            },
            onOnlyThisOne = { categorizer.applyToOne(reclassify.txn, reclassify.categoryId); pendingReclassify = null; refresh++ },
            onApplyToAll = { keyword ->
                categorizer.setCategory(reclassify.accountId, reclassify.txn, reclassify.categoryId, keyword)
                pendingReclassify = null
                refresh++
            },
        )
    }
    if (showManageCategories) {
        ManageCategoriesDialog(
            categories = categoryById.values.sortedBy { it.name },
            txnCountFor = { id -> repo.categoryTxnCount(id).toInt() },
            onAddCategory = { showAddCategory = true },
            onEditCategory = { editingCategory = it },
            onDelete = { id -> repo.deleteCategory(id); refresh++ },
            onDisable = { id -> repo.disableCategory(id, CAT_OTHER, CAT_INCOME); refresh++ },
            onEnable = { id -> repo.enableCategory(id); refresh++ },
            onDismiss = { showManageCategories = false },
        )
    }
    if (showAddCategory) {
        // Adding from Manage isn't tied to a transaction, so every kind is offered. Keywords (if any)
        // become learned rules that classify matching transactions on the next categorize pass.
        AddCategoryDialog(
            onDismiss = { showAddCategory = false },
            onAdd = { name, kind, color, keywords ->
                categorizer.addCategory(name, kind, color, keywords)
                showAddCategory = false; refresh++
            },
        )
    }
    val editCat = editingCategory
    if (editCat != null) {
        EditCategoryDialog(
            category = editCat,
            // Read once when the dialog opens: inline this was a full categoryRules scan on every
            // recomposition of the window, and a fresh list each time re-entered the dialog with it.
            initialKeywords = remember(editCat) { categorizer.keywordsFor(editCat.id) },
            onDismiss = { editingCategory = null },
            onSave = { name, color, keywords ->
                categorizer.updateCategory(editCat.id, name, color, keywords)
                editingCategory = null; refresh++
            },
        )
    }
    if (showAddAccount) {
        AddAccountDialog(
            profiles = profiles,
            onDismiss = { showAddAccount = false },
            onAdd = { name, bank, type, iban, ownerIds ->
                // The bank is stored by its enum name so imports can be routed back to its templates.
                repo.addAccount(name, type, bank.name, iban.ifBlank { null }, ownerProfileIds = ownerIds)
                refresh++; showAddAccount = false
            },
        )
    }
    if (previews.isNotEmpty() && selected != null) {
        val account = selected
        ImportReviewDialog(previews, onDismiss = { previews = emptyList() }, onConfirm = {
            val toCommit = previews
            previews = emptyList()
            busy = strings.importingItems(toCommit.sumOf { it.rowCount })
            scope.launch {
                // Commit + categorize off the UI thread (categorization may load the embedding model).
                val n = withContext(Dispatchers.IO) { toCommit.sumOf { importService.commit(account.id, it) } }
                val r = withContext(Dispatchers.IO) { categorizer.classifyAccount(account.id) }
                val src = if (toCommit.size == 1) toCommit.first().sourceName else strings.filesLabel(toCommit.size)
                status = strings.importResult(n, src, r.committed, r.suggested)
                refresh++; busy = null
            }
        })
    }
}

/**
 * The confirmation before a correction spreads. The **keyword is editable**, because the app's guess
 * at what a correction means is only a guess: it takes the first usable token of the counterparty
 * (or of the purpose when there is no counterparty), which is the merchant often enough to be a good
 * default and wrong often enough that the user must be able to say otherwise — narrowing
 * "SUPERMARKT" to "SUPERMARKT FIL 12", or widening a payment reference to "VERSICHERUNG".
 *
 * The match count updates as the keyword is typed, so the reach of "apply to all" is visible before
 * it happens rather than discovered afterwards.
 */
@Composable
private fun ApplyToMatchingDialog(
    initialKeyword: String,
    categoryName: String,
    countMatches: (String) -> Int,
    onOnlyThisOne: () -> Unit,
    onApplyToAll: (String) -> Unit,
) {
    val strings = LocalStrings.current
    var keyword by remember { mutableStateOf(initialKeyword) }
    val trimmed = keyword.trim()
    val valid = trimmed.length >= 2
    // Counting walks an in-memory list, but only when the keyword actually changed.
    val others = remember(trimmed) { if (valid) countMatches(trimmed) else 0 }

    AlertDialog(
        onDismissRequest = onOnlyThisOne,
        title = { Text(strings.applyToSimilarTitle) },
        text = {
            Column(Modifier.width(400.dp)) {
                Text(strings.applyToSimilarIntro, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text(strings.keywordLabel) },
                    supportingText = { Text(strings.keywordRuleHelp) },
                    singleLine = true,
                    isError = keyword.isNotBlank() && !valid,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (!valid) strings.applyToSimilarNeedsKeyword
                    else if (others == 0) strings.applyToSimilarNoOthers(categoryName)
                    else strings.applyToSimilarCount(others, categoryName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onApplyToAll(trimmed) }) {
                Text(if (others == 0) strings.rememberRuleOnly else strings.applyToAll(others + 1))
            }
        },
        dismissButton = { TextButton(onClick = onOnlyThisOne) { Text(strings.onlyThisOne) } },
    )
}

// ---------------------------------------------------------------- Sidebar

@Composable
private fun Sidebar(
    profiles: List<Profile>,
    accounts: List<Account>,
    owners: Map<String, List<Profile>>,
    balances: Map<String, Long?>,
    filterProfileId: String?,
    selectedAccountId: String?,
    dashboardSelected: Boolean,
    classificationSelected: Boolean,
    triageSelected: Boolean,
    reviewPending: Int,
    onOverview: () -> Unit,
    onClassification: () -> Unit,
    onTriage: () -> Unit,
    onFilter: (String?) -> Unit,
    onSelectAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onAddProfile: () -> Unit,
    onManageProfiles: () -> Unit,
    profilesHintVisible: Boolean,
    accountsHintVisible: Boolean,
    onDismissProfilesHint: () -> Unit,
    onDismissAccountsHint: () -> Unit,
) {
    val strings = LocalStrings.current
    // A faint translucent panel: enough to separate the sidebar from the content area without a
    // hard-edged second background competing with the window's gradient.
    Column(
        Modifier.width(300.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        NavItem(strings.overview, selected = dashboardSelected, onClick = onOverview, icon = { OverviewGlyph(it) })
        Spacer(Modifier.height(6.dp))
        // The inbox sits between the overview and the classifier's settings: it is the daily task,
        // and its label carries the count so the sidebar says how much is waiting.
        // Hidden once nothing is waiting: an inbox with nothing in it is not a destination, and a
        // permanent "Review (0)" trains the eye to ignore the one place that should mean work. Same
        // reasoning as the "To review" pill and the month dropdown, which also hide when inert.
        //
        // It stays while you are standing on it, though. Filing the last row would otherwise pull the
        // item out from under you — and *navigating* away on your behalf is worse than it sounds: the
        // screen's undo offer for the bulk write you just made lives in its composition, so leaving
        // would discard the undo at exactly the moment it matters most. Letting the item linger until
        // you navigate yourself costs nothing and strands no one. It also means a profile filter that
        // happens to hide the remaining work can't eject you mid-task.
        if (reviewPending > 0 || triageSelected) {
            NavItem(strings.reviewNav(reviewPending), selected = triageSelected, onClick = onTriage, icon = { ReviewGlyph(it) })
            Spacer(Modifier.height(6.dp))
        }
        NavItem(strings.classification, selected = classificationSelected, onClick = onClassification, icon = { ClassificationGlyph(it) })
        Spacer(Modifier.height(16.dp))
        SectionHeader(strings.profiles, onAdd = onAddProfile, onManage = if (profiles.isNotEmpty()) onManageProfiles else null)
        Spacer(Modifier.height(6.dp))
        if (profiles.isEmpty()) {
            // Profiles are optional, so make clear what they're for rather than showing a lone "All" chip.
            if (profilesHintVisible) {
                EmptyCallout(
                    title = strings.noProfilesTitle,
                    body = strings.noProfilesBody,
                    action = strings.addProfileAction,
                    onAction = onAddProfile,
                    onDismiss = onDismissProfilesHint,
                )
            } else {
                Text(strings.noProfilesShort, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            FlowRowChips {
                Chip(strings.all, selected = filterProfileId == null, onClick = { onFilter(null) })
                profiles.forEach { profile ->
                    Chip(profile.name, selected = filterProfileId == profile.id, dot = parseHexColor(profile.color), onClick = { onFilter(profile.id) })
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader(strings.accounts, onAdd = onAddAccount)
        Spacer(Modifier.height(6.dp))
        if (accounts.isEmpty()) {
            if (accountsHintVisible) {
                EmptyCallout(
                    title = if (filterProfileId == null) strings.noAccountsTitle else strings.noAccountsForProfileTitle,
                    body = if (filterProfileId == null) strings.addAccountBody else strings.noAccountsForProfileBody,
                    action = if (filterProfileId == null) strings.addAccountAction else null,
                    onAction = onAddAccount,
                    onDismiss = onDismissAccountsHint,
                )
            } else {
                Text(strings.noAccountsShort, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            accounts.forEach { account ->
                AccountCard(
                    account = account,
                    owners = owners[account.id].orEmpty(),
                    balance = balances[account.id],
                    selected = account.id == selectedAccountId,
                    onClick = { onSelectAccount(account.id) },
                )
            }
        }
    }
}

/** A soft callout for empty sections: a title, a one-line explanation, an optional CTA, and a
 * dismiss ✕ so it can be collapsed to reclaim sidebar space once the hint is understood. */
@Composable
private fun EmptyCallout(title: String, body: String, action: String?, onAction: () -> Unit, onDismiss: (() -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (onDismiss != null) {
                    Surface(onClick = onDismiss, shape = CircleShape, color = Color.Transparent) {
                        Text("✕", Modifier.padding(6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(body, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) {
                Spacer(Modifier.height(10.dp))
                PrimaryButton(action, onClick = onAction)
            }
        }
    }
}

@Composable
private fun AccountCard(account: Account, owners: List<Profile>, balance: Long?, selected: Boolean, onClick: () -> Unit) {
    // Hovering lifts the card (border brightens) so the sidebar feels responsive; the selected card
    // keeps a primary-tinted fill and a heavier border.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val borderColor by animateColorAsState(
        when {
            selected -> MaterialTheme.colorScheme.primary
            hovered -> MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
            else -> hairline()
        },
        label = "account-border",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        shadowElevation = if (selected || hovered) 4.dp else 0.dp,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(account.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                owners.forEach { Dot(parseHexColor(it.color)); Spacer(Modifier.width(3.dp)) }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(accountTypeLabel(account.type))
                account.institution?.let { Spacer(Modifier.width(6.dp)); Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.weight(1f))
                Text(balance?.let(::formatCents) ?: "—", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            }
        }
    }
}
