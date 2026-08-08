package org.fuchss.projectvault.classification

/**
 * Tier 1 of the classifier: deterministic keyword matching. Rules are tried most-specific first
 * (higher priority — i.e. learned USER rules — then longer keyword), and the first whose keyword is
 * contained in the transaction text wins. Fast, offline, and fully explainable.
 */
class RuleEngine(private val rules: List<CategoryRule>) {

    private data class Match(val rule: CategoryRule, val index: Int)

    /**
     * Returns the best matching rule, or null. "Best" = highest priority (learned USER rules first),
     * then the keyword that appears EARLIEST in the text (merchant names lead the counterparty), then
     * the longest keyword (so "Amazon Prime" beats "Prime").
     */
    fun bestRule(text: String): CategoryRule? {
        val haystack = normalize(text)
        val matches = rules.mapNotNull { rule ->
            val needle = normalize(rule.keyword)
            val index = if (needle.isEmpty()) -1 else haystack.indexOf(needle)
            if (index >= 0) Match(rule, index) else null
        }
        return matches.minWithOrNull(
            compareByDescending<Match> { it.rule.priority }
                .thenBy { it.index }
                .thenByDescending { it.rule.keyword.length },
        )?.rule
    }

    fun categorize(text: String): String? = bestRule(text)?.categoryId

    // Fold diacritics (ö→oe, é→e, …) and upper-case, so umlaut spelling never affects a match.
    private fun normalize(text: String): String = TextNormalizer.fold(text)

    companion object {
        /**
         * Derives a stable keyword from a transaction's counterparty for a learned USER rule — the
         * first alphanumeric token of length ≥ 3 (e.g. "REWE.Rene.Mueller/…" → "REWE"), else the
         * whole trimmed string. Diacritics are folded first so `DÖNER` yields `DOENER`, not `D`/`NER`.
         */
        fun keywordFor(counterparty: String): String {
            val folded = TextNormalizer.fold(counterparty)
            val token = folded.split(Regex("[^A-Za-z0-9]+")).firstOrNull { it.length >= 3 }
            return token ?: folded.trim()
        }
    }
}
