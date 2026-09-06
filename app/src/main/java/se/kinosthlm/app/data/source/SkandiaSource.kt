package se.kinosthlm.app.data.source

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import se.kinosthlm.app.data.match.MatchCandidate
import se.kinosthlm.app.data.match.TitleMatcher
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http

/**
 * Bio Skandia, Drottninggatan.
 *
 * The programme is split across one page per film, so we fetch the (short) index of what is
 * currently showing, match those titles against the watchlist first, and only then fetch detail
 * pages — normally none or one, rather than every film on the schedule.
 *
 * Each detail page lists showings as a weekday/day/month/time row beside a Tickster link.
 *
 * Parsing is split out from fetching so the selectors can be tested against a saved page.
 */
class SkandiaSource(private val indexUrl: String = INDEX) : CinemaSource {

  override val id = SOURCE_ID
  override val label = "Bio Skandia"

  override suspend fun fetchScreenings(
    cinemas: List<Cinema>,
    watchlist: List<WatchlistItem>,
    from: Instant,
    to: Instant,
  ): List<RawScreening> =
    withContext(Dispatchers.IO) {
      val cinema = cinemas.firstOrNull() ?: return@withContext emptyList()
      if (watchlist.isEmpty()) return@withContext emptyList()

      val relevant =
        parseIndex(Http.getString(indexUrl, accept = "text/html"), indexUrl).filter { film ->
          TitleMatcher.findMatch(
            MatchCandidate(
              title = film.title,
              originalTitle = film.originalTitle,
              year = film.year,
            ),
            watchlist,
          ) != null
        }
      if (relevant.isEmpty()) return@withContext emptyList()

      relevant.flatMap { film ->
        parseFilmPage(
            html = Http.getString(film.url, accept = "text/html"),
            baseUrl = film.url,
            cinema = cinema,
            title = film.title,
            originalTitle = film.originalTitle,
            year = film.year,
          )
          .filter { !it.startTime.isBefore(from) && !it.startTime.isAfter(to) }
      }
    }

  /** Films currently on the schedule, with their detail-page URLs. */
  internal fun parseIndex(html: String, baseUrl: String): List<Film> =
    Jsoup.parse(html, baseUrl)
      .select("a[href*=/filmer/]")
      .mapNotNull { link ->
        val listed =
          link.selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null

        // Skandia runs its auditorium as a venue as well as a cinema — guided tours, live
        // performances, its own birthday party — all listed here alongside the films.
        if (ProgrammeStrands.isNonFilmEvent(listed)) return@mapNotNull null

        // And its films carry the cinema's own furniture: "The Odyssey (70MM)",
        // "Cinemateket: Persona", "Parasite (기생충)". The Korean is worth keeping — it is the
        // film's original title, and what TMDB indexes it under — the projector format is not.
        val cleaned = ProgrammeStrands.clean(listed)
        Film(
          title = cleaned.title,
          originalTitle = cleaned.originalTitle,
          year = cleaned.year,
          url = link.absUrl("href"),
        )
      }
      .distinctBy { it.url }

  /** Screening rows on one film's page. */
  internal fun parseFilmPage(
    html: String,
    baseUrl: String,
    cinema: Cinema,
    title: String,
    originalTitle: String? = null,
    year: Int? = null,
    today: LocalDate = LocalDate.now(SwedishDates.STOCKHOLM),
  ): List<RawScreening> =
    Jsoup.parse(html, baseUrl)
      .select("a[href*=secure.tickster.com]")
      .mapNotNull { link ->
        val row = link.parents().firstOrNull { it.hasClass("border-b") } ?: return@mapNotNull null
        val start = parseRow(row, today) ?: return@mapNotNull null

        RawScreening(
          cinemaId = cinema.id,
          cinemaName = cinema.name,
          title = title,
          originalTitle = originalTitle,
          year = year,
          startTime = start,
          bookingUrl = link.absUrl("href").ifBlank { baseUrl },
        )
      }
      .distinctBy { it.startTime }

  /** A row reads "lördag 12 Sep … 14:00"; the year is implied by the calendar. */
  private fun parseRow(row: Element, today: LocalDate): Instant? {
    val text = row.text()
    val date = Regex("(\\d{1,2})\\s+([A-Za-zÅÄÖåäö]{3,})").find(text) ?: return null
    val day = date.groupValues[1].toIntOrNull() ?: return null
    val month = SwedishDates.monthOf(date.groupValues[2]) ?: return null
    val time = SwedishDates.parseTime(text) ?: return null
    return SwedishDates.resolveYear(day, month, today)
      .atTime(time)
      .atZone(SwedishDates.STOCKHOLM)
      .toInstant()
  }

  internal data class Film(
    val title: String,
    val url: String,
    val originalTitle: String? = null,
    val year: Int? = null,
  )

  companion object {
    const val SOURCE_ID = "bio_skandia"
    private const val INDEX = "https://bioskandia.se/filmer/"
  }
}
