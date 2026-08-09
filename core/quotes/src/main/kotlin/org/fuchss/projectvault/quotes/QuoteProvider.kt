package org.fuchss.projectvault.quotes

/**
 * Looks up the current price of a security by ISIN.
 *
 * The single seam between the app and the outside world: this is the only place Project Vault talks
 * to the network, and it is only ever reached for Depot accounts the user has explicitly opted in.
 *
 * Implementations **must not throw** — an unreachable network, a rate limit, a changed payload or an
 * unlisted instrument all return `null`, so live prices are an enhancement that degrades to
 * "unavailable" and never breaks the Depot view. Mirrors the `Embedder`/`NoopEmbedder` arrangement
 * in `:core:classification`.
 */
interface QuoteProvider {
    /** Whether this provider can attempt lookups at all. Says nothing about network reachability. */
    fun available(): Boolean

    /** The current price for [isin], or `null` if this provider has none. Never throws. */
    fun quote(isin: String): Quote?
}

/** The default: no network, no quotes. Used wherever live prices are not configured. */
object NoopQuoteProvider : QuoteProvider {
    override fun available(): Boolean = false

    override fun quote(isin: String): Quote? = null
}
