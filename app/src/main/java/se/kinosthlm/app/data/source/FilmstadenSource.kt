package se.kinosthlm.app.data.source

import com.squareup.moshi.Types
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.kinosthlm.app.data.match.MatchCandidate
import se.kinosthlm.app.data.match.TitleMatcher
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http

/**
 * Programme for every cinema on the Nordic Cinema Group booking platform — all Filmstaden
 * venues plus Grand, Victoria, Sture, Saga and Grand Lidingö in Stockholm.
 *
 * This is the same unauthenticated JSON API that filmstaden.se itself calls from the browser.
 * No key, no token, no scraping.
 *
 * Strategy, and the reason this stays cheap: the catalogue of currently-scheduled films is
 * small (~70 nationwide), so we fetch it once, match it against the watchlist locally, and only
 * then ask for showings — one request per *matched* film. A typical watchlist matches a handful,
 * so a full sync is a few small requests rather than paging ~7000 irrelevant showings.
 */
class FilmstadenSource(private val baseUrl: String = BASE) : CinemaSource {

  override val id = SOURCE_ID
  override val label = "Filmstaden m.fl."

  override suspend fun fetchScreenings(
    cinemas: List<Cinema>,
    watchlist: List<WatchlistItem>,
    from: Instant,
    to: Instant,
  ): List<RawScreening> = withContext(Dispatchers.IO) {
    if (cinemas.isEmpty() || watchlist.isEmpty()) return@withContext emptyList()

    // Venue id -> our Cinema row. Venues we don't track are dropped later.
    val byRemoteId = cinemas.mapNotNull { c -> c.remoteId?.let { it to c } }.toMap()
    if (byRemoteId.isEmpty()) return@withContext emptyList()

    val scheduled = fetchScheduledMovies()

    // Match the catalogue against the watchlist before asking for any showings.
    val relevant = scheduled.mapNotNull { movie ->
      val candidate = MatchCandidate(
        title = movie.title,
        originalTitle = movie.originalTitle,
        year = movie.productionYear,
      )
      TitleMatcher.findMatch(candidate, watchlist)?.let { movie }
    }
    if (relevant.isEmpty()) return@withContext emptyList()

    val results = mutableListOf<RawScreening>()
    for (movie in relevant) {
      for (show in fetchShowsForMovie(movie.ncgId)) {
        val cinema = byRemoteId[show.cId] ?: continue
        val start = runCatching { Instant.parse(show.utc) }.getOrNull() ?: continue
        if (start.isBefore(from) || start.isAfter(to)) continue
        results += RawScreening(
          cinemaId = cinema.id,
          cinemaName = cinema.name,
          title = movie.title,
          originalTitle = movie.originalTitle,
          year = movie.productionYear,
          startTime = start,
          auditorium = show.st?.trim()?.ifBlank { null },
          formatTags = show.sa.orEmpty().filter { it.isNotBlank() },
          bookingUrl = bookingUrl(movie.slug),
          // lzp is the lowest ticket price for the show; 0 means "not priced yet".
          priceSek = show.lzp?.takeIf { it > 0 },
        )
      }
    }
    results
  }

  /** Films currently on Swedish screens. `false` = include special/event screenings too. */
  private fun fetchScheduledMovies(): List<Movie> {
    val json = Http.getString("$baseUrl/movie/scheduled/$LOCALE/1/$PAGE_SIZE/false")
    val type = Types.newParameterizedType(Paged::class.java, Movie::class.java)
    val adapter = Http.moshi.adapter<Paged<Movie>>(type)
    return adapter.fromJson(json)?.items.orEmpty()
  }

  /** Every upcoming showing of one film, nationwide; we filter to our venues in the caller. */
  private fun fetchShowsForMovie(movieNcgId: String): List<Show> {
    val url = "$baseUrl/show/stripped/$LOCALE/1/$PAGE_SIZE/" +
      "?filter.movieNcgId=$movieNcgId&Channel=Web"
    val json = Http.getString(url)
    val type = Types.newParameterizedType(Paged::class.java, Show::class.java)
    val adapter = Http.moshi.adapter<Paged<Show>>(type)
    return adapter.fromJson(json)?.items.orEmpty()
  }

  companion object {
    const val SOURCE_ID = "filmstaden"

    /**
     * The API filmstaden.se's own frontend uses. Discovered in its published JS bundle;
     * unauthenticated, no rate limit documented. Locale segment is "sv", not the country code.
     */
    private const val BASE = "https://services.cinema-api.com"
    private const val LOCALE = "sv"

    /** Comfortably above the ~7000 nationwide showings, so one page is always enough. */
    private const val PAGE_SIZE = 10_000

    /**
     * Films link to their filmstaden.se page, which lists every showing with a Book button.
     * The API exposes no per-showing deep link.
     */
    fun bookingUrl(slug: String): String = "https://www.filmstaden.se/film/$slug/"
  }
}

// --- Wire format. Field names are the API's own; the "stripped" show endpoint abbreviates. ---

internal data class Paged<T>(
  val totalNbrOfItems: Int = 0,
  val items: List<T> = emptyList(),
)

internal data class Movie(
  val ncgId: String,
  val title: String,
  val originalTitle: String? = null,
  val slug: String,
  val productionYear: Int? = null,
  val posterUrl: String? = null,
  val length: Int? = null,
)

internal data class Show(
  /** Movie ncgId. */
  val mId: String,
  /** Cinema ncgId, e.g. "NCG27927" for Filmstaden Sergel. */
  val cId: String,
  /** Cinema display name. */
  val ct: String? = null,
  /** Auditorium name, e.g. "Salong 2". */
  val st: String? = null,
  /** Screen attributes, e.g. "XL - vår största duk". */
  val sa: List<String>? = null,
  /** Start time, ISO-8601 UTC. */
  val utc: String,
  /** Lowest ticket price in SEK. */
  val lzp: Int? = null,
)
