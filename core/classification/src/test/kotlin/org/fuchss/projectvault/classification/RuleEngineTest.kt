package org.fuchss.projectvault.classification

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
