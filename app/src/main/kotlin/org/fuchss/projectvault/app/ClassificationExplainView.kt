package org.fuchss.projectvault.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.fuchss.projectvault.data.VaultRepository
import org.fuchss.projectvault.data.db.Category
import org.fuchss.projectvault.data.db.Txn
import org.fuchss.projectvault.model.AccountType
import org.fuchss.projectvault.model.CategoryKind
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToLong

/** Where the text being explained comes from: a real row of the vault, or something typed. */
private enum class ExplainMode { TRANSACTION, FREE_TEXT }

/** A transaction offered in the picker, with the account it belongs to (the picker spans accounts). */
/**
 * Explain's expensive state, held **above** the tab switch so it survives leaving the tab.
 *
 * `when (tab)` disposes the tab that is not showing, which would discard every `remember` inside it —
 * including the built models. Rebuilding them is not cheap enough to do on a whim: it is an embedding
 * forward pass per category prototype *and* per labeled transaction, measured at ~6.5 s on a vault
 * with 800 labels. Paying that once per vault state is fine; paying it again every time the user
 * glances at the Rules tab and comes back is not.
 *
 * It is still built lazily — only when the Explain tab is actually opened — so the cost is never
 * imposed on someone who came to the screen to edit a rule.
 */
internal class ExplainState {
    var models by mutableStateOf<ExplainModels?>(null)
    var rows by mutableStateOf<List<PickableTxn>?>(null)
}

/** One question for the panel: the text, its sign, and the account type that may pre-empt the rules. */
private data class ExplainInput(val text: String, val amountCents: Long, val accountType: AccountType?)

internal data class PickableTxn(val txn: Txn, val accountName: String, val accountType: AccountType) {
    val text: String get() = explainTextOf(txn)
}

/**
 * The **Explain** tab: the full decision path for one input, in the order the classifier runs it —
 * the amount-sign constraint, the Tier-1 rule ranking, both Tier-2 models with their actual numbers,
 * the merge, and the outcome. The Rules tab's tester answers "which rule wins"; this answers "why did
 * *this* row get that category", which is a different question and needs the models to answer.
 *
 * Everything expensive happens exactly twice, never per keystroke: the transaction list is read once
 * off the UI thread, and the Tier-2 models are **built** once off the UI thread (an embedding forward
 * pass per category prototype and per labeled transaction — see [Categorizer.buildExplainModels]) and
 * then reused. Explaining a new text only embeds that one text, and that too runs off the UI thread,
 * debounced while typing.
 */
