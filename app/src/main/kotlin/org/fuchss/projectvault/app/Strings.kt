package org.fuchss.projectvault.app

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/** The languages the UI can be shown in. Two, for now — add a case here to grow the set. */
enum class AppLanguage(val code: String, val label: String) {
    EN("en", "EN"),
    DE("de", "DE");

    /** The next language when the switcher is toggled (there are only two). */
    fun next(): AppLanguage = if (this == EN) DE else EN

    companion object {
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: EN
    }
}

fun stringsFor(language: AppLanguage): Strings = Strings(language)

/**
 * The active [Strings] bundle for composition, defaulting to English so screens render even without an
 * explicit provider (e.g. in the screenshot test). The app root provides the user's chosen language.
 */
val LocalStrings = staticCompositionLocalOf { Strings(AppLanguage.EN) }

/**
 * A global handle on the active [Strings], mirrored from composition so **non-composable** code
 * (import summaries/warnings in [ImportService], date formatting in `Format.kt`) can localize too.
 * The app root keeps this in sync with the selected language.
 */
object I18n {
    @Volatile
    var current: Strings = Strings(AppLanguage.EN)
}

/**
 * The i18n engine. It holds no strings itself — the actual translations live in the `*.i18n.kt` files
 * as **extension** properties/functions grouped by screen/feature (e.g. `AccountDetail.i18n.kt`,
 * `ImportService.i18n.kt`), each declared with the co-located DSL:
 *
 * - Plain labels: `val Strings.x get() = translate { en("…"); de("…") }`.
 * - With a runtime part: `fun Strings.f(…) = translate { en("…"); de("…") }`.
 *
 * Modelled on trixnity-messenger's i18n style: a single bundle bound to the active [language], where
 * each string's translations are co-located rather than split into one block per language. The DSL is
 * open-ended — a [Bundle] lists whatever languages a string has, and an entry missing for the active
 * language falls back to English — so adding a third language later means one new [Bundle] method plus
 * the translations you have, not a signature change at every call site. Domain product names
 * (Girokonto, Depot, DKB Kontoauszug, Sonstiges, …) are kept as-is in both languages on purpose —
 * they're proper German banking terms.
 */
class Strings(private val language: AppLanguage) {

    /** Collects a string's per-language variants; add a method here to support another language. */
    class Bundle {
        val variants = LinkedHashMap<AppLanguage, String>()
        fun en(value: String) { variants[AppLanguage.EN] = value }
        fun de(value: String) { variants[AppLanguage.DE] = value }
    }

    /**
     * Resolves a bundle for the active language, falling back to English (then whatever exists).
     * `internal` so the `*.i18n.kt` extension declarations across the module can build on it.
     */
    internal fun translate(build: Bundle.() -> Unit): String {
        val variants = Bundle().apply(build).variants
        return variants[language] ?: variants[AppLanguage.EN] ?: variants.values.first()
    }

    val locale: Locale get() = when (language) {
        AppLanguage.EN -> Locale.ENGLISH
        AppLanguage.DE -> Locale.GERMANY
    }
}
