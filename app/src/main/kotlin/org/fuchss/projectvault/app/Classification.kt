package org.fuchss.projectvault.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fuchss.projectvault.classification.CategoryRule
import org.fuchss.projectvault.classification.Embedder
import org.fuchss.projectvault.classification.RuleSource
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.data.db.Category
import org.fuchss.projectvault.data.db.CategoryRule as RuleRow

/** The sections of the Classification view — what decides a category, in the order it happens. */
private enum class ClassificationTab { RULES, MODELS, EXPLAIN, ACTIONS }

/**
 * The control room for automatic categorization: the Tier-1 keyword rules (list/search/add/edit/
 * delete plus a live tester), the two Tier-2 suggesters (on/off and their confidence thresholds,
 * stored in the vault) and the explicit re-classification runs.
 *
 * It reads the vault directly like [DashboardScreen] does, but everything expensive — the per-rule
 * match counts and the embedding model's availability — is computed once off the UI thread and
 * remembered against [refreshKey], never re-queried per recomposition.
 */
@Composable
internal fun ClassificationScreen(
    repo: VaultRepository,
    categorizer: Categorizer,
    embedder: Embedder,
    categories: List<Category>,
    categoryById: Map<String, Category>,
    accountCount: Int,
    refreshKey: Int,
    status: String?,
    onRulesChanged: () -> Unit,
    onReclassify: (ReclassifyScope) -> Unit,
) {
    val strings = LocalStrings.current
    var tab by remember { mutableStateOf(ClassificationTab.RULES) }
    val rules = remember(refreshKey) { repo.categoryRules() }

    Column(Modifier.fillMaxSize()) {
        VaultCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(strings.classification, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(strings.classificationSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                FlowRowChips {
                    Chip(strings.tabRules, selected = tab == ClassificationTab.RULES) { tab = ClassificationTab.RULES }
                    Chip(strings.tabModels, selected = tab == ClassificationTab.MODELS) { tab = ClassificationTab.MODELS }
                    Chip(strings.tabExplain, selected = tab == ClassificationTab.EXPLAIN) { tab = ClassificationTab.EXPLAIN }
                    Chip(strings.tabActions, selected = tab == ClassificationTab.ACTIONS) { tab = ClassificationTab.ACTIONS }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Hoisted so switching tabs does not throw away the Explain models and pay to rebuild them.
        val explainState = remember(refreshKey) { ExplainState() }
        when (tab) {
            ClassificationTab.RULES -> RulesTab(repo, categorizer, rules, categories, categoryById, refreshKey, onRulesChanged)
            ClassificationTab.MODELS -> ModelsTab(repo, embedder, refreshKey)
            ClassificationTab.EXPLAIN -> ExplainTab(repo, categorizer, categoryById, refreshKey, explainState)
            ClassificationTab.ACTIONS -> ActionsTab(categorizer, accountCount, refreshKey, status, onReclassify)
        }
    }
}

// ---------------------------------------------------------------- Rules

@Composable
private fun RulesTab(
    repo: VaultRepository,
    categorizer: Categorizer,
    rules: List<RuleRow>,
    categories: List<Category>,
    categoryById: Map<String, Category>,
    refreshKey: Int,
    onChanged: () -> Unit,
) {
    val strings = LocalStrings.current
    var search by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var sourceFilter by remember { mutableStateOf<String?>(null) } // null = any, else RuleSource name
    var categoryMenu by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<RuleRow?>(null) }
    var deleting by remember { mutableStateOf<RuleRow?>(null) }

    // Every account's transaction text, read **once**, then reused for all rules — the alternative
    // (a query per rule) is a few hundred round-trips for one screen.
    var matches by remember(refreshKey) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    LaunchedEffect(refreshKey, rules) {
        matches = withContext(Dispatchers.IO) {
            val texts = repo.accounts().flatMap { account ->
                repo.transactions(account.id).map { listOfNotNull(it.counterparty, it.purpose).joinToString(" ") }
            }
            ruleMatchCounts(rules, texts)
        }
    }
    val removedBuiltins = remember(refreshKey) { categorizer.suppressedSeedRules() }

    val filtered = remember(rules, search, categoryFilter, sourceFilter) {
        val needle = search.trim().uppercase()
        rules.filter { rule ->
            (needle.isEmpty() || rule.keyword.uppercase().contains(needle)) &&
                (categoryFilter == null || rule.categoryId == categoryFilter) &&
                (sourceFilter == null || rule.source == sourceFilter)
        }
    }

    Row(Modifier.fillMaxSize()) {
        VaultCard(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        strings.rulesHeader(filtered.size, rules.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(strings.addRuleButton, onClick = { adding = true })
                }
                Spacer(Modifier.height(4.dp))
                Text(strings.rulesIntro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The "Matches" column counts occurrences, not wins — worth saying once, up here,
                // rather than letting the number be read as "this rule categorized N transactions".
                Text(strings.matchesHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                SearchField(search, { search = it }, Modifier.fillMaxWidth(), placeholder = strings.searchKeywordPlaceholder)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val activeCategory = categoryFilter?.let { categoryById[it] }
                    Box {
                        SelectPill(
                            prefix = strings.columnCategory,
                            label = activeCategory?.name ?: strings.all,
                            expanded = categoryMenu,
                            active = activeCategory != null,
                            leadingDot = activeCategory?.let { parseHexColor(it.color) },
                            onClick = { categoryMenu = true },
                        )
                        VaultMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                            VaultMenuItem(strings.all, selected = categoryFilter == null, onClick = { categoryFilter = null; categoryMenu = false })
                            VaultMenuDivider()
                            categories.forEach { c ->
                                VaultMenuItem(
                                    label = c.name,
                                    selected = c.id == categoryFilter,
                                    leadingDot = parseHexColor(c.color),
                                    onClick = { categoryFilter = c.id; categoryMenu = false },
                                )
                            }
                        }
                    }
                    Box {
                        SelectPill(
                            prefix = strings.sourcePrefix,
                            label = sourceFilter?.let { strings.ruleSourceLabel(it) } ?: strings.sourceAny,
                            expanded = sourceMenu,
                            active = sourceFilter != null,
                            onClick = { sourceMenu = true },
                        )
                        VaultMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                            VaultMenuItem(strings.sourceAny, selected = sourceFilter == null, onClick = { sourceFilter = null; sourceMenu = false })
                            RuleSource.entries.forEach { s ->
                                VaultMenuItem(
                                    label = strings.ruleSourceLabel(s.name),
                                    selected = sourceFilter == s.name,
                                    onClick = { sourceFilter = s.name; sourceMenu = false },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                RuleColumnHeader()
                Spacer(Modifier.height(4.dp))
                if (filtered.isEmpty()) {
                    EmptyHint(strings.noRulesMatchFilter)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(filtered, key = { it.id }) { rule ->
                            RuleRowItem(
                                rule = rule,
                                category = categoryById[rule.categoryId],
                                matchCount = matches[rule.id],
                                onEdit = { editing = rule },
                                onDelete = { deleting = rule },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.width(330.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
            RuleTester(categorizer, categoryById)
            if (removedBuiltins.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                RemovedBuiltins(removedBuiltins, categoryById) { rule ->
                    categorizer.restoreSeedRule(rule)
                    onChanged()
                }
            }
        }
    }

    if (adding) {
        RuleDialog(
            rule = null,
            categories = categories,
            onDismiss = { adding = false },
            onSave = { keyword, categoryId, priority ->
                categorizer.addRule(keyword, categoryId, priority)
                adding = false
                onChanged()
            },
        )
    }
    val editRule = editing
    if (editRule != null) {
        RuleDialog(
            rule = editRule,
            categories = categories,
            onDismiss = { editing = null },
            onSave = { keyword, categoryId, priority ->
                categorizer.updateRule(editRule, keyword, categoryId, priority)
                editing = null
                onChanged()
            },
        )
    }
    val deleteRule = deleting
    if (deleteRule != null) {
        val categoryName = categoryById[deleteRule.categoryId]?.name ?: strings.unknownCategory
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(strings.deleteRuleTitle) },
            text = {
                Column {
                    Text(strings.deleteRuleBody(deleteRule.keyword, categoryName))
                    // Deleting a built-in is the case that needs explaining: it is remembered, not
                    // just removed, or the next seed reconcile would put it straight back.
                    if (deleteRule.source == RuleSource.SEED.name) {
                        Spacer(Modifier.height(8.dp))
                        Text(strings.deleteSeedRuleNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { categorizer.deleteRule(deleteRule); deleting = null; onChanged() }) {
                    Text(strings.delete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(strings.cancel) } },
        )
    }
}

/** Column captions for the rule table — the same widths the rows use, so everything lines up. */
@Composable
private fun RuleColumnHeader() {
    val strings = LocalStrings.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(strings.columnKeyword, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted)
        Text(strings.columnCategory, Modifier.width(RuleCategoryWidth), style = MaterialTheme.typography.labelSmall, color = muted)
        Text(strings.columnPriority, Modifier.width(RulePriorityWidth), style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.End)
        Text(strings.columnMatches, Modifier.width(RuleMatchesWidth), style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.End)
        Spacer(Modifier.width(RuleActionsWidth))
    }
}

private val RuleCategoryWidth = 190.dp
private val RulePriorityWidth = 62.dp
private val RuleMatchesWidth = 62.dp
private val RuleActionsWidth = 64.dp

@Composable
private fun RuleRowItem(
    rule: RuleRow,
    category: Category?,
    matchCount: Int?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by animateColorAsState(
        if (hovered) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        label = "rule-row",
    )
    Surface(
        onClick = onEdit,
        shape = RoundedCornerShape(10.dp),
        color = container,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rule.keyword, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    strings.ruleSourceLabel(rule.source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.width(RuleCategoryWidth)) {
                if (category != null) CategoryChip(category)
                else Text(strings.unknownCategory, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                rule.priority.toString(),
                Modifier.width(RulePriorityWidth),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
            )
            Text(
                // "—" while the off-thread count is still running, so the table never blocks on it.
                matchCount?.toString() ?: "—",
                Modifier.width(RuleMatchesWidth),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if ((matchCount ?: 0) > 0) FontWeight.Medium else FontWeight.Normal,
                color = if (matchCount == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
            )
            Row(Modifier.width(RuleActionsWidth), horizontalArrangement = Arrangement.End) {
                RowAction("✎", onEdit)
                RowAction("✕", onDelete)
            }
        }
    }
}

/** A tiny borderless glyph button used inside a dense row (edit / delete). */
@Composable
private fun RowAction(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier.size(24.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The "why did it pick that?" panel: type a sample counterparty/purpose and see the winning rule and
 * the category it would commit, straight from [Categorizer.explain] (i.e. `RuleEngine.bestRule`) — so
 * the priority / earliest-match / longest-keyword ordering is inspectable instead of folklore.
 */
@Composable
private fun RuleTester(categorizer: Categorizer, categoryById: Map<String, Category>) {
    val strings = LocalStrings.current
    var sample by remember { mutableStateOf("") }
    // Re-evaluated only when the text changes — cheap, but no reason to run it on every recomposition.
    val winner: CategoryRule? = remember(sample) { sample.takeIf { it.isNotBlank() }?.let { categorizer.explain(it) } }

    VaultCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(strings.testRuleHeader, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(strings.testRuleIntro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            // This box is Tier 1 only, and deliberately so — it is an affordance of the rule editor.
            // The full decision path (sign constraint, both Tier-2 models, the merge) is its own tab.
            Text(strings.testRuleSeeExplain, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = sample,
                onValueChange = { sample = it },
                label = { Text(strings.testRulePlaceholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            when {
                sample.isBlank() -> Unit
                winner == null -> Text(strings.testRuleNoMatch, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text(
                        strings.testRuleWinner(winner.keyword, strings.ruleSourceLabel(winner.source.name)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(strings.testRuleCommits, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        categoryById[winner.categoryId]?.let { CategoryChip(it) }
                    }
                }
            }
        }
    }
}

/** Built-in keywords the user deleted. Listed so a removal is visible and reversible, not a black hole. */
@Composable
private fun RemovedBuiltins(
    removed: List<CategoryRule>,
    categoryById: Map<String, Category>,
    onRestore: (CategoryRule) -> Unit,
) {
    val strings = LocalStrings.current
    VaultCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(strings.removedBuiltinsHeader(removed.size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            removed.forEach { rule ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.keyword, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        categoryById[rule.categoryId]?.let {
                            Text(it.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = { onRestore(rule) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text(strings.restore, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * Add / edit one rule. Editing a built-in rule takes it over (it becomes a learned rule), which the
 * note in the dialog spells out — see [Categorizer.updateRule].
 */
@Composable
private fun RuleDialog(
    rule: RuleRow?,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (keyword: String, categoryId: String, priority: Int) -> Unit,
) {
    val strings = LocalStrings.current
    var keyword by remember { mutableStateOf(rule?.keyword ?: "") }
    var categoryId by remember { mutableStateOf(rule?.categoryId ?: categories.firstOrNull()?.id) }
    var priorityText by remember { mutableStateOf((rule?.priority ?: 100L).toString()) }
    var categoryMenu by remember { mutableStateOf(false) }

    val priority = priorityText.trim().toIntOrNull()
    val chosen = categories.firstOrNull { it.id == categoryId }
    val valid = keyword.trim().length >= 2 && chosen != null && priority != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule == null) strings.addRuleTitle else strings.editRuleTitle) },
        text = {
            Column(Modifier.width(380.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text(strings.keywordLabel) },
                    supportingText = { Text(strings.keywordRuleHelp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Box {
                    SelectPill(
                        prefix = strings.columnCategory,
                        label = chosen?.name ?: strings.none,
                        leadingDot = chosen?.let { parseHexColor(it.color) },
                        expanded = categoryMenu,
                        onClick = { categoryMenu = true },
                    )
                    VaultMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        categories.forEach { c ->
                            VaultMenuItem(
                                label = c.name,
                                selected = c.id == categoryId,
                                leadingDot = parseHexColor(c.color),
                                onClick = { categoryId = c.id; categoryMenu = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = priorityText,
                    onValueChange = { priorityText = it },
                    label = { Text(strings.priorityLabel) },
                    supportingText = { Text(strings.priorityHelp) },
                    singleLine = true,
                    isError = priorityText.isNotBlank() && priority == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (rule?.source == RuleSource.SEED.name) {
                    Spacer(Modifier.height(10.dp))
                    Text(strings.editSeedRuleNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(keyword.trim(), chosen!!.id, priority!!) }) {
                Text(if (rule == null) strings.add else strings.save)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
}

// ---------------------------------------------------------------- Models (Tier 2)

@Composable
private fun ModelsTab(repo: VaultRepository, embedder: Embedder, refreshKey: Int) {
    val strings = LocalStrings.current
    var settings by remember(refreshKey) { mutableStateOf(ClassifierSettings.load(repo)) }
    // Loading the ONNX model takes a moment, so availability is resolved off the UI thread; null =
    // still checking. The check is what loads the model, so it runs once per screen, not per frame.
    var embeddingAvailable by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(embedder) { embeddingAvailable = withContext(Dispatchers.IO) { embedder.available() } }

    fun persist(updated: ClassifierSettings) {
        settings = updated
        ClassifierSettings.save(repo, updated)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(strings.modelsIntro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(12.dp))

        ModelCard(
            name = strings.statisticalModelName,
            description = strings.statisticalModelDesc,
            statusText = strings.modelStatusAlwaysOn,
            statusOk = true,
            enabled = settings.statisticalEnabled,
            onEnabledChange = { persist(settings.copy(statisticalEnabled = it)) },
            thresholdLabel = strings.thresholdConfidence,
            threshold = settings.statisticalThreshold,
            default = ClassifierSettings.DEFAULT_STATISTICAL_THRESHOLD,
            range = ClassifierSettings.ThresholdRange,
            thresholdHelp = strings.thresholdHelp,
            onThresholdChange = { settings = settings.copy(statisticalThreshold = it) },
            onThresholdCommit = { ClassifierSettings.save(repo, settings) },
        )
        Spacer(Modifier.height(12.dp))
        ModelCard(
            name = strings.embeddingModelName,
            description = strings.embeddingModelDesc,
            statusText = when (embeddingAvailable) {
                null -> strings.modelStatusChecking
                true -> strings.modelStatusLoaded
                false -> strings.modelStatusMissing
            },
            statusOk = embeddingAvailable == true,
            enabled = settings.embeddingEnabled,
            // A build without the model can't run it whatever the switch says, so the control is
            // disabled rather than silently ineffective.
            controlsEnabled = embeddingAvailable == true,
            note = if (embeddingAvailable == false) strings.modelMissingHint else null,
            onEnabledChange = { persist(settings.copy(embeddingEnabled = it)) },
            thresholdLabel = strings.thresholdMargin,
            threshold = settings.embeddingMargin,
            default = ClassifierSettings.DEFAULT_EMBEDDING_MARGIN,
            // A margin lives in a much smaller span than a probability, and the useful part of it is
            // narrow (0.02 vs 0.05 is a real difference), so the slider is stepped and shown to three
            // decimals instead of pretending to the same 0.30-0.95 scale as the statistical one.
            range = ClassifierSettings.MarginRange,
            steps = 29,
            decimals = 3,
            thresholdHelp = strings.marginHelp,
            onThresholdChange = { settings = settings.copy(embeddingMargin = it) },
            onThresholdCommit = { ClassifierSettings.save(repo, settings) },
        )

        if (!settings.statisticalEnabled && !settings.embeddingEnabled) {
            Spacer(Modifier.height(12.dp))
            WarningNote(strings.bothModelsOffWarning)
        }
        Spacer(Modifier.height(12.dp))
        Text(strings.settingsScopeNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun ModelCard(
    name: String,
    description: String,
    statusText: String,
    statusOk: Boolean,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    thresholdLabel: String,
    threshold: Double,
    default: Double,
    range: ClosedFloatingPointRange<Float>,
    thresholdHelp: String,
    onThresholdChange: (Double) -> Unit,
    onThresholdCommit: () -> Unit,
    steps: Int = 0,
    decimals: Int = 2,
    controlsEnabled: Boolean = true,
    note: String? = null,
) {
    val strings = LocalStrings.current
    VaultCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(16.dp))
                Switch(checked = enabled && controlsEnabled, onCheckedChange = onEnabledChange, enabled = controlsEnabled)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(statusOk)
                Text(statusText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (note != null) {
                Spacer(Modifier.height(8.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(thresholdLabel, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(formatThreshold(threshold, decimals), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(strings.defaultValue(formatThreshold(default, decimals)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(
                value = threshold.toFloat().coerceIn(range),
                onValueChange = { onThresholdChange(it.toDouble()) },
                // Persisted on release, not on every drag frame — one DB write per adjustment.
                onValueChangeFinished = onThresholdCommit,
                valueRange = range,
                steps = steps,
                enabled = controlsEnabled && enabled,
            )
            Text(thresholdHelp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusDot(ok: Boolean) {
    Dot(if (ok) MoneyPositive else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Thresholds read as fixed decimals — "0.65", not "0.6500000000000001" after a slider drag. */
private fun formatThreshold(value: Double, decimals: Int = 2): String =
    String.format(I18n.current.locale, "%.${decimals}f", value)

// ---------------------------------------------------------------- Actions

@Composable
private fun ActionsTab(
    categorizer: Categorizer,
    accountCount: Int,
    refreshKey: Int,
    status: String?,
    onReclassify: (ReclassifyScope) -> Unit,
) {
    val strings = LocalStrings.current
    var pending by remember { mutableStateOf<ReclassifyScope?>(null) }
    // Both counts come from one pass over the vault, refreshed only when the data changes.
    val counts = remember(refreshKey) {
        ReclassifyScope.entries.associateWith { categorizer.reclassifyCandidateCount(it) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        VaultCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text(strings.reclassifyHeader, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(strings.reclassifyIntro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                if (accountCount == 0) {
                    Text(strings.noAccountsToClassify, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    ScopeRow(
                        title = strings.scopeUncategorizedTitle,
                        description = strings.scopeUncategorizedDesc,
                        affects = counts[ReclassifyScope.UNCATEGORIZED] ?: 0,
                        onRun = { pending = ReclassifyScope.UNCATEGORIZED },
                    )
                    Spacer(Modifier.height(10.dp))
                    ScopeRow(
                        title = strings.scopeAllTitle,
                        description = strings.scopeAllDesc,
                        affects = counts[ReclassifyScope.ALL_EXCEPT_MANUAL] ?: 0,
                        onRun = { pending = ReclassifyScope.ALL_EXCEPT_MANUAL },
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(strings.manualAlwaysRespected, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    val scope = pending
    if (scope != null) {
        val label = when (scope) {
            ReclassifyScope.UNCATEGORIZED -> strings.scopeUncategorizedTitle
            ReclassifyScope.ALL_EXCEPT_MANUAL -> strings.scopeAllTitle
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(strings.reclassifyConfirmTitle) },
            text = { Text(strings.reclassifyConfirmBody(counts[scope] ?: 0, label)) },
            confirmButton = {
                TextButton(onClick = { pending = null; onReclassify(scope) }) { Text(strings.reclassifyConfirm) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(strings.cancel) } },
        )
    }
}

/** One re-classification scope: what it does, how many transactions it touches, and its Run button. */
@Composable
private fun ScopeRow(title: String, description: String, affects: Int, onRun: () -> Unit) {
    val strings = LocalStrings.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, hairline()),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(5.dp))
                Text(strings.scopeAffects(affects), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(16.dp))
            PrimaryButton(strings.reclassifyConfirm, onClick = onRun, enabled = affects > 0)
        }
    }
}

/** A tinted note for a state the user probably didn't intend (e.g. both suggesters switched off). */
@Composable
private fun WarningNote(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(brandAccent()))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
