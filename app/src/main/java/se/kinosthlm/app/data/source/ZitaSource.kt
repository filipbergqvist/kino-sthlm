package se.kinosthlm.app.data.source

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http

/**
 * Zita Folkets Bio, Birger Jarlsgatan.
 *
 * Its site is a React front end over a small JSON API, and one call to the calendar endpoint
 * returns the lot: `week_events` for the days on sale now and `future_movies` for everything
 * beyond — which is the "fler visningar" list the page hides behind a filter with no URL of its
 * own. Both are keyed by date, and every showing carries a bio.se booking link with its exact
 * time, so nothing here needs a date parsed out of Swedish prose.
 *
 * A release year comes free in the payload, which is worth more than it sounds: it is what tells
 * a restored classic apart from its remake when the title alone cannot.
 */
class ZitaSource(private val apiUrl: String = API) : CinemaSource {

  override val id = SOURCE_ID
  override val label = "Zita Folkets Bio"

  override suspend fun fetchScreenings(
    cinemas: List<Cinema>,
    watchlist: List<WatchlistItem>,
    from: Instant,
    to: Instant,
  ): List<RawScreening> = withContext(Dispatchers.IO) {
    val cinema = cinemas.firstOrNull() ?: return@withContext emptyList()
    parse(Http.getString(apiUrl), cinema)
      .filter { !it.startTime.isBefore(from) && !it.startTime.isAfter(to) }
  }

  /** Split out from the fetch so the mapping is testable against a saved response. */
  internal fun parse(json: String, cinema: Cinema): List<RawScreening> {
    val root = JSONObject(json)

    // Both buckets have the same shape; the only difference is how far out they reach.
    return listOf("week_events", "future_movies")
      .mapNotNull { root.optJSONObject(it) }
      .flatMap { byDate ->
        byDate.keys().asSequence().flatMap { date ->
          val films = byDate.optJSONArray(date) ?: return@flatMap emptySequence()
          (0 until films.length()).asSequence().mapNotNull { films.optJSONObject(it) }
        }
      }
      .flatMap { film -> screeningsOf(film, cinema) }
      .distinctBy { it.title to it.startTime }
  }

  private fun screeningsOf(film: JSONObject, cinema: Cinema): List<RawScreening> {
    val listed = film.optString("title").trim().takeIf { it.isNotEmpty() } ?: return emptyList()
    if (ProgrammeStrands.isNonFilmEvent(listed)) return emptyList()

    val cleaned = ProgrammeStrands.clean(listed)
    val year = film.optString("kinoplex_year").toIntOrNull()?.takeIf { it in 1880..2100 }
    val showings = film.optJSONArray("showings") ?: return emptyList()

    return (0 until showings.length()).mapNotNull { index ->
      val showing = showings.optJSONObject(index) ?: return@mapNotNull null
      val bookingUrl = showing.optString("booking_url").takeIf { it.isNotBlank() }
        ?: return@mapNotNull null
      val parsed = BioSeLinks.parse(bookingUrl) ?: return@mapNotNull null

      RawScreening(
        cinemaId = cinema.id,
        cinemaName = cinema.name,
        title = cleaned.title,
        originalTitle = cleaned.originalTitle,
        year = cleaned.year ?: year,
        startTime = parsed.startTime,
        auditorium = showing.optString("screen_name").takeIf { it.isNotBlank() }
          ?.let { "Salong $it" },
        formatTags = cleaned.formats,
        bookingUrl = bookingUrl,
      )
    }
  }

  companion object {
    const val SOURCE_ID = "zita"
    private const val API = "https://zita.se/api/get-kalendarium-week.php"
  }
}
