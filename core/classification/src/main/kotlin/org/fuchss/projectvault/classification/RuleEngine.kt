package org.fuchss.projectvault.classification

/**
 * Tier 1 of the classifier: deterministic keyword matching. Rules are tried most-specific first
 * (higher priority — i.e. learned USER rules — then longer keyword), and the first whose keyword is
 * contained in the transaction text wins. Fast, offline, and fully explainable.
 */
class RuleEngine(private val rules: List<CategoryRule>) {

    private data class Match(val rule: CategoryRule, val index: Int)

    /**
     * One rule whose keyword occurs in the text, with everything the ordering is decided on: where the
     * keyword was found ([index] in the folded text) and whether the rule was an eligible candidate at
     * all ([accepted] — i.e. it passed the caller's category filter, which carries the amount-sign
     * constraint). See [explain].
     */
    data class Candidate(val rule: CategoryRule, val index: Int, val accepted: Boolean)

    /** Most-specific first: priority, then earliest position in the text, then longest keyword. */
    private val ordering = compareByDescending<Match> { it.rule.priority }
        .thenBy { it.index }
        .thenByDescending { it.rule.keyword.length }

    /** Every rule whose keyword occurs in [text], paired with the position it was found at. */
    private fun matches(text: String, acceptsCategory: (String) -> Boolean): List<Match> {
        val haystack = normalize(text)
        return rules.mapNotNull { rule ->
            if (!acceptsCategory(rule.categoryId)) return@mapNotNull null
            val index = indexOfWord(haystack, normalize(rule.keyword))
            if (index >= 0) Match(rule, index) else null
        }
    }

    /**
     * [bestRule] with its working shown: **every** rule whose keyword occurs in [text], ranked in the
     * exact order the engine resolves them, each flagged with whether [acceptsCategory] admitted it.
     *
     * The winner is therefore `explain(…).firstOrNull { it.accepted }` — which is what makes this
     * useful: the rules listed *above* the winner are the ones that matched and lost, and saying why
     * (rejected as a candidate vs. simply out-ranked) is most of the answer to "why did this row get
     * that category?". Nothing here feeds a decision, so [bestRule] is untouched by it.
     */
    fun explain(text: String, acceptsCategory: (String) -> Boolean = { true }): List<Candidate> =
        matches(text) { true }
            .sortedWith(ordering)
            .map { Candidate(it.rule, it.index, acceptsCategory(it.rule.categoryId)) }

    /**
     * Returns the best matching rule, or null. "Best" = highest priority (learned USER rules first),
     * then the keyword that appears EARLIEST in the text (merchant names lead the counterparty), then
     * the longest keyword (so "Amazon Prime" beats "Prime").
     *
     * [acceptsCategory] filters the *candidates* before the best one is picked, rather than vetoing
     * the winner afterwards — so a rejected front-runner falls through to the next admissible match
     * instead of leaving the transaction unclassified. That is what makes an incoming
     * "AMAZON … Erstattung" resolve to the refund rule (a transfer) rather than to the Amazon
     * shopping rule that happens to appear earlier in the text.
     */
    @JvmOverloads
    fun bestRule(text: String, acceptsCategory: (String) -> Boolean = { true }): CategoryRule? =
        matches(text, acceptsCategory).minWithOrNull(ordering)?.rule

    @JvmOverloads
    fun categorize(text: String, acceptsCategory: (String) -> Boolean = { true }): String? =
        bestRule(text, acceptsCategory)?.categoryId

    // Fold diacritics (ö→oe, é→e, …) and upper-case, so umlaut spelling never affects a match.
    private fun normalize(text: String): String = TextNormalizer.fold(text)

    /**
     * First occurrence of [needle] in [haystack] that stands on its own — i.e. is not glued to a
     * letter or digit on either side — or -1. Plain substring matching is what makes a short generic
     * keyword grab an unrelated longer word: `NETTO` inside *Nettobezüge* filed salary under
     * groceries, `MIETE` inside *Mieteinnahme* filed rent received under housing, and `UBER` inside
     * *Ueberweisung* filed any transfer under mobility. Punctuation still counts as a boundary, so
     * `REWE.Markt/…`, `AMZN.Mktp.DE` and `Booking.com Amsterdam` keep matching.
     *
     * Occurrences are scanned rather than just the first, so a keyword that appears glued once and
     * standalone later ("Nettobezuege … NETTO Markt") is still found at the standalone position.
     */
    private fun indexOfWord(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return -1
        var from = 0
        while (true) {
            val i = haystack.indexOf(needle, from)
            if (i < 0) return -1
            val before = i == 0 || !haystack[i - 1].isLetterOrDigit()
            if (before && endsWord(haystack, i + needle.length)) return i
            from = i + 1
        }
    }

    /**
     * Whether a keyword ending at [end] is at the end of a word — allowing a **short** continuation.
     *
     * Requiring a hard boundary on both sides was too strict against real statement text: it lost
     * `RESTAURANT` on the Romance inflections *Restaurante/Restaurantes*, `MCDONALD` on the plural
     * and on a glued store number (*McDonalds450*), and `HOTEL` on *Hotels* — 1% of a real vault.
     * What separates those from the false positives this boundary exists to stop is the **length** of
     * what follows: a plural or inflection adds one or two letters (optionally then a store number),
     * whereas *Nettobezüge*, *Nettolohn* and *Mieteinnahme* glue on a whole further word.
     *
     * So: at most [MAX_SUFFIX_LETTERS] trailing letters, optionally followed by digits, then a real
     * boundary. A continuation that starts with digits is a store/branch number and always allowed.
     */
    private fun endsWord(haystack: String, end: Int): Boolean {
        if (end == haystack.length || !haystack[end].isLetterOrDigit()) return true
        var i = end
        var letters = 0
        while (i < haystack.length && haystack[i].isLetter()) { letters++; i++ }
        if (letters > MAX_SUFFIX_LETTERS) return false
        while (i < haystack.length && haystack[i].isDigit()) i++
        return i == haystack.length || !haystack[i].isLetterOrDigit()
    }

    companion object {
        /** How many letters a keyword may be glued to and still count as a whole-word match. */
        private const val MAX_SUFFIX_LETTERS = 2

        /**
         * Derives a stable keyword from a transaction's counterparty for a learned USER rule — the
         * first alphanumeric token of length ≥ 3 (e.g. "REWE.Rene.Mueller/…" → "REWE"), else the
         * whole trimmed string. Diacritics are folded first so `DÖNER` yields `DOENER`, not `D`/`NER`.
         */
        fun keywordFor(counterparty: String): String {
            val folded = TextNormalizer.fold(counterparty)
            val tokens = folded.split(Regex("[^A-Za-z0-9]+"))
            // Prefer a token of at least 4 characters. A 3-character first token is usually a bank
            // or product prefix ("DKB …", "ING …") rather than the merchant, and such a keyword
            // matches almost everything: on a real vault three learned 3-character rules alone had
            // mis-committed 5% of all transactions. Three characters is still accepted when the
            // counterparty offers nothing longer, because some merchants really are that short (KFC,
            // OBI, DM).
            val token = tokens.firstOrNull { it.length >= 4 } ?: tokens.firstOrNull { it.length >= 3 }
            return token ?: folded.trim()
        }
    }
}
