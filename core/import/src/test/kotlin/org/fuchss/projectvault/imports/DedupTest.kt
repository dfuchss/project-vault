package org.fuchss.projectvault.imports

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DedupTest {

    private fun tx(date: String, cents: Long, cp: String?, purpose: String) =
        ParsedTransaction(LocalDate.parse(date), null, cents, cp, purpose, purpose)

    @Test
    fun `content hash is stable and distinguishes different rows`() {
        val a = tx("2026-08-07", -2784, "REWE", "Einkauf")
        assertEquals(Dedup.hash(a), Dedup.hash(tx("2026-08-07", -2784, "rewe", "  einkauf ")), "normalized")
        assertNotEquals(Dedup.hash(a), Dedup.hash(tx("2026-08-07", -3010, "REWE", "Einkauf")))
    }

    @Test
    fun `coarse key ignores counterparty and purpose text`() {
        // The same purchase as CSV (rich text) and as the later PDF (different text) shares date + amount.
        val csv = Dedup.coarseKey(LocalDate.parse("2026-08-07"), -2784)
        val pdf = Dedup.coarseKey(LocalDate.parse("2026-08-07"), -2784)
        assertEquals(csv, pdf)
        assertNotEquals(csv, Dedup.coarseKey(LocalDate.parse("2026-08-08"), -2784))
        assertNotEquals(csv, Dedup.coarseKey(LocalDate.parse("2026-08-07"), -2785))
    }

    @Test
    fun `cross-source duplicate is caught by the coarse key when text differs`() {
        // Stored from a CSV import; the candidate is the same transaction from the bank's PDF, whose
        // extracted counterparty/purpose text differs — so its content hash differs.
        val stored = listOf(Dedup.Keys(contentHash = "csv-hash", coarseKey = Dedup.coarseKey(LocalDate.parse("2026-08-07"), -2784)))
        val candidate = listOf(Dedup.Keys(contentHash = "pdf-hash", coarseKey = Dedup.coarseKey(LocalDate.parse("2026-08-07"), -2784)))
        assertEquals(listOf(true), Dedup.duplicateFlags(stored, candidate))
    }

    @Test
    fun `distinct same-day same-amount rows are matched one-to-one, not over-merged`() {
        // One €3,19 fee already imported; the PDF has TWO €3,19 fees on that day. Only one is a dup.
        val key = Dedup.coarseKey(LocalDate.parse("2026-07-22"), -319)
        val stored = listOf(Dedup.Keys("stored-1", key))
        val candidates = listOf(Dedup.Keys("pdf-a", key), Dedup.Keys("pdf-b", key))
        assertEquals(listOf(true, false), Dedup.duplicateFlags(stored, candidates))
    }

    @Test
    fun `an exact content-hash match is preferred and consumes its own row`() {
        val keyA = Dedup.coarseKey(LocalDate.parse("2026-07-01"), -1450)
        val keyB = Dedup.coarseKey(LocalDate.parse("2026-07-01"), -1450) // same coarse key
        val stored = listOf(Dedup.Keys("hash-x", keyA), Dedup.Keys("hash-y", keyB))
        // Candidate matching hash-y exactly must consume hash-y (not hash-x by coarse), leaving hash-x
        // free for a second candidate.
        val candidates = listOf(Dedup.Keys("hash-y", keyB), Dedup.Keys("hash-z", keyA))
        assertEquals(listOf(true, true), Dedup.duplicateFlags(stored, candidates))
    }

    @Test
    fun `genuinely new rows are not flagged`() {
        val stored = listOf(Dedup.Keys("h1", Dedup.coarseKey(LocalDate.parse("2026-07-01"), -1450)))
        val candidates = listOf(Dedup.Keys("h2", Dedup.coarseKey(LocalDate.parse("2026-07-02"), -1450)))
        assertTrue(Dedup.duplicateFlags(stored, candidates).none { it })
    }
}
