package org.fuchss.projectvault.app

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val EURO: NumberFormat = NumberFormat.getCurrencyInstance(Locale.GERMANY)
private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMANY)

/** Formats integer cents as a German-locale currency string, e.g. -2995 → "-29,95 €". */
fun formatCents(cents: Long): String = EURO.format(cents / 100.0)

/** Formats an epoch-day integer as dd.MM.yyyy. */
fun formatEpochDay(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(DATE)

/** Formats a [LocalDate] as dd.MM.yyyy. */
fun formatLocalDate(date: LocalDate): String = date.format(DATE)

/** Formats a nullable epoch-day, using an en dash for null. */
fun formatEpochDayOrDash(epochDay: Long?): String = epochDay?.let(::formatEpochDay) ?: "—"

/** Formats epoch milliseconds as a local date-time. */
fun formatEpochMillis(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DATETIME)

/** Formats a year-month, e.g. "August 2026". */
fun formatYearMonth(ym: YearMonth): String = ym.format(MONTH)
