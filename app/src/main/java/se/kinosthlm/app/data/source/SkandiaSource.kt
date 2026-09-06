package se.kinosthlm.app.data.source

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http

/**
 * Bio Skandia, Drottninggatan — read from its ticketing system rather than its website.
 *
 * The cinema's own site lists what it is promoting, which is not the same as what it is showing:
 * nine films there against thirty-six showings on Tickster, with a whole Korean film festival
 * missing entirely. Tickster is also better structured — every showing carries an explicit ISO
 * date, so no year has to be inferred from a Swedish month name.
 *
 * Two things make it awkward. It refuses to serve anyone without a session cookie, and it says
 * so with a **200** — the "session timed out" page is a normal response, so a cookie-less fetch
 * looks like a cinema with nothing on rather than an error. That is why this goes through
 * [Http.sessionClient]. And its pager is client-side: the markup holds every showing at once and
 * JavaScript hides all but fifteen, so what looks like three pages is a single request.
 *
 * No IMDb ids here, so matching stays on titles — which is why the title cleaning came first.
 */
class SkandiaSource(private val calendarUrl: String = CALENDAR) : CinemaSource {

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

      // Two calls, not one: the first is turned away with a session cookie and no programme, and
      // only the second — carrying that cookie — gets the real page.
      Http.getString(calendarUrl, accept = "text/html", withCookies = true)
      val html = Http.getString(calendarUrl, accept = "text/html", withCookies = true)

      parse(html, cinema).filter { !it.startTime.isBefore(from) && !it.startTime.isAfter(to) }
    }

  /** Split out from the fetch so the selectors can be tested against a saved page. */
  internal fun parse(html: String, cinema: Cinema): List<RawScreening> =
    Jsoup.parse(html, calendarUrl)
      .select("article.c-tile[data-startdate]")
      .mapNotNull { tile ->
        val listed =
          tile.selectFirst(".event-name")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null

        // Skandia runs its auditorium as a venue as well as a cinema: guided tours, stand-up,
        // live music, its own birthday party. All of it sits in the same calendar as the films.
        if (ProgrammeStrands.isNonFilmEvent(listed)) return@mapNotNull null

        val date =
          runCatching { LocalDate.parse(tile.attr("data-startdate")) }.getOrNull()
            ?: return@mapNotNull null
        val time =
          SwedishDates.parseTime(tile.selectFirst(".c-date__time")?.text().orEmpty())
            ?: return@mapNotNull null

        // "The Odyssey (70MM)", "Cinemateket: Persona", "Parasite (기생충)". The Korean is the
        // film's original title and what TMDB indexes it under; the projector format is not.
        val cleaned = ProgrammeStrands.clean(listed)

        RawScreening(
          cinemaId = cinema.id,
          cinemaName = cinema.name,
          title = cleaned.title,
          originalTitle = cleaned.originalTitle,
          year = cleaned.year,
          startTime = date.atTime(time).atZone(SwedishDates.STOCKHOLM).toInstant(),
          formatTags = cleaned.formats,
          bookingUrl = bookingUrlOf(tile.id()) ?: calendarUrl,
        )
      }
      .distinctBy { it.title to it.startTime }

  /**
   * The booking page for one showing, from the tile's own id.
   *
   * Tickster's buy button is an ASP.NET postback with no URL in it, but the id it posts —
   * `ERC_7ZU35WV8MHR86GJ` — is the event's own code, and `/sv/{code}` is its booking page. That
   * link bounces once through the session-timeout page and lands on the right basket, which is
   * exactly what a browser does with it.
   */
  private fun bookingUrlOf(tileId: String): String? =
    tileId.removePrefix("ERC_").takeIf { it.isNotBlank() && it != tileId }?.lowercase()?.let {
      "https://secure.tickster.com/sv/$it"
    }

  companion object {
    const val SOURCE_ID = "bio_skandia"

    /** Skandia's own Tickster storefront; `wux3mrj1um9x41g` is the venue, not one event. */
    private const val CALENDAR =
      "https://secure.tickster.com/sv/wux3mrj1um9x41g/selectevent"
  }
}
