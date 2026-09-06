package se.kinosthlm.app.data.source

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http

/**
 * Biocafé Tellus, Midsommarkransen.
 *
 * Runs WordPress with The Events Calendar, which exposes a documented REST API — one JSON
 * object per screening, with UTC start times and the Nortic ticket link. No scraping needed.
 */
class TellusSource(private val baseUrl: String = BASE) : CinemaSource {

  override val id = SOURCE_ID
  override val label = "Biocafé Tellus"

  override suspend fun fetchScreenings(
    cinemas: List<Cinema>,
    watchlist: List<WatchlistItem>,
    from: Instant,
    to: Instant,
  ): List<RawScreening> = withContext(Dispatchers.IO) {
    val cinema = cinemas.firstOrNull() ?: return@withContext emptyList()

    val window =
      "&start_date=${DATE.format(LocalDateTime.ofInstant(from, SwedishDates.STOCKHOLM))}" +
        "&end_date=${DATE.format(LocalDateTime.ofInstant(to, SwedishDates.STOCKHOLM))}"

    // Ask for the film categories only. Tellus is a cultural venue as much as a cinema — jazz
    // nights, a supper club, programme launches — and it files all of that in the same calendar.
    // Its own categorisation is a far better judge of what is a film than any guess we could make
    // from the title: it counts "Förstadens filmsalong" as a screening, which a keyword rule
    // would have thrown away.
    val filtered =
      runCatching { parse(Http.getString("$baseUrl?per_page=$PER_PAGE$window&categories=$FILM_CATEGORIES"), cinema) }
        .getOrDefault(emptyList())

    // If the category ids ever change under us, that request comes back empty rather than
    // failing — which would look exactly like a quiet week. Fall back to the whole calendar and
    // filter by name, which is worse but not silent.
    val screenings =
      filtered.ifEmpty {
        parse(Http.getString("$baseUrl?per_page=$PER_PAGE$window"), cinema)
          .filterNot { ProgrammeStrands.isNonFilmEvent(it.title) }
      }

    screenings.filter { !it.startTime.isBefore(from) && !it.startTime.isAfter(to) }
  }

  /** Split out from the fetch so the mapping is testable against a saved response. */
  internal fun parse(json: String, cinema: Cinema): List<RawScreening> {
    val events = JSONObject(json).optJSONArray("events") ?: return emptyList()

    return (0 until events.length()).mapNotNull { index ->
      val event = events.optJSONObject(index) ?: return@mapNotNull null
      // utc_start_date is "2026-09-05 16:00:00" — space-separated, already UTC.
      val start = event.optString("utc_start_date").takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDateTime.parse(it, STORED).toInstant(ZoneOffset.UTC) }.getOrNull() }
        ?: return@mapNotNull null

      val listed = decodeEntities(event.optString("title")).takeIf { it.isNotBlank() }
        ?: return@mapNotNull null

      // The category filter is the venue's judgement and gets the jazz nights and the supper
      // club right, but it is not the whole answer: "Förstadens filmsalong" is filed as a film
      // and is actually a secret-cinema night, with no title to match on by design. So the name
      // check stays as well.
      if (ProgrammeStrands.isNonFilmEvent(listed)) return@mapNotNull null

      // Branded even inside the film categories: "Frukostbio: Spider-Man – Brand New Day",
      // "Dokumentär med regissörsbesök: Lillpojkens flykt till väst".
      val cleaned = ProgrammeStrands.clean(listed)

      RawScreening(
        cinemaId = cinema.id,
        cinemaName = cinema.name,
        title = cleaned.title,
        originalTitle = cleaned.originalTitle,
        year = cleaned.year,
        startTime = start,
        // "website" is the Nortic ticket page; fall back to the event page itself.
        bookingUrl = event.optString("website").takeIf { it.isNotBlank() }
          ?: event.optString("url").takeIf { it.isNotBlank() }
          ?: HOME,
        priceSek = Regex("(\\d+)").find(event.optString("cost"))?.value?.toIntOrNull(),
      )
    }
  }

  /** WordPress returns titles with HTML entities ("Gunnar &#038; Lazlo"). */
  private fun decodeEntities(raw: String): String =
    raw.replace("&#038;", "&")
      .replace("&amp;", "&")
      .replace("&#8217;", "\u2019")
      .replace("&#8211;", "\u2013")
      .replace("&quot;", "\"")
      .trim()

  companion object {
    const val SOURCE_ID = "bio_tellus"
    private const val HOME = "https://tellusbio.nu/"

    private const val BASE = "https://tellusbio.nu/wp-json/tribe/events/v1/events"
    /**
     * The Events Calendar category ids Tellus files screenings under. Taken from its own
     * "Filmer" filter on tellusbio.nu/programmet.
     */
    private const val FILM_CATEGORIES = "15,23,28,38"
    private const val PER_PAGE = 50
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val STORED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  }
}
