package org.fuchss.projectvault.classification

import java.text.Normalizer

/**
 * Shared text folding used across the classifier so keyword matching (Tier 1) and the statistical
 * classifier (Tier 2) treat text identically. German transliterations are expanded first
 * (ä→ae, ö→oe, ü→ue, ß→ss), then any remaining accents are stripped (é→e, à→a, …), then upper-cased.
 * This is why `DÖNER` and `DOENER` are the same token — SeedCatalog no longer needs both spellings.
 */
object TextNormalizer {

    /** Folds diacritics and upper-cases. Applied to both haystack and needle, so both sides align. */
    fun fold(text: String): String = stripAccents(expandGerman(text)).uppercase()

    private fun expandGerman(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (ch in text) {
            when (ch) {
                'ä' -> sb.append("ae"); 'ö' -> sb.append("oe"); 'ü' -> sb.append("ue")
                'Ä' -> sb.append("Ae"); 'Ö' -> sb.append("Oe"); 'Ü' -> sb.append("Ue")
                'ß' -> sb.append("ss")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** NFD-decompose and drop the combining marks (U+0300–U+036F), turning é→e, à→a, ç→c, etc. */
    private fun stripAccents(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}