@Composable
internal fun ExplainTab(
    repo: VaultRepository,
    categorizer: Categorizer,
    categoryById: Map<String, Category>,
    refreshKey: Int,
    state: ExplainState,
) {
    val strings = LocalStrings.current
    var mode by remember { mutableStateOf(ExplainMode.TRANSACTION) }
    var search by remember { mutableStateOf("") }
    var selected by remember(refreshKey) { mutableStateOf<PickableTxn?>(null) }
    var freeText by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("-25,00") }

    // Every account's transactions, read once off the UI thread — the picker deliberately spans
    // accounts, since "why did this row get that category" is asked of a row, not of an account.
    val rows = state.rows
    LaunchedEffect(state) {
        if (state.rows == null) {
            state.rows = withContext(Dispatchers.IO) {
                repo.accounts()
                    .flatMap { account -> repo.transactions(account.id).map { PickableTxn(it, account.name, account.type) } }
                    .sortedByDescending { it.txn.bookingDate }
            }
        }
    }

    // The models: built once per vault state. Null while the build is still running, which the right
    // pane shows as a loading state rather than an empty one.
    val models = state.models
    LaunchedEffect(state) {
        if (state.models == null) {
            state.models = withContext(Dispatchers.IO) { categorizer.buildExplainModels() }
        }
    }

    val amountCents = parseAmountCents(amountText)
    val input: ExplainInput? = when (mode) {
        ExplainMode.TRANSACTION -> selected?.let { ExplainInput(it.text, it.txn.amountCents, it.accountType) }
        ExplainMode.FREE_TEXT -> freeText.takeIf { it.isNotBlank() }?.let { text -> amountCents?.let { ExplainInput(text, it, null) } }
    }

    var explanation by remember { mutableStateOf<ClassificationExplanation?>(null) }
    var computing by remember { mutableStateOf(false) }
    LaunchedEffect(models, input) {
        val ready = models
        if (ready == null || input == null) {
            explanation = null
            return@LaunchedEffect
        }
        computing = true
        // Typing is a stream of inputs and every one of them would embed a string; a short pause
        // collapses them to the text the user actually settled on.
        delay(250)
        explanation = withContext(Dispatchers.IO) { ready.explain(input.text, input.amountCents, input.accountType) }
        computing = false
    }

    Row(Modifier.fillMaxSize()) {
        VaultCard(modifier = Modifier.width(400.dp).fillMaxHeight()) {
            Column(Modifier.padding(16.dp)) {
                Text(strings.explainInputHeader, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(strings.explainIntro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                FlowRowChips {
                    Chip(strings.explainModeTransaction, selected = mode == ExplainMode.TRANSACTION) { mode = ExplainMode.TRANSACTION }
                    Chip(strings.explainModeFreeText, selected = mode == ExplainMode.FREE_TEXT) { mode = ExplainMode.FREE_TEXT }
                }
                Spacer(Modifier.height(12.dp))
                when (mode) {
                    ExplainMode.TRANSACTION -> TransactionPicker(
                        rows = rows,
                        search = search,
                        onSearch = { search = it },
                        selected = selected,
                        onSelect = { selected = it },
                        categoryById = categoryById,
                    )
                    ExplainMode.FREE_TEXT -> FreeTextInput(
                        text = freeText,
                        onText = { freeText = it },
                        amount = amountText,
                        onAmount = { amountText = it },
                        amountValid = amountCents != null,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            val ready = explanation
            when {
                models == null -> NoteCard(strings.explainBuildingModels)
                input == null -> NoteCard(if (mode == ExplainMode.TRANSACTION) strings.explainPickPrompt else strings.explainTypePrompt)
                ready == null -> NoteCard(strings.explainComputing)
                else -> {
                    ExplanationPanel(ready, categoryById, models)
                    if (computing) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            strings.explainComputing,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ---------------------------------------------------------------- Input side

@Composable
private fun TransactionPicker(
    rows: List<PickableTxn>?,
    search: String,
    onSearch: (String) -> Unit,
    selected: PickableTxn?,
    onSelect: (PickableTxn) -> Unit,
    categoryById: Map<String, Category>,
) {
    val strings = LocalStrings.current
    SearchField(search, onSearch, Modifier.fillMaxWidth(), placeholder = strings.explainSearchPlaceholder)
    Spacer(Modifier.height(10.dp))
    val all = rows.orEmpty()
    val filtered = remember(all, search) {
        val needle = search.trim().uppercase()
        if (needle.isEmpty()) all else all.filter { it.text.uppercase().contains(needle) }
    }
    when {
        rows == null -> EmptyHint(strings.explainComputing)
        all.isEmpty() -> EmptyHint(strings.explainNoTransactions)
        filtered.isEmpty() -> EmptyHint(strings.explainNoTxnMatch)
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(filtered, key = { it.txn.id }) { row ->
                TxnPickRow(row, selected?.txn?.id == row.txn.id, categoryById) { onSelect(row) }
            }
        }
    }
}

@Composable
private fun TxnPickRow(row: PickableTxn, selected: Boolean, categoryById: Map<String, Category>, onClick: () -> Unit) {
    val strings = LocalStrings.current
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container by animateColorAsState(
        when {
            selected -> scheme.primaryContainer
            hovered -> scheme.surfaceContainerHigh
            else -> Color.Transparent
        },
        label = "explain-txn-row",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = container,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.txn.counterparty ?: row.txn.purpose,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatCents(row.txn.amountCents),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = if (row.txn.amountCents < 0) MoneyNegative else MoneyPositive,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${formatEpochDay(row.txn.bookingDate)} · ${row.accountName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The row's standing today: a committed category, a pending suggestion, or neither —
                // the thing the user is about to ask "why?" about.
                val committed = row.txn.categoryId?.let { categoryById[it] }
                val suggested = row.txn.suggestedCategoryId?.let { categoryById[it] }
                when {
                    committed != null -> CategoryChip(committed)
                    suggested != null -> {
                        Text(strings.explainCurrentlySuggested, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CategoryChip(suggested)
                    }
                    else -> Text(strings.explainUncategorized, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FreeTextInput(
    text: String,
    onText: (String) -> Unit,
    amount: String,
    onAmount: (String) -> Unit,
    amountValid: Boolean,
) {
    val strings = LocalStrings.current
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        label = { Text(strings.explainTextLabel) },
        placeholder = { Text(strings.testRulePlaceholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = amount,
        onValueChange = onAmount,
        label = { Text(strings.explainAmountLabel) },
        supportingText = { Text(strings.explainAmountHelp) },
        isError = amount.isNotBlank() && !amountValid,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "-12,34" / "-12.34" / "42" → cents. Null when it isn't a number at all (the field then errors). */
private fun parseAmountCents(text: String): Long? {
    val cleaned = text.trim().replace(" ", "").replace(".", "").replace(',', '.')
    if (cleaned.isEmpty()) return null
    return cleaned.toDoubleOrNull()?.let { (it * 100).roundToLong() }
}

// ---------------------------------------------------------------- The decision path

/**
 * The five steps, rendered as one column. Deliberately reachable on its own (rather than only from
 * inside [ExplainTab], whose data arrives through suspending effects): a headless render test can
 * then paint a real explanation and catch a layout crash before a human opens the app.
 */
@Composable
internal fun ExplanationPanel(
    explanation: ClassificationExplanation,
    categoryById: Map<String, Category>,
    models: ExplainModels?,
) {
    val strings = LocalStrings.current
    SignStep(explanation, categoryById)
    Spacer(Modifier.height(12.dp))
    // A Tagesgeld row never reaches the rule engine at all. Everything below is still shown — what
    // the rules *would* have said is worth seeing — but it must be framed as hypothetical, or the
    // panel would name a rule and a category that were never applied.
    explanation.accountTypeDefault?.let { default ->
        NoteCard(strings.explainAccountDefault(accountTypeLabel(default.accountType), categoryById[default.categoryId]?.name ?: default.categoryId))
        Spacer(Modifier.height(12.dp))
    }
    RulesStep(explanation, categoryById)
    Spacer(Modifier.height(12.dp))
    // Tier 2 is not even consulted once a rule has committed. The numbers are still shown (they are
    // the interesting comparison), so the banner has to say plainly that they changed nothing.
    if (explanation.accountTypeDefault == null && explanation.winningRule != null) {
        NoteCard(strings.explainNotConsulted)
        Spacer(Modifier.height(12.dp))
    }
    StatisticalStep(explanation.statistical, categoryById)
    Spacer(Modifier.height(12.dp))
    EmbeddingStep(explanation.embedding, categoryById)
    Spacer(Modifier.height(12.dp))
    MergeStepCard(explanation, categoryById)
    if (models != null) {
        Spacer(Modifier.height(10.dp))
        Text(
            strings.explainModelBasis(models.prototypeCount, models.exampleCount) + " " + strings.explainNowNote,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** Step 1 — the amount sign: which kinds of category are admissible at all, and which are ruled out. */
@Composable
private fun SignStep(explanation: ClassificationExplanation, categoryById: Map<String, Category>) {
    val strings = LocalStrings.current
    val allowed = explanation.allowedKinds
    val (admissible, ruledOut) = categoryById.values
        .filter { it.enabled == 1L }
        .sortedBy { it.name }
        .partition { it.kind in allowed }

    StepCard(strings.explainSignHeader) {
        Text(
            if (explanation.amountCents == 0L) strings.explainSignZero else strings.explainSignIntro(formatCents(explanation.amountCents)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        FlowRowChips {
            CategoryKind.entries.forEach { kind ->
                val on = kind in allowed
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (on) MoneyPositive.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, hairline()),
                ) {
                    Row(Modifier.padding(horizontal = 11.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (on) MoneyPositive else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            strings.kindLabel(kind),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.explainAdmissible(admissible.size), style = MaterialTheme.typography.labelMedium, color = MoneyPositive)
            Text(strings.explainRuledOut(ruledOut.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (ruledOut.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            // Named, not just counted: "a rule that should have matched but didn't" is almost always
            // one of these, and seeing the category by name is what makes that click.
            Text(
                ruledOut.joinToString(" · ") { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(strings.explainRuledOutHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val RuleKeywordWidth = 160.dp
private val ExplainCategoryWidth = 170.dp
private val NarrowNumberWidth = 64.dp
private val NumberWidth = 78.dp
private val VerdictWidth = 104.dp

/** Step 2 — Tier 1: the ranked rule candidates, including the ones that matched and lost. */
@Composable
private fun RulesStep(explanation: ClassificationExplanation, categoryById: Map<String, Category>) {
    val strings = LocalStrings.current
    StepCard(strings.explainRulesHeader) {
        Text(strings.explainRulesIntro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        if (explanation.rules.isEmpty()) {
            Text(strings.explainNoRuleMatches, style = MaterialTheme.typography.bodySmall)
            return@StepCard
        }
        TableHeader {
            HeaderCell(strings.columnKeyword, Modifier.width(RuleKeywordWidth))
            HeaderCell(strings.columnCategory, Modifier.width(ExplainCategoryWidth))
            HeaderCell(strings.columnPriority, Modifier.width(NarrowNumberWidth), TextAlign.End)
            HeaderCell(strings.columnPosition, Modifier.width(NarrowNumberWidth), TextAlign.End)
            Spacer(Modifier.width(12.dp))
            HeaderCell(strings.columnVerdict, Modifier.width(VerdictWidth))
        }
        explanation.rules.forEach { candidate ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(RuleKeywordWidth)) {
                    Text(candidate.rule.keyword, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        strings.ruleSourceLabel(candidate.rule.source.name),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(Modifier.width(ExplainCategoryWidth)) {
                    categoryById[candidate.rule.categoryId]?.let { CategoryChip(it) }
                        ?: Text(strings.unknownCategory, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                NumberCell(candidate.rule.priority.toString(), Modifier.width(NarrowNumberWidth))
                NumberCell(candidate.index.toString(), Modifier.width(NarrowNumberWidth))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.width(VerdictWidth)) { VerdictBadge(candidate.verdict) }
            }
        }
    }
}

@Composable
private fun VerdictBadge(verdict: RuleVerdict) {
    val strings = LocalStrings.current
    val (label, color) = when (verdict) {
        RuleVerdict.WINNER -> strings.verdictWinner to MoneyPositive
        RuleVerdict.OUTRANKED -> strings.verdictOutranked to MaterialTheme.colorScheme.onSurfaceVariant
        RuleVerdict.SIGN_REJECTED -> strings.verdictSignRejected to MoneyNegative
        RuleVerdict.CATEGORY_DISABLED -> strings.verdictDisabled to brandAccent()
    }
    Surface(shape = RoundedCornerShape(7.dp), color = color.copy(alpha = 0.15f)) {
        Text(
            label,
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (verdict == RuleVerdict.WINNER) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
        )
    }
}

/** Step 3 — the statistical model: token evidence sorted by lift, then where every class landed. */
@Composable
private fun StatisticalStep(tier: StatisticalTier, categoryById: Map<String, Category>) {
    val strings = LocalStrings.current
    StepCard(strings.explainStatisticalHeader) {
        val explanation = tier.explanation
        if (tier.state != TierState.RAN || explanation == null) {
            TierNotRun(tier.state)
            return@StepCard
        }
        val decision = explanation.decision
        val top = explanation.scores.filter { it.admissible }
        Text(
            when {
                decision != null -> strings.explainStatDecision(
                    categoryById[decision.categoryId]?.name ?: decision.categoryId,
                    num(decision.confidence, 3),
                    num(tier.threshold, 2),
                )
                top.isEmpty() -> strings.explainStatNoVocabulary
                // The confidence of the decision that was *not* taken: recomputed the same way the
                // classifier does, from the top two admissible log-scores.
                else -> strings.explainStatNoDecision(num(pairwiseConfidence(top.map { it.logScore }), 3), num(tier.threshold, 2))
            },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )

        if (explanation.tokens.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(strings.explainTokenEvidence, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(strings.explainTokenEvidenceHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            TableHeader {
                HeaderCell(strings.columnToken, Modifier.weight(1f))
                HeaderCell(strings.columnWeight, Modifier.width(NumberWidth), TextAlign.End)
                HeaderCell(strings.columnToward, Modifier.width(NumberWidth), TextAlign.End)
                HeaderCell(strings.columnLift, Modifier.width(NumberWidth), TextAlign.End)
                Spacer(Modifier.width(BarWidth + 10.dp))
            }
            val maxLift = explanation.tokens.maxOf { abs(it.lift) }.takeIf { it > 0.0 } ?: 1.0
            explanation.tokens.take(MaxEvidenceRows).forEach { token ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(token.token, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    NumberCell(num(token.weight, 3), Modifier.width(NumberWidth))
                    NumberCell(signed(token.towardWinner, 3), Modifier.width(NumberWidth))
                    NumberCell(
                        signed(token.lift, 3),
                        Modifier.width(NumberWidth),
                        color = if (token.lift > 0) MoneyPositive else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    MiniBar((abs(token.lift) / maxLift).toFloat(), if (token.lift >= 0) MoneyPositive else MoneyNegative)
                }
            }
            OverflowNote(explanation.tokens.size, MaxEvidenceRows)
        }

        if (top.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(strings.explainClassScores, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            TableHeader {
                HeaderCell(strings.columnCategory, Modifier.weight(1f))
                HeaderCell(strings.columnLogScore, Modifier.width(NumberWidth), TextAlign.End)
                HeaderCell(strings.columnShare, Modifier.width(NumberWidth), TextAlign.End)
                Spacer(Modifier.width(BarWidth + 10.dp))
            }
            top.forEach { score ->
                val category = categoryById[score.categoryId]
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Dot(category?.let { parseHexColor(it.color) } ?: MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            category?.name ?: score.categoryId,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (score.categoryId == decision?.categoryId) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    NumberCell(num(score.logScore, 3), Modifier.width(NumberWidth))
                    NumberCell(percent(score.share), Modifier.width(NumberWidth))
                    Spacer(Modifier.width(10.dp))
                    MiniBar(score.share.toFloat(), category?.let { parseHexColor(it.color) } ?: MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(strings.explainShareHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (explanation.unknownTokens.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                strings.explainUnknownTokens(explanation.unknownTokens.size) + ": " +
                    explanation.unknownTokens.take(MaxUnknownTokens).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (explanation.tokens.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(strings.explainAllUnknown, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Step 4 — the embedding model: per-category cosine, and the lead the decision is actually made on. */
@Composable
private fun EmbeddingStep(tier: EmbeddingTier, categoryById: Map<String, Category>) {
    val strings = LocalStrings.current
    StepCard(strings.explainEmbeddingHeader) {
        val explanation = tier.explanation
        if (tier.state != TierState.RAN || explanation == null) {
            TierNotRun(tier.state)
            return@StepCard
        }
        val margin = explanation.margin
        if (margin == null || explanation.winner == null) {
            Text(strings.explainEmbNoCandidates, style = MaterialTheme.typography.bodySmall)
            return@StepCard
        }
        val runnerUpSimilarity = explanation.scores.firstOrNull { it.admissible && it.categoryId == explanation.runnerUp }?.similarity
        Text(
            strings.explainMarginLine(
                num((explanation.similarity ?: 0f).toDouble(), 4),
                num((runnerUpSimilarity ?: 0f).toDouble(), 4),
                num(margin.toDouble(), 4),
            ),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        val cleared = explanation.decision != null
        Text(
            if (cleared) strings.explainMarginCleared(num(tier.minMargin, 3)) else strings.explainMarginMissed(num(tier.minMargin, 3)),
            style = MaterialTheme.typography.labelMedium,
            color = if (cleared) MoneyPositive else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        TableHeader {
            HeaderCell(strings.columnCategory, Modifier.weight(1f))
            HeaderCell(strings.columnCosine, Modifier.width(NumberWidth), TextAlign.End)
            Spacer(Modifier.width(BarWidth + 10.dp))
        }
        // The cosines are all in a narrow band, so the bar is scaled to the band rather than to [0,1]
        // — a bar from zero would render every category the same length and show nothing.
        val shown = explanation.scores.take(MaxCategoryRows)
        val lo = shown.minOf { it.similarity }
        val hi = shown.maxOf { it.similarity }
        val span = (hi - lo).takeIf { it > 0f } ?: 1f
        shown.forEach { score ->
            val category = categoryById[score.categoryId]
            val muted = !score.admissible
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Dot(
                        when {
                            muted -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            else -> category?.let { parseHexColor(it.color) } ?: MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        category?.name ?: score.categoryId,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (score.categoryId == explanation.winner) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (muted) {
                        Spacer(Modifier.width(6.dp))
                        VerdictBadge(RuleVerdict.SIGN_REJECTED)
                    }
                }
                NumberCell(
                    num(score.similarity.toDouble(), 4),
                    Modifier.width(NumberWidth),
                    color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(10.dp))
                MiniBar(
                    (score.similarity - lo) / span,
                    if (muted) MaterialTheme.colorScheme.onSurfaceVariant else category?.let { parseHexColor(it.color) } ?: MaterialTheme.colorScheme.primary,
                )
            }
        }
        OverflowNote(explanation.scores.size, MaxCategoryRows)
        Spacer(Modifier.height(8.dp))
        Text(strings.explainCosineHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Step 5 — the merge of the two proposals, and what the classifier ends up doing. */
@Composable
private fun MergeStepCard(explanation: ClassificationExplanation, categoryById: Map<String, Category>) {
    val strings = LocalStrings.current
    val merge = explanation.merge
    StepCard(strings.explainMergeHeader) {
        Text(strings.explainMergeIntro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        TableHeader {
            HeaderCell(strings.tabModels, Modifier.width(RuleKeywordWidth))
            Spacer(Modifier.width(12.dp))
            HeaderCell(strings.columnCategory, Modifier.weight(1f))
            HeaderCell(strings.columnStrength, Modifier.width(NumberWidth), TextAlign.End)
        }
        MergeRow(strings.statisticalModelName, merge.statistical, merge.categoryId, categoryById)
        MergeRow(strings.embeddingModelName, merge.embedding, merge.categoryId, categoryById)
        Spacer(Modifier.height(10.dp))
        Text(
            when (merge.verdict) {
                MergeVerdict.NOTHING -> strings.explainMergeNothing
                MergeVerdict.AGREED -> strings.explainMergeAgreed
                MergeVerdict.ONLY_STATISTICAL -> strings.explainMergeOnlyStat
                MergeVerdict.ONLY_EMBEDDING -> strings.explainMergeOnlyEmb
                MergeVerdict.STATISTICAL_STRONGER -> strings.explainMergeStatStronger
                MergeVerdict.EMBEDDING_STRONGER -> strings.explainMergeEmbStronger
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(hairline()))
        Spacer(Modifier.height(14.dp))
        Text(strings.explainOutcomeHeader, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        val outcome = explanation.outcome
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(
                when (outcome.kind) {
                    OutcomeKind.ACCOUNT_DEFAULT, OutcomeKind.COMMITTED -> MoneyPositive
                    OutcomeKind.SUGGESTED -> brandAccent()
                    OutcomeKind.NOTHING -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            outcome.categoryId?.let { id -> categoryById[id]?.let { CategoryChip(it) } }
            Text(
                when (outcome.kind) {
                    OutcomeKind.ACCOUNT_DEFAULT -> strings.explainOutcomeAccountDefault
                    OutcomeKind.COMMITTED -> strings.explainOutcomeCommitted(strings.explainSourceLabel(outcome.source.orEmpty()))
                    OutcomeKind.SUGGESTED -> strings.explainOutcomeSuggested
                    OutcomeKind.NOTHING -> strings.explainOutcomeNothing
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun MergeRow(
    model: String,
    proposal: Pair<String, Float>?,
    merged: String?,
    categoryById: Map<String, Category>,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(model, Modifier.width(RuleKeywordWidth), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            val category = proposal?.first?.let { categoryById[it] }
            when {
                category != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CategoryChip(category)
                    // The merge's winner marked in place, so "who won" needs no second reading.
                    if (proposal.first == merged) Text("✓", style = MaterialTheme.typography.labelMedium, color = MoneyPositive)
                }
                else -> Text("—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        NumberCell(proposal?.let { num(it.second.toDouble(), 3) } ?: "—", Modifier.width(NumberWidth))
    }
}

// ---------------------------------------------------------------- Shared bits

private const val MaxEvidenceRows = 12
private const val MaxCategoryRows = 12
private const val MaxUnknownTokens = 12
private val BarWidth = 64.dp

/** One numbered step of the decision path. Same raised panel as everywhere else in the app. */
@Composable
private fun StepCard(title: String, content: @Composable () -> Unit) {
    VaultCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** A standalone note — the loading, "pick something" and "Tier 2 wasn't consulted" states. */
@Composable
private fun NoteCard(text: String) {
    VaultCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Why a Tier-2 box has no numbers in it — said plainly rather than left as an empty table. */
@Composable
private fun TierNotRun(state: TierState) {
    val strings = LocalStrings.current
    Text(
        when (state) {
            TierState.SWITCHED_OFF -> strings.explainTierSwitchedOff
            TierState.MODEL_MISSING -> strings.explainTierModelMissing
            // RAN never reaches here — the caller renders the numbers instead.
            TierState.UNTRAINED, TierState.RAN -> strings.explainTierUntrained
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TableHeader(content: @Composable RowScope.() -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = content)
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(hairline()))
        Spacer(Modifier.height(3.dp))
    }
}

@Composable
private fun HeaderCell(label: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(label, modifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align)
}

/**
 * A number in a table. Monospaced and right-aligned on purpose: these are log-scores, cosines and
 * TF-IDF weights read against each other, and proportional digits make a column of them unreadable.
 */
@Composable
private fun NumberCell(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text,
        modifier,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

/** The dashboard's bar, sized for a table cell — relative magnitude at a glance, no new idiom. */
@Composable
private fun MiniBar(fraction: Float, color: Color) {
    Box(Modifier.width(BarWidth)) { Bar(fraction, color) }
}

/** "+N more" under a capped table, so a truncated list never looks like the whole list. */
@Composable
private fun OverflowNote(total: Int, shown: Int) {
    if (total <= shown) return
    Spacer(Modifier.height(4.dp))
    Text(
        LocalStrings.current.explainMoreRows(total - shown),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Fixed decimals in the active locale — "0.652", never "0.6520000000000001". */
private fun num(value: Double, decimals: Int): String = String.format(I18n.current.locale, "%.${decimals}f", value)

/** Same, with an explicit plus so a column of signed contributions stays readable. */
private fun signed(value: Double, decimals: Int): String = (if (value > 0) "+" else "") + num(value, decimals)

private fun percent(share: Double): String = num(share * 100.0, 1) + " %"

/**
 * The confidence the statistical classifier would report for the leader — the pairwise softmax
 * between the top two admissible log-scores, i.e. the same margin it decides on. Used only to name
 * the number that fell short when there is no proposal.
 */
private fun pairwiseConfidence(logScores: List<Double>): Double {
    if (logScores.isEmpty()) return 0.0
    val sorted = logScores.sortedDescending()
    if (sorted.size == 1) return 1.0
    return 1.0 / (1.0 + exp(-(sorted[0] - sorted[1])))
}
