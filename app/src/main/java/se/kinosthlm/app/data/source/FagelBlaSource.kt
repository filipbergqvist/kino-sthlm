package se.kinosthlm.app.data.source

import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http

/**
 * Bio Fågel Blå, Södermalm.
 *
 * The friendliest programme page of the lot: one request returns every showing to the end of the
 * schedule, and each carries a `<time datetime>` with a full ISO timestamp including the offset.
 * No date parsing, no year inference, no locale to get wrong.
 */
class FagelBlaSource(private val programmeUrl: String = PROGRAMME) : CinemaSource {

  override val id = SOURCE_ID
  override val label = "Bio Fågel Blå"

  override suspend fun fetchScreenings(
    cinemas: List<Cinema>,
    watchlist: List<WatchlistItem>,
    from: Instant,
    to: Instant,
  ): List<RawScreening> = withContext(Dispatchers.IO) {
    val cinema = cinemas.firstOrNull() ?: return@withContext emptyList()
    parse(Http.getString(programmeUrl, accept = "text/html"), cinema)
      .filter { !it.startTime.isBefore(from) && !it.startTime.isAfter(to) }
  }

  /** Split out from the fetch so the selectors can be tested against a saved page. */
  internal fun parse(html: String, cinema: Cinema): List<RawScreening> {
    val document = Jsoup.parse(html, programmeUrl)

    return document.select("article").mapNotNull { article ->
      val time = article.selectFirst("time[datetime]") ?: return@mapNotNull null
      val start =
        runCatching { OffsetDateTime.parse(time.attr("datetime")).toInstant() }.getOrNull()
          ?: return@mapNotNull null

      // The heading holds the title as its own text, with the time as a child element — so
      // ownText() is exactly the name and nothing else.
      val heading = article.selectFirst("h3") ?: return@mapNotNull null
      val listed = heading.ownText().trim().ifBlank { return@mapNotNull null }
      if (ProgrammeStrands.isNonFilmEvent(listed)) return@mapNotNull null

      val cleaned = ProgrammeStrands.clean(listed)

      RawScreening(
        cinemaId = cinema.id,
        cinemaName = cinema.name,
        title = cleaned.title,
        originalTitle = cleaned.originalTitle,
        year = cleaned.year,
        startTime = start,
        formatTags = cleaned.formats,
        // No per-showing booking link on this page; the programme is where you start.
        bookingUrl = article.selectFirst("a[href]")?.absUrl("href")?.ifBlank { null } ?: programmeUrl,
      )
    }
      .distinctBy { it.title to it.startTime }
  }

  companion object {
    const val SOURCE_ID = "bio_fagel_bla"
    private const val PROGRAMME = "https://biofagelbla.se/program/"
  }
}
