package org.fuchss.projectvault.classification

import org.fuchss.projectvault.model.CategoryKind.EXPENSE
import org.fuchss.projectvault.model.CategoryKind.INCOME
import org.fuchss.projectvault.model.CategoryKind.TRANSFER

/**
 * The built-in category taxonomy and starter keyword rules installed into every new vault. This is
 * the cold-start fix: a fresh vault already classifies common German merchants before the user
 * corrects anything. Keywords are generic merchant/brand names — never personal data.
 */
object SeedCatalog {

    val categories: List<SeedCategory> = listOf(
        SeedCategory("cat-salary", "Gehalt", INCOME, "#2E7D53"),
        SeedCategory("cat-income", "Weitere Einkünfte", INCOME, "#79B473"),
        SeedCategory("cat-groceries", "Lebensmittel", EXPENSE, "#43A047"),
        SeedCategory("cat-restaurants", "Restaurant & Café", EXPENSE, "#F4511E"),
        SeedCategory("cat-shopping", "Shopping", EXPENSE, "#8E24AA"),
        SeedCategory("cat-drugstore", "Drogerie & Gesundheit", EXPENSE, "#00897B"),
        SeedCategory("cat-mobility", "Mobilität", EXPENSE, "#3949AB"),
        SeedCategory("cat-fuel", "Tanken", EXPENSE, "#6D4C41"),
        SeedCategory("cat-subscriptions", "Abos & Digitales", EXPENSE, "#5E35B1"),
        SeedCategory("cat-telecom", "Telefon & Internet", EXPENSE, "#1E88E5"),
        SeedCategory("cat-housing", "Wohnen & Miete", EXPENSE, "#00695C"),
        SeedCategory("cat-utilities", "Strom & Nebenkosten", EXPENSE, "#FB8C00"),
        SeedCategory("cat-insurance", "Versicherung", EXPENSE, "#546E7A"),
        SeedCategory("cat-leisure", "Freizeit & Sport", EXPENSE, "#00ACC1"),
        SeedCategory("cat-events", "Events & Kultur", EXPENSE, "#C2185B"),
        SeedCategory("cat-travel", "Reisen & Urlaub", EXPENSE, "#EC407A"),
        SeedCategory("cat-cash", "Bargeld", EXPENSE, "#757575"),
        SeedCategory("cat-transfers", "Umbuchung & Sparen", TRANSFER, "#00838F"),
        SeedCategory("cat-other", "Sonstiges", EXPENSE, "#9AA6AD"),
    )

