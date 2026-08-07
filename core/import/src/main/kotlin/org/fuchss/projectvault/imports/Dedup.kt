package org.fuchss.projectvault.imports

import java.security.MessageDigest
import java.time.LocalDate

/**
 * Stable content hash for a parsed transaction, used as the de-duplication key so re-importing an
 * overlapping statement never creates duplicates. PDF statements rarely carry a unique reference, so
 * the hash is derived from booking date, signed amount, and normalized counterparty/purpose.
 */
object Dedup {
    fun hash(tx: ParsedTransaction): String {
        val key = listOf(
            tx.bookingDate.toString(),
            tx.valueDate?.toString().orEmpty(), // original/value date sharpens de-dup + provenance
            tx.amountCents.toString(),
            normalize(tx.counterparty),
            normalize(tx.purpose),
        ).joinToString("|")
        return sha256(key)
    }

    /**
     * A **source-independent** key: the same transaction exported as CSV now and as the bank's PDF
     * later rarely shares identical counterparty/purpose text (different extraction paths), but it
     * always agrees on the booking date and the signed amount. This coarser key lets [duplicateFlags]
     * recognise a PDF row as already imported from a CSV (and vice-versa). It is intentionally
     * account-scoped by the caller (existing rows are queried per account).
     */
    fun coarseKey(bookingDate: LocalDate, amountCents: Long): String =
        "${bookingDate.toEpochDay()}|$amountCents"

    /** A stored or candidate row reduced to its two de-dup keys. */
    data class Keys(val contentHash: String, val coarseKey: String)

    /**
     * Classifies each candidate (in order) as a duplicate of an already-[stored] row. A candidate
     * matches by exact [Keys.contentHash] first (same-source re-import), else by the
     * cross-source [Keys.coarseKey]. Matching is **one-to-one**: each stored row is consumed by at
     * most one candidate, so two genuinely distinct rows that share a date and amount are not
     * over-merged — if one copy was already imported, only one of the new copies is treated as a
     * duplicate and the other is kept.
     */
    fun duplicateFlags(stored: List<Keys>, candidates: List<Keys>): List<Boolean> {
        val pool = stored.toMutableList()
        return candidates.map { c ->
            var i = pool.indexOfFirst { it.contentHash == c.contentHash }
            if (i < 0) i = pool.indexOfFirst { it.coarseKey == c.coarseKey }
            if (i >= 0) { pool.removeAt(i); true } else false
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun normalize(text: String?): String =
        (text ?: "").uppercase().replace(Regex("\\s+"), " ").trim()
}
