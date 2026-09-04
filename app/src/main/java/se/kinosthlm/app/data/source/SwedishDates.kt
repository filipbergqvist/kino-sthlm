package se.kinosthlm.app.data.source

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** Date parsing helpers for cinema sites that print human Swedish rather than ISO timestamps. */
object SwedishDates {

  val STOCKHOLM: ZoneId = ZoneId.of("Europe/Stockholm")

  /** Both the three-letter abbreviations and the full names sites use interchangeably. */
  private val MONTHS: Map<String, Int> = buildMap {
    val names = listOf(
      "januari" to 1, "februari" to 2, "mars" to 3, "april" to 4,
      "maj" to 5, "juni" to 6, "juli" to 7, "augusti" to 8,
      "september" to 9, "oktober" to 10, "november" to 11, "december" to 12,
    )
    for ((name, number) in names) {
      put(name, number)
      put(name.take(3), number)
    }
    // Sites abbreviate these two irregularly.
    put("sept", 9)
    put("okt", 10)
  }

  fun monthOf(token: String): Int? = MONTHS[token.lowercase(Locale.ROOT).trim('.', ' ')]

  /**
   * Resolve a day/month with no year, as printed in cinema programmes.
   *
   * Picks whichever year puts the date nearest to [reference] without falling far behind it, so
   * a "12 jan" listed in December resolves to next January rather than eleven months ago.
   */
  fun resolveYear(day: Int, month: Int, reference: LocalDate = LocalDate.now(STOCKHOLM)): LocalDate {
    val candidates = listOf(reference.year - 1, reference.year, reference.year + 1)
      .mapNotNull { year -> runCatching { LocalDate.of(year, month, day) }.getOrNull() }
    // Prefer the soonest date that has not already passed by more than a few days.
    return candidates.firstOrNull { !it.isBefore(reference.minusDays(2)) } ?: candidates.last()
  }

  /** "lördag 12 Sep" + "14:00" -> a Stockholm-local datetime. */
  fun parse(day: Int, monthToken: String, time: LocalTime): LocalDateTime? {
    val month = monthOf(monthToken) ?: return null
    return LocalDateTime.of(resolveYear(day, month), time)
  }

  /** Accepts "18:00" and the "kl.18.00" form some sites use. */
  fun parseTime(raw: String): LocalTime? {
    val match = Regex("(\\d{1,2})[:.](\\d{2})").find(raw) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return LocalTime.of(hour, minute)
  }
}