    /** Seed rules (priority 0). Learned USER rules use a higher priority so they win. */
    val rules: List<CategoryRule> = buildRules(
        "cat-groceries" to listOf("REWE", "EDEKA", "ALDI", "LIDL", "PENNY", "NETTO", "KAUFLAND", "NAHKAUF", "TEGUT", "DENNS", "BIO COMPANY", "MARKTKAUF", "GLOBUS", "WASGAU", "FRISCHEMARKT", "WOCHENMARKT"),
        "cat-restaurants" to listOf("RESTAURANT", "CAFE", "MCDONALD", "BURGER KING", "LIEFERANDO", "IMBISS", "LANGOS", "BACKEREI", "BAECKEREI", "STARBUCKS", "DELIVEROO", "WOLT", "PIZZERIA", "SUSHI", "DOENER", "DÖNER", "KFC", "SUBWAY", "VAPIANO", "L'OSTERIA", "LOSTERIA", "NORDSEE", "BISTRO", "KANTINE", "GASTSTAETTE", "EISCAFE", "EISDIELE"),
        "cat-shopping" to listOf("AMAZON", "AMZN", "ZALANDO", "ABOUT YOU", "MEDIA-SATURN", "MEDIAMARKT", "SATURN", "CYBERPORT", "JACOB", "IKEA", "C.A.HAUS", "APPLE STORE", "OTTO VERSAND", "H&M", "H & M", "ZARA", "DECATHLON", "THALIA", "DOUGLAS", "TK MAXX", "GALERIA", "KAUFHOF", "EBAY", "ETSY", "SHEIN", "TEMU", "BAUHAUS", "HORNBACH", "TCHIBO", "SNIPES", "PRIMARK"),
        "cat-drugstore" to listOf("DM.DROGERIE", "DM-DROGERIE", "DM DROGERIE", "ROSSMANN", "BUDNI", "APOTHEKE", "ZAHNARZT", "SHOP APOTHEKE", "DOCMORRIS", "FIELMANN", "APOLLO OPTIK", "KLINIK", "PHYSIO", "PRAXIS DR", "HAUSARZT"),
        "cat-mobility" to listOf("DB VERTRIEB", "DEUTSCHE BAHN", "VERKEHRSBETRIEBE", "FLIXBUS", "UBER", "FREENOW", "DEUTSCHLANDTICKET", "PARKHAUS", "SIXT", "EUROPCAR", "MIETWAGEN", "LIME SCOOTER", "NEXTBIKE", "CALL A BIKE", "S-BAHN", "DEUTSCHLAND-TICKET", "TAXI", "APCOA", "PARKEN"),
        "cat-fuel" to listOf("ARAL", "SHELL", "ESSO", "TANKSTELLE", "OMV", "TOTAL", "AVIA", "STAR TANK", "AGIP", "SUPERCHARGER", "IONITY", "EWE GO"),
        "cat-subscriptions" to listOf("SPOTIFY", "NETFLIX", "DISNEY", "PRIME VIDEO", "AMAZON PRIME", "PRIME.VIDEO", "YOUTUBE", "GITHUB", "ANTHROPIC", "OPENAI", "ADOBE", "PATREON", "STEAM", "PLAYSTATION", "DAZN", "SKY", "WOW TV", "AUDIBLE", "ICLOUD", "APPLE.COM/BILL", "GOOGLE ONE", "GOOGLE STORAGE", "DROPBOX", "NOTION", "LINKEDIN", "XBOX", "NINTENDO", "TWITCH", "PARAMOUNT", "MICROSOFT 365", "CANVA", "MIDJOURNEY"),
        "cat-telecom" to listOf("TELEKOM", "VODAFONE", "1+1 TELECOM", "1&1 TELECOM", "1UND1", "O2", "TELEFONICA", "BLAU", "CONGSTAR", "PYUR", "SIMYO", "LEBARA", "LYCAMOBILE", "ALDI TALK", "WINSIM", "FRAENK"),
        "cat-housing" to listOf("MIETE", "KALTMIETE", "WARMMIETE", "STUDIERENDENWERK", "HAUSVERWALTUNG", "WOHNUNG", "WOHNGELD", "NEBENKOSTEN", "GENOSSENSCHAFT", "VONOVIA", "DEUTSCHE WOHNEN", "GRUNDSTEUER", "GRUNDBESITZ"),
        "cat-utilities" to listOf("YELLO STROM", "STADTWERKE", "ENBW", "E.ON", "VATTENFALL", "GASAG", "STROM ABSCHLAG", "STROMRECHNUNG", "ERDGAS", "WASSERWERK", "ABWASSER", "ENTSORGUNG", "RUNDFUNK", "RUNDFUNKBEITRAG", "ARD ZDF", "OCTOPUS ENERGY", "LICHTBLICK"),
        "cat-insurance" to listOf("DEBEKA", "ALLIANZ", "HUK", "VERSICHERUNG", "AXA", "ERGO", "GENERALI", "SIGNAL IDUNA", "HANSEMERKUR", "GOTHAER", "BARMENIA", "GETSAFE", "FRIDAY", "KRANKENKASSE", "BARMER", "TECHNIKER KRANKEN"),
        "cat-leisure" to listOf("FITNESSSTUDIO", "FITNESS", "MCFIT", "CLEVER FIT", "URBAN SPORTS", "SCHWIMMBAD", "HALLENBAD", "THERME", "SPORTVEREIN", "KLETTERHALLE", "BOULDER", "YOGA"),
        "cat-events" to listOf("KINO", "CINEMAXX", "CINESTAR", "THEATER", "MUSEUM", "EVENTIM", "TICKETMASTER", "KONZERT", "FESTIVAL", "STADION", "OPER", "PHILHARMONIE", "VERANSTALTUNG", "KABARETT", "COMEDY", "KARTENVORVERKAUF"),
        "cat-travel" to listOf("BOOKING.COM", "AIRBNB", "HOTEL", "LUFTHANSA", "RYANAIR", "EUROWINGS", "EASYJET", "EXPEDIA", "TRIVAGO", "FERIENWOHNUNG", "OPODO", "HOSTEL"),
        "cat-salary" to listOf("LOHN", "GEHALT", "LOHN/GEHALT", "LOHN GEHALT", "BEZUEGE", "BEZÜGE", "LOHNABRECHNUNG", "GEHALTSABRECHNUNG"),
        "cat-income" to listOf("RENTE", "KINDERGELD", "STEUERERSTATTUNG", "DIVIDENDE", "HONORAR", "ELTERNGELD", "BAFOEG", "BAFÖG", "ZINSGUTSCHRIFT"),
        "cat-cash" to listOf("BARGELD", "AUSZAHLUNG", "GELDAUTOMAT", "GA NR", "BARGELDAUSZAHLUNG"),
        // Internal money movements (not real spending/income): credit-card settlement, savings, own
        // transfers, and refunds/reversals — incoming money that isn't income lands here, not Einkommen.
        "cat-transfers" to listOf("UMBUCHUNG", "SPAREN", "SPARPLAN", "DAUERAUFTRAG SPAREN", "RUECKLAGE", "TAGESGELD", "FESTGELD", "DEPOTUEBERTRAG", "UEBERTRAG", "ÜBERTRAG", "EIGENUEBERTRAG", "EIGENE UEBERWEISUNG", "KREDITKARTENABRECHNUNG", "KREDITKARTEN", "VISA ABRECHNUNG", "MASTERCARD ABRECHNUNG", "ERSTATTUNG", "RUECKERSTATTUNG", "RÜCKERSTATTUNG", "RUECKZAHLUNG", "RÜCKZAHLUNG", "RETOURE", "STORNO"),
    )

    private fun buildRules(vararg groups: Pair<String, List<String>>): List<CategoryRule> =
        groups.flatMap { (categoryId, keywords) ->
            keywords.map { CategoryRule(keyword = it, categoryId = categoryId, priority = 0, source = RuleSource.SEED) }
        }
}
