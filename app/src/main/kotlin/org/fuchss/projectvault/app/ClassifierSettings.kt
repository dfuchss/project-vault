package org.fuchss.projectvault.app

import org.fuchss.projectvault.data.VaultRepository

/**
 * How far the user wants Tier 2 to go: which of the two suggesters runs, and how confident each has
 * to be before it proposes anything. Stored **per vault** (see `VaultSetting.sq`) rather than in
 * [AppPrefs], because it belongs with the rules and labels it tunes — copy the vault to another
 * machine and it keeps behaving the way it was tuned.
 *
 * The defaults mirror the classifier constructors' own defaults (picked by `ClassifierComparisonTest`),
 * so an untouched vault behaves exactly as before this screen existed.
 */
data class ClassifierSettings(
    val statisticalEnabled: Boolean = true,
    /** Margin-based confidence a statistical proposal must clear — `StatisticalClassifier.minConfidence`. */
    val statisticalThreshold: Double = DEFAULT_STATISTICAL_THRESHOLD,
    val embeddingEnabled: Boolean = true,
    /**
     * Lead over the runner-up category an embedding proposal must have — `EmbeddingClassifier.minMargin`.
     *
     * Replaces the old absolute-cosine setting, which was decorative: multilingual-e5 scores every
     * German bank string 0.76–0.88 against every category, so no cosine threshold changed a decision
     * (the `ClassifierComparisonTest` sweep showed coverage 1.00 from σ=0.62 to σ=0.80).
     */
    val embeddingMargin: Double = DEFAULT_EMBEDDING_MARGIN,
) {
    companion object {
        const val DEFAULT_STATISTICAL_THRESHOLD = 0.65
        /** Mirrors `EmbeddingClassifier.DEFAULT_MIN_MARGIN` (kept in sync by ClassificationControlTest). */
        const val DEFAULT_EMBEDDING_MARGIN = 0.03

        // Setting keys. Values are plain text; anything unparseable falls back to the default, so a
        // hand-edited or future-version vault degrades to sane behaviour instead of failing to open.
        private const val KEY_STAT_ENABLED = "classification.statistical.enabled"
        private const val KEY_STAT_THRESHOLD = "classification.statistical.threshold"
        private const val KEY_EMB_ENABLED = "classification.embedding.enabled"

        /**
         * The embedding setting changed **meaning** (cosine → margin), so it changed **key**: a vault
         * tuned to the old `…embedding.threshold` (≈0.62) must not have that number reinterpreted as
         * a margin, which would silence the suggester completely. The old key is simply never read
         * again — an existing vault falls back to [DEFAULT_EMBEDDING_MARGIN], i.e. the retuned
         * default, and the stale row is left in place rather than migrated (it is inert, and keeps a
         * downgrade to an older build working).
         */
        private const val KEY_EMB_MARGIN = "classification.embedding.margin"

        /** The range the statistical slider offers — outside it the model always/never fires. */
        val ThresholdRange = 0.30f..0.95f

        /**
         * The range the embedding slider offers. Picked from the margin sweep: 0 proposes on every
         * transaction, and by 0.15 nothing on the harness clears the bar — so the useful span is the
         * whole slider rather than a sliver of it.
         */
        val MarginRange = 0.0f..0.15f

        fun load(repo: VaultRepository): ClassifierSettings {
            val s = repo.settings()
            val defaults = ClassifierSettings()
            return ClassifierSettings(
                statisticalEnabled = s[KEY_STAT_ENABLED]?.toBooleanStrictOrNull() ?: defaults.statisticalEnabled,
                statisticalThreshold = s[KEY_STAT_THRESHOLD]?.toDoubleOrNull() ?: defaults.statisticalThreshold,
                embeddingEnabled = s[KEY_EMB_ENABLED]?.toBooleanStrictOrNull() ?: defaults.embeddingEnabled,
                embeddingMargin = s[KEY_EMB_MARGIN]?.toDoubleOrNull() ?: defaults.embeddingMargin,
            )
        }

        fun save(repo: VaultRepository, settings: ClassifierSettings) {
            repo.setSetting(KEY_STAT_ENABLED, settings.statisticalEnabled.toString())
            repo.setSetting(KEY_STAT_THRESHOLD, settings.statisticalThreshold.toString())
            repo.setSetting(KEY_EMB_ENABLED, settings.embeddingEnabled.toString())
            repo.setSetting(KEY_EMB_MARGIN, settings.embeddingMargin.toString())
        }
    }
}
