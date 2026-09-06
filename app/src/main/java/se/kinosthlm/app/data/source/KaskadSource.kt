package se.kinosthlm.app.data.source

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http

/**
 * Bio Kaskad, Blackeberg.
 *
 * One server-rendered calendar page. Every showing carries a bio.se booking link with the date
 * and time encoded in the path, which is a better source of truth than the Swedish date text
 * beside it: no locale, no year inference, and it survives a redesign of the page around it.
 */
class KaskadSource(private val calendarUrl: String = CALENDAR) : CinemaSource {

  override val id = SOURCE_ID
  override val label = "Bio Kaskad"

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

  /** Split out from the fetch so the selectors can be tested against a saved page. */
  internal fun parse(html: String, cinema: Cinema): List<RawScreening> {
    val document = Jsoup.parse(html, calendarUrl)

    return document.select("a[href*=bio.se/biografer/]").mapNotNull { link ->
      val href = link.absUrl("href").ifBlank { link.attr("href") }
      val showing = BioSeLinks.parse(href) ?: return@mapNotNull null

      // The title is the other link in the same table cell — the one that is not a booking link.
      val cell = link.parents().firstOrNull { it.tagName() == "td" } ?: return@mapNotNull null
      val listed =
        cell.select("a")
          .firstOrNull { it !== link && !it.attr("href").contains("bio.se/biografer/") }
          ?.text()
          ?.trim()
          ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null

      if (ProgrammeStrands.isNonFilmEvent(listed)) return@mapNotNull null
      val cleaned = ProgrammeStrands.clean(listed)

      RawScreening(
        cinemaId = cinema.id,
        cinemaName = cinema.name,
        title = cleaned.title,
        originalTitle = cleaned.originalTitle,
        year = cleaned.year,
        startTime = showing.startTime,
        formatTags = cleaned.formats,
        bookingUrl = href,
      )
    }
      .distinctBy { it.title to it.startTime }
  }

  companion object {
    const val SOURCE_ID = "bio_kaskad"
    private const val CALENDAR = "https://www.biokaskad.se/kalendarium"
  }
}
