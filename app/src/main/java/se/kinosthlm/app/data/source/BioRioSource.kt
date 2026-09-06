package se.kinosthlm.app.data.source

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http

/**
 * Bio Rio, Hornstull.
 *
 * `/sv/kalender` server-renders the full multi-week programme as plain HTML — day groups with a
 * Swedish date header, each holding one row per showing with its time, title, per-film link and
 * booking link. No JSON-LD, no client-side fetch, nothing gated: just markup to parse.
 */
class BioRioSource(private val calendarUrl: String = CALENDAR) : CinemaSource {

  override val id = SOURCE_ID
  override val label = "Bio Rio"

  override suspend fun fetchScreenings(
    cinemas: List<Cinema>,
    watchlist: List<WatchlistItem>,
    from: Instant,
    to: Instant,
  ): List<RawScreening> = withContext(Dispatchers.IO) {
    val cinema = cinemas.firstOrNull() ?: return@withContext emptyList()
    parse(Http.getString(calendarUrl, accept = "text/html"), cinema)
      .filter { !it.startTime.isBefore(from) && !it.startTime.isAfter(to) }
  }

  /** Split out from the fetch so the selectors are testable against a saved page. */
  internal fun parse(html: String, cinema: Cinema): List<RawScreening> =
    Jsoup.parse(html, calendarUrl).select("div.kalender-day-group").flatMap { dayGroup ->
      val header = dayGroup.selectFirst("h2.kalender-date-header")?.text().orEmpty()
      val dateMatch = DATE.find(header) ?: return@flatMap emptyList()
      val day = dateMatch.groupValues[1].toIntOrNull() ?: return@flatMap emptyList()
      val monthToken = dateMatch.groupValues[2]

      dayGroup.select("div.kalender-showtime-item").mapNotNull { item ->
        val timeText = item.selectFirst("span.kalender-showtime-time")?.text() ?: return@mapNotNull null
        val time = SwedishDates.parseTime(timeText) ?: return@mapNotNull null
        val start = SwedishDates.parse(day, monthToken, time) ?: return@mapNotNull null

        val titleLink = item.selectFirst("a.kalender-showtime-title") ?: return@mapNotNull null
        val listed = titleLink.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        if (ProgrammeStrands.isNonFilmEvent(listed)) return@mapNotNull null

        // Rio was the first adapter written and the last to be told about this, which is why its
        // listings kept reaching TMDB as "Sagan om ringen (1978)" and "The Odyssey 35mm -
        // otextad" — a year and a projector welded to the title, so nothing matched. The year is
        // worth keeping, just as a field rather than as part of the name.
        val cleaned = ProgrammeStrands.clean(listed)

        val bookingUrl =
          item.selectFirst("a.kalender-showtime-poster")?.absUrl("href")?.takeIf { it.isNotBlank() }
            ?: titleLink.absUrl("href").ifBlank { calendarUrl }

        RawScreening(
          cinemaId = cinema.id,
          cinemaName = cinema.name,
          title = cleaned.title,
          originalTitle = cleaned.originalTitle,
          year = cleaned.year,
          startTime = start.atZone(SwedishDates.STOCKHOLM).toInstant(),
          formatTags = cleaned.formats,
          bookingUrl = bookingUrl,
        )
      }
    }

  companion object {
    const val SOURCE_ID = "bio_rio"
    private const val CALENDAR = "https://www.biorio.se/sv/kalender"
    private val DATE = Regex("(\\d{1,2})\\s+([A-Za-zÅÄÖåäö]+)")
  }
}
