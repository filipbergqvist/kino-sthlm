package se.kinosthlm.app.data.source

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Booking links on bio.se, which several Stockholm cinemas share as their ticketing.
 *
 * The URL carries the whole showing:
 * `https://bio.se/biografer/filmhuset/20260906/1630/Victor` — venue, date, time, auditorium.
 * That is worth more than the surrounding markup: it is unambiguous, carries no locale, and
 * survives a redesign of the page it sits on, which the date headings above it do not.
 */
object BioSeLinks {

  /** venue / yyyyMMdd / HHmm / auditorium. */
  private val PATTERN =
    Regex("""bio\.se/biografer/([^/]+)/(\d{8})/(\d{3,4})/([^/?#]+)""", RegexOption.IGNORE_CASE)

  data class Showing(
    val venue: String,
    val auditorium: String,
    val startTime: Instant,
  )

  /** Null when [url] is not a bio.se booking link, or carries a date we cannot read. */
  fun parse(url: String): Showing? {
    val match = PATTERN.find(url) ?: return null
    val (venue, date, time, hall) = match.destructured

    val day =
      runCatching {
          LocalDate.of(
            date.substring(0, 4).toInt(),
            date.substring(4, 6).toInt(),
            date.substring(6, 8).toInt(),
          )
        }
        .getOrNull() ?: return null

    // "1630", and occasionally "930" without the leading zero.
    val padded = time.padStart(4, '0')
    val at =
      runCatching { LocalTime.of(padded.substring(0, 2).toInt(), padded.substring(2, 4).toInt()) }
        .getOrNull() ?: return null

    return Showing(
      venue = venue.lowercase(),
      auditorium = hall,
      startTime = day.atTime(at).atZone(SwedishDates.STOCKHOLM).toInstant(),
    )
  }
}
