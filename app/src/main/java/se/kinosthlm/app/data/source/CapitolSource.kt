package se.kinosthlm.app.data.source

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http

/**
 * Bio Capitol, Sankt Eriksplan.
 *
 * Its ticketing backend (MyCloudCinema) is behind a login, but the programme page is fully
 * server-rendered: one `<a href="/boka/{id}">` per showing, carrying the film title, the time in
 * a screen-reader span, and the auditorium. One request per day in range.
 */
class CapitolSource(private val programmeUrl: String = PROGRAMME) : CinemaSource {

  override val id = SOURCE_ID
  override val label = "Bio Capitol"

  override suspend fun fetchScreenings(
    cinemas: List<Cinema>,
    watchlist: List<WatchlistItem>,
    from: Instant,
    to: Instant,
  ): List<RawScreening> = withContext(Dispatchers.IO) {
    val cinema = cinemas.firstOrNull() ?: return@withContext emptyList()

    val firstDay = LocalDateTime.ofInstant(from, SwedishDates.STOCKHOLM).toLocalDate()
    val lastDay = LocalDateTime.ofInstant(to, SwedishDates.STOCKHOLM).toLocalDate()
    val days = Duration.between(from, to).toDays().toInt().coerceIn(0, MAX_DAYS)

    val screenings = mutableListOf<RawScreening>()
    var day: LocalDate = firstDay
    var fetched = 0
    while (!day.isAfter(lastDay) && fetched <= days) {
      screenings += fetchDay(cinema, day, from, to)
      day = day.plusDays(1)
      fetched++
    }
    screenings
  }

  private fun fetchDay(
    cinema: Cinema,
    day: LocalDate,
    from: Instant,
    to: Instant,
  ): List<RawScreening> =
    parseDay(
        html = Http.getString("$programmeUrl?datum=${DATE.format(day)}", accept = "text/html"),
        baseUrl = programmeUrl,
        cinema = cinema,
        day = day,
      )
      .filter { !it.startTime.isBefore(from) && !it.startTime.isAfter(to) }

  /** One day's programme page. Split out from the fetch so the selectors are testable. */
  internal fun parseDay(
    html: String,
    baseUrl: String,
    cinema: Cinema,
    day: LocalDate,
  ): List<RawScreening> {
    val document = Jsoup.parse(html, baseUrl)

    return document.select("a[href^=/boka/]").mapNotNull { link ->
      // The visible label is the film title; the time rides along in a screen-reader span as
      // " (18:00)". Rows whose label is just the ticket CTA are duplicates of a row above.
      val srOnly = link.selectFirst("span.sr-only") ?: return@mapNotNull null
      val time = SwedishDates.parseTime(srOnly.text()) ?: return@mapNotNull null

      val title = link.ownText().trim().ifBlank { return@mapNotNull null }

      val start = LocalDateTime.of(day, time).atZone(SwedishDates.STOCKHOLM).toInstant()

      // "Salong" label followed by the number, both inside aria-hidden spans.
      val auditorium = link.select("span[aria-hidden=true] span")
        .firstOrNull { it.text().trim().toIntOrNull() != null }
        ?.let { "Salong ${it.text().trim()}" }

      RawScreening(
        cinemaId = cinema.id,
        cinemaName = cinema.name,
        title = title,
        startTime = start,
        auditorium = auditorium,
        bookingUrl = link.absUrl("href").ifBlank { baseUrl },
      )
    }
  }

  companion object {
    const val SOURCE_ID = "bio_capitol"
    private const val PROGRAMME = "https://www.capitolbio.se/filmer"

    /** Capitol publishes roughly a fortnight; beyond that the pages are empty. */
    private const val MAX_DAYS = 14
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
  }
}
