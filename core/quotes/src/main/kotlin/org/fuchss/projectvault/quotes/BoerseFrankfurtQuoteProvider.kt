package org.fuchss.projectvault.quotes

import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Current prices from Börse Frankfurt / Xetra (Deutsche Börse), looked up **by ISIN**.
 *
 * German venues quote by ISIN, which is exactly the identifier bank Depotauszüge carry — so there is
 * no ISIN→ticker resolution step and no symbol mapping to maintain. The only thing that leaves the
 * device is an ISIN: no amounts, no quantities, no account identity.
 *
 * ```
 * GET https://api.boerse-frankfurt.de/v1/data/quote_box/single?isin=DE0007164600&mic=XETR
 * {"lastPrice":177.98,"nominal":false,"instrumentStatus":"Active","timestampLastPrice":"…Z", …}
 * ```
 *
 * This is the JSON backend of boerse-frankfurt.de rather than a contractual public API, so it may
 * change without notice. Every failure path — transport, status code, payload shape — collapses to
 * `null` per the [QuoteProvider] contract.
 */
class BoerseFrankfurtQuoteProvider(
    private val http: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
) : QuoteProvider {
    override fun available(): Boolean = true

    /** Tries the Xetra reference market first, then the Frankfurt floor. */
    override fun quote(isin: String): Quote? =
        VENUES.firstNotNullOfOrNull { venue -> fetch(isin, venue) }

    private fun fetch(isin: String, venue: String): Quote? = runCatching {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$BASE?isin=$isin&mic=$venue"))
            .timeout(Duration.ofSeconds(8))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        parseQuoteBox(isin, venue, response.body())
    }.getOrNull()

    private companion object {
        const val BASE = "https://api.boerse-frankfurt.de/v1/data/quote_box/single"
        const val USER_AGENT = "ProjectVault/0.1 (personal finance app; local-first)"

        /** XETR is the reference market; XFRA catches instruments not traded on Xetra. */
        val VENUES = listOf("XETR", "XFRA")
    }
}

/**
 * Reads a `quote_box/single` payload. Split out from the HTTP call so the payload contract can be
 * tested offline against captured responses.
 *
 * Returns `null` for anything unusable: malformed JSON, a missing or non-numeric `lastPrice`, or an
 * instrument the venue does not currently report as `Active`.
 */
internal fun parseQuoteBox(isin: String, venue: String, body: String): Quote? {
    val json = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    if (json.string("instrumentStatus") != "Active") return null
    // Read the raw token, never a Double: BigDecimal("128.54") is exact where 128.54 is not.
    val price = json.string("lastPrice")?.let { runCatching { BigDecimal(it) }.getOrNull() } ?: return null
    if (price.signum() <= 0) return null
    return Quote(
        isin = isin,
        price = price,
        // `nominal: true` = percent-of-par quotation (bonds); anything else is a per-share price.
        perShare = json.string("nominal")?.toBooleanStrictOrNull() != true,
        venue = venue,
        asOf = json.instantMillis("timestampLastPrice")
            ?: json.instantMillis("timestamp")
            ?: System.currentTimeMillis(),
    )
}

/** Raw token for [key], or `null` when absent, JSON `null`, or not a primitive. */
private fun JsonObject.string(key: String): String? {
    val primitive = runCatching { this[key]?.jsonPrimitive }.getOrNull() ?: return null
    return if (primitive is JsonNull) null else primitive.content
}

private fun JsonObject.instantMillis(key: String): Long? =
    string(key)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
