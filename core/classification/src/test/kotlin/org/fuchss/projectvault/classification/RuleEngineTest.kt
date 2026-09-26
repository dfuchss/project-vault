package org.fuchss.projectvault.classification

import org.fuchss.projectvault.model.CategoryKind
import org.fuchss.projectvault.model.categoryAllowedForAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class RuleEngineTest {

    @Test
    fun `seed rules categorize common merchants case-insensitively`() {
        val engine = RuleEngine(SeedCatalog.rules)
        assertEquals("cat-groceries", engine.categorize("REWE.Markt/Musterstadt/../DE"))
        assertEquals("cat-shopping", engine.categorize("AMZN.Mktp.DE.JN0PS5SL5/AMZN.COM.BIL"))
        assertEquals("cat-subscriptions", engine.categorize("Spotify/Stockholm/../SE"))
        assertNull(engine.categorize("Some unknown counterparty"))
    }

    @Test
    fun `user rules outrank seed rules`() {
        val rules = SeedCatalog.rules + CategoryRule("AMZN", "cat-groceries", priority = 100, source = RuleSource.USER)
        assertEquals("cat-groceries", RuleEngine(rules).categorize("AMZN.Mktp.DE"))
    }

    @Test
    fun `longer keyword wins at equal priority`() {
        val rules = listOf(
            CategoryRule("PRIME", "cat-subscriptions", 0, RuleSource.SEED),
            CategoryRule("PRIME VIDEO", "cat-subscriptions", 0, RuleSource.SEED),
            CategoryRule("AMAZON PRIME", "cat-subscriptions", 0, RuleSource.SEED),
        )
        assertEquals("cat-subscriptions", RuleEngine(rules).categorize("AMAZON PRIME*membership"))
    }

    @Test
    fun `savings and card settlement are transfers, not spending or income`() {
        val e = RuleEngine(SeedCatalog.rules)
        assertEquals("cat-transfers", e.categorize("Dauerauftrag Sparen"))
        assertEquals("cat-transfers", e.categorize("Umbuchung Tagesgeld"))
        assertEquals("cat-transfers", e.categorize("Kreditkartenabrechnung Mastercard"))
    }

    @Test
    fun `enriched keywords classify more real-world merchants`() {
        val e = RuleEngine(SeedCatalog.rules)
        assertEquals("cat-fuel", e.categorize("TOTAL Tankstelle Berlin"))
        assertEquals("cat-leisure", e.categorize("FitnessStudio McFit Muenchen"))
        assertEquals("cat-travel", e.categorize("Booking.com Amsterdam"))
        assertEquals("cat-mobility", e.categorize("FLIXBUS DE Fernbus"))
        assertEquals("cat-restaurants", e.categorize("Pizzeria Napoli"))
        assertEquals("cat-drugstore", e.categorize("Shop Apotheke Versand"))
        assertEquals("cat-income", e.categorize("Kindergeld Familienkasse"))
        assertEquals("cat-utilities", e.categorize("Stadtwerke Stromrechnung"))
    }

    @Test
    fun `events are their own category and refunds are transfers, not income`() {
        val e = RuleEngine(SeedCatalog.rules)
        assertEquals("cat-events", e.categorize("Eventim Konzert Tickets"))
        assertEquals("cat-events", e.categorize("CinemaxX Kino"))
        // Incoming money that is a refund/own-transfer is a transfer, not Einkommen.
        assertEquals("cat-transfers", e.categorize("Erstattung Krankenkasse"))
        assertEquals("cat-transfers", e.categorize("Rueckzahlung Finanzamt"))
        assertEquals("cat-transfers", e.categorize("Eigenuebertrag auf Tagesgeld"))
        // Salary is its own category; other income stays "Weitere Einkünfte".
        assertEquals("cat-salary", e.categorize("Gehalt Muster GmbH"))
        assertEquals("cat-salary", e.categorize("Muster AG Lohn/Gehalt"))
        assertEquals("cat-income", e.categorize("Rente Deutsche Rentenversicherung"))
        assertEquals("cat-income", e.categorize("Kindergeld Familienkasse"))
    }

    @Test
    fun `over-broad substrings do not misclassify (collision guards)`() {
        val e = RuleEngine(SeedCatalog.rules)
        // "...gezahlt" must not hit a utilities keyword (old GEZ removed).
        assertNull(e.categorize("Betrag wurde gezahlt am 01.07."))
        // A person named Leon must not become utilities (bare EON removed; E.ON kept).
        assertNull(e.categorize("Leon Mustermann"))
        // e-scooter TIER removed, so an animal shelter is not "Mobilität".
        assertNull(e.categorize("Tierheim Musterstadt e.V."))
    }

    @Test
    fun `keywordFor extracts a stable token from a counterparty`() {
        assertEquals("REWE", RuleEngine.keywordFor("REWE.Markt/Musterstadt/../DE"))
        assertEquals("AMZN", RuleEngine.keywordFor("AMZN.Mktp.DE.JN0PS5SL5"))
        assertEquals("TELECOM", RuleEngine.keywordFor("1+1 Telecom GmbH"))
    }

    @Test
    fun `diacritics fold so umlaut spelling does not affect matching`() {
        val e = RuleEngine(SeedCatalog.rules)
        // Seed keyword is the ASCII "DOENER"; the umlaut spelling still matches after folding.
        assertEquals("cat-restaurants", e.categorize("Döner Imbiss Musterstadt"))
        // And the reverse: an ASCII-spelled BAFOEG text against the (now single) seed keyword.
        assertEquals("cat-income", e.categorize("BAFOEG Amt fuer Ausbildungsfoerderung"))
        assertEquals("cat-income", e.categorize("BAföG Amt für Ausbildungsförderung"))
        // A user rule with an umlaut keyword matches an ASCII-spelled transaction and vice versa.
        val rules = listOf(CategoryRule("MÜLLER", "cat-drugstore", 100, RuleSource.USER))
        assertEquals("cat-drugstore", RuleEngine(rules).categorize("MUELLER Drogeriemarkt"))
    }

    @Test
    fun `keywordFor folds diacritics into an ascii token`() {
        assertEquals("DOENER", RuleEngine.keywordFor("Döner Palast"))
        assertEquals("MUELLER", RuleEngine.keywordFor("Müller Handels GmbH"))
    }

    // ---------------------------------------------------------------- sign awareness

    private val seedKinds: Map<String, CategoryKind> = SeedCatalog.categories.associate { it.id to it.kind }

    /** Mirrors how Categorizer constrains candidates: a rule is only eligible if its category's kind
     *  is admissible for the transaction's signed amount. */
    private fun RuleEngine.categorize(text: String, amountCents: Long): String? =
        categorize(text) { categoryId ->
            seedKinds[categoryId]?.let { categoryAllowedForAmount(amountCents, it) } ?: true
        }

    @Test
    fun `an expense category is never chosen for incoming money`() {
        val e = RuleEngine(SeedCatalog.rules)
        // Each of these used to commit an EXPENSE category to a credit, because the merchant name
        // leads the counterparty and so won on earliest-match.
        val credits = listOf(
            "AMZN Mktp DE Rueckerstattung Bestellung",
            "Zalando SE Rueckzahlung Retoure",
            "Techniker Krankenkasse Erstattung Zahnarzt",
            "Telekom GmbH Gutschrift Storno Rechnung",
            "Eventim Ticket Rueckerstattung Konzert abgesagt",
        )
        credits.forEach { text ->
            val category = e.categorize(text, amountCents = 4990)
            val kind = category?.let { seedKinds[it] }
            assertNotEquals(CategoryKind.EXPENSE, kind, "expense category committed to a credit: '$text' -> $category")
        }
    }

    @Test
    fun `a rejected front-runner falls through to the next admissible rule`() {
        val e = RuleEngine(SeedCatalog.rules)
        // AMZN (shopping) leads the text but is inadmissible on a credit, so the refund keyword wins
        // instead of the transaction being left unclassified.
        assertEquals("cat-transfers", e.categorize("AMZN Mktp DE Rueckerstattung Bestellung", amountCents = 4990))
        // The same text as a debit is an ordinary Amazon purchase again.
        assertEquals("cat-shopping", e.categorize("AMZN Mktp DE Bestellung", amountCents = -4990))
    }

    @Test
    fun `income keywords do not apply to money going out`() {
        val e = RuleEngine(SeedCatalog.rules)
        assertEquals("cat-salary", e.categorize("Muster GmbH Gehaltszahlung", amountCents = 250000))
        // "Vergütung" on a debit is money you paid someone, not income.
        assertNull(e.categorize("Verguetung Dienstleister", amountCents = -25000))
    }

    // ---------------------------------------------------------------- word boundaries

    @Test
    fun `keywords match whole words, not fragments of longer ones`() {
        val e = RuleEngine(SeedCatalog.rules)
        // NETTO inside "Nettobezuege" used to file salary under Lebensmittel.
        assertEquals("cat-salary", e.categorize("Arbeitgeber GmbH Nettobezuege 05/2026", amountCents = 250000))
        assertEquals("cat-salary", e.categorize("Muster AG Nettolohn Juni", amountCents = 250000))
        // ...while the supermarket itself still matches.
        assertEquals("cat-groceries", e.categorize("NETTO Marken-Discount Musterstadt", amountCents = -2350))
        // MIETE inside "Mieteinnahme" used to file rent received under Wohnen & Miete.
        assertEquals("cat-income", e.categorize("Mieteinnahme Wohnung Musterstr 5", amountCents = 65000))
        assertEquals("cat-housing", e.categorize("Miete Wohnung Musterstr 5", amountCents = -65000))
    }

    @Test
    fun `punctuation still counts as a word boundary`() {
        val e = RuleEngine(SeedCatalog.rules)
        assertEquals("cat-groceries", e.categorize("REWE.Markt/Musterstadt/../DE"))
        assertEquals("cat-shopping", e.categorize("AMZN.Mktp.DE.JN0PS5SL5/AMZN.COM.BIL"))
        assertEquals("cat-travel", e.categorize("Booking.com Amsterdam"))
        assertEquals("cat-subscriptions", e.categorize("APPLE.COM/BILL ITUNES"))
    }

    @Test
    fun `a plural or store number does not break a keyword match`() {
        val e = RuleEngine(SeedCatalog.rules)
        // Real statement text glues short inflections and branch numbers onto merchant names; a
        // strict boundary on both sides lost all of these.
        assertEquals("cat-restaurants", e.categorize("Restaurante Da Vinci", amountCents = -2350))
        assertEquals("cat-restaurants", e.categorize("Restaurantes El Puerto", amountCents = -2350))
        assertEquals("cat-restaurants", e.categorize("MCDONALDS450 Musterstadt", amountCents = -890))
        assertEquals("cat-travel", e.categorize("Hotels.com Buchung", amountCents = -12000))
    }

    @Test
    fun `a glued second word still does not count as a match`() {
        val e = RuleEngine(SeedCatalog.rules)
        // The tolerance is short on purpose: one or two letters is an inflection, a whole further
        // word is a different word. These are the cases the boundary exists for.
        assertEquals("cat-salary", e.categorize("Arbeitgeber GmbH Nettobezuege 05/2026", amountCents = 250000))
        assertEquals("cat-income", e.categorize("Mieteinnahme Wohnung Musterstr 5", amountCents = 65000))
        // NETTOLOHN is a salary keyword in its own right, but NETTO must not be what matches it.
        assertNull(RuleEngine(listOf(CategoryRule("NETTO", "cat-groceries", 0, RuleSource.SEED))).categorize("Nettolohn Juni"))
    }

    @Test
    fun `keywordFor prefers a token long enough to identify a merchant`() {
        // A 3-character first token is usually a bank or product prefix, and as a learned rule it
        // matches almost everything — so a longer token in the same counterparty wins.
        assertEquals("VISA", RuleEngine.keywordFor("DKB Visa Debit"))
        assertEquals("TELECOM", RuleEngine.keywordFor("1+1 Telecom GmbH"))
        // ...but a genuinely short merchant name is still learnable when nothing longer is offered.
        assertEquals("KFC", RuleEngine.keywordFor("KFC"))
        assertEquals("REWE", RuleEngine.keywordFor("REWE.Markt/Musterstadt/../DE"))
    }
}
