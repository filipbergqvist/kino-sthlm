package se.kinosthlm.app.data.watchlist

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import se.kinosthlm.app.BuildConfig
import se.kinosthlm.app.data.match.TitleMatcher
import se.kinosthlm.app.data.net.Http

/**
 * Puts an identity on a bare title, using TMDB.
 *
 * A Google TV export gives nothing but a name — no year, no id, and no way to tell a film from a
 * TV series. TMDB answers all three: it is a documented, sanctioned API (unlike scraping IMDb),
 * it indexes Swedish and other release titles as well as originals, and its multi-search labels
 * every result with a media type, so series filter themselves out.
 *
 * Needs a free API key. Without one the app still works — imports, syncing and matching are all
 * unaffected — but bare titles stay unidentified, so series are not filtered and same-named
 * films cannot be told apart. [isConfigured] reports that, and the UI says so plainly.
 */
class TitleLookup(
  /**
   * Read per call rather than captured, so a key pasted into Settings takes effect immediately
   * instead of only after the next app start.
   */
  private val apiKeyProvider: () -> String = { BuildConfig.TMDB_API_KEY },
  private val baseUrl: String = BASE,
) {

  /** Fixed-key constructor, for tests and anywhere the key cannot change under us. */
  constructor(apiKey: String, baseUrl: String = BASE) : this({ apiKey }, baseUrl)

  private val apiKey: String get() = apiKeyProvider()

  val isConfigured: Boolean get() = apiKey.isNotBlank()

  /** What we learned about one title. */
  data class Result(
    val candidates: List<Candidate>,
    /**
     * True when the query matched a release or alternative title and TMDB answered under a
     * different primary one — a Swedish entry resolving to its English title, typically.
     */
    val matchedByAlias: Boolean = false,
  ) {
    val films: List<Candidate> get() = candidates.filter { it.isFilm }

    /** True when every plausible reading of this title is a TV series, not a film. */
    val isSeries: Boolean get() = candidates.isNotEmpty() && films.isEmpty()
  }

  data class Candidate(
    val tmdbId: Int,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val type: String,
    val posterUrl: String?,
    /** Short synopsis, straight from TMDB — no extra request, every response carries it. */
    val overview: String? = null,
    /** Filled in lazily by [attachImdbId]; TMDB's search results do not carry it. */
    val imdbId: String? = null,
    /** TMDB's own popularity score, used to rank which same-named film to offer first. */
    val popularity: Double = 0.0,
  ) {
    val isFilm: Boolean get() = type == TYPE_MOVIE
  }

  /**
   * Look [title] up and return the entries that plausibly *are* that title.
   *
   * Multi-search is deliberate over movie-search: knowing that "The Sopranos" is a series is the
   * point, not an inconvenience. Results are then narrowed to those actually named the query,
   * since TMDB ranks partial matches too — otherwise "Nosferatu" would offer every film with
   * the word in its title as an equally likely answer.
   */
  suspend fun lookup(title: String): Result = withContext(Dispatchers.IO) {
    require(isConfigured) { "No TMDB API key configured" }
    val query = title.trim()
    if (query.isEmpty()) return@withContext Result(emptyList())

    val json = Http.getString("$baseUrl/search/multi?query=${encode(query)}&include_adult=false&$auth")
    val results = JSONObject(json).optJSONArray("results") ?: return@withContext Result(emptyList())

    val all =
      (0 until results.length()).mapNotNull { index ->
        results.optJSONObject(index)?.let(::candidateOf)
      }
    if (all.isEmpty()) return@withContext Result(emptyList())

    // Strict comparison: identity, not cinema matching. Article-insensitive comparison would
    // make "The Sopranos" collide with the unrelated film "Sopranos".
    val wanted = TitleMatcher.normalizeStrict(query)
    val exact =
      all.filter {
        TitleMatcher.normalizeStrict(it.title) == wanted ||
          it.originalTitle?.let { original -> TitleMatcher.normalizeStrict(original) == wanted } == true
      }
    if (exact.isNotEmpty()) return@withContext Result(exact, matchedByAlias = false)

    // TMDB matched a title it does not display — an alternative or regional one. Trust its
    // ranking for what the query meant, then keep everything sharing that title so a remake is
    // still offered as a choice.
    val best = all.firstOrNull { it.isFilm } ?: return@withContext Result(emptyList())
    val bestTitle = TitleMatcher.normalizeStrict(best.title)
    Result(
      all.filter { TitleMatcher.normalizeStrict(it.title) == bestTitle },
      matchedByAlias = true,
    )
  }

  /**
   * Best-effort single TMDB match for a cinema listing's raw title.
   *
   * This is the other half of "standardize on TMDB id": [lookup] identifies what a *watchlist*
   * entry is, this identifies what a *cinema screening* is, and matching becomes comparing the
   * two ids instead of comparing strings. Conservative like the rest of this class — returns
   * null rather than guessing when a title is shared by several films and there is no year to
   * separate them, so the caller falls back to text matching for exactly that screening.
   */
  suspend fun resolveBestMatch(title: String, year: Int? = null): Candidate? {
    if (!isConfigured) return null
    val result = runCatching { lookup(title) }.getOrNull() ?: return null
    val films = result.films
    if (films.isEmpty()) return null
    if (films.size == 1) return films.single()

    val byYear = year?.let { films.filter { film -> film.year != null && kotlin.math.abs(film.year - it) <= 1 } }
    return byYear?.singleOrNull()
  }

  /**
   * Resolve an IMDb id straight to its film.
   *
   * This is why adding by hand takes a link rather than a typed title and year: the entry
   * arrives already identified, so it can never be the wrong film of two sharing a name.
   */
  suspend fun lookupByImdbId(imdbId: String): Candidate? = withContext(Dispatchers.IO) {
    require(isConfigured) { "No TMDB API key configured" }
    val id = extractImdbId(imdbId) ?: return@withContext null

    val json = Http.getString("$baseUrl/find/$id?external_source=imdb_id&$auth")
    val root = JSONObject(json)

    root.optJSONArray("movie_results")?.optJSONObject(0)?.let { movie ->
      return@withContext candidateOf(movie, forcedType = TYPE_MOVIE)?.copy(imdbId = id)
    }
    // Report a series honestly rather than pretending we found nothing.
    root.optJSONArray("tv_results")?.optJSONObject(0)?.let { show ->
      return@withContext candidateOf(show, forcedType = TYPE_TV)?.copy(imdbId = id)
    }
    null
  }

  /**
   * Fetch a film's poster and synopsis directly by TMDB id.
   *
   * Used to backfill entries that already have a TMDB id (Trakt hands one back with every
   * import) but never went through a title search or lookup, so they never picked up a poster or
   * overview. One request per film, same shape as everything else here.
   */
  suspend fun fetchMovieDetails(tmdbId: Int): Candidate? = withContext(Dispatchers.IO) {
    if (!isConfigured) return@withContext null
    runCatching {
      val entry = JSONObject(Http.getString("$baseUrl/movie/$tmdbId?$auth"))
      candidateOf(entry, forcedType = TYPE_MOVIE)
    }.getOrNull()
  }

  /** Fetch the IMDb id for a film, so entries from different providers share one identity. */
  suspend fun attachImdbId(candidate: Candidate): Candidate = withContext(Dispatchers.IO) {
    if (candidate.imdbId != null || !candidate.isFilm) return@withContext candidate
    val imdbId =
      runCatching {
        JSONObject(Http.getString("$baseUrl/movie/${candidate.tmdbId}/external_ids?$auth"))
          .optString("imdb_id")
          .takeIf { it.startsWith("tt") }
      }
        .getOrNull()
    candidate.copy(imdbId = imdbId)
  }

  /** TMDB uses `title`/`release_date` for films and `name`/`first_air_date` for series. */
  private fun candidateOf(entry: JSONObject, forcedType: String? = null): Candidate? {
    val type = forcedType ?: entry.optString("media_type")
    if (type != TYPE_MOVIE && type != TYPE_TV) return null

    val title =
      (if (type == TYPE_MOVIE) entry.optString("title") else entry.optString("name"))
        .takeIf { it.isNotBlank() } ?: return null
    val original =
      (if (type == TYPE_MOVIE) entry.optString("original_title") else entry.optString("original_name"))
        .takeIf { it.isNotBlank() }
    val date =
      if (type == TYPE_MOVIE) entry.optString("release_date") else entry.optString("first_air_date")

    return Candidate(
      tmdbId = entry.optInt("id").takeIf { it > 0 } ?: return null,
      title = title,
      originalTitle = original,
      year = date.take(4).toIntOrNull()?.takeIf { it > 1800 },
      type = type,
      posterUrl =
        entry.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }
          ?.let { "$IMAGE_BASE$it" },
      overview = entry.optString("overview").takeIf { it.isNotBlank() },
      popularity = entry.optDouble("popularity", 0.0).takeIf { !it.isNaN() } ?: 0.0,
    )
  }

  private val auth: String get() = "api_key=$apiKey"

  private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

  companion object {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w342"

    const val TYPE_MOVIE = "movie"
    const val TYPE_TV = "tv"

    private val IMDB_ID = Regex("""tt\d{5,}""")
    private val TMDB_URL_ID = Regex("""themoviedb\.org/movie/(\d+)""")

    /** Pull the title id out of anything from a full URL to a bare "tt0013442". */
    fun extractImdbId(input: String): String? = IMDB_ID.find(input.trim())?.value

    /** Pull the numeric id out of a themoviedb.org film URL, e.g. ".../movie/603-the-matrix". */
    fun extractTmdbId(input: String): Int? =
      TMDB_URL_ID.find(input.trim())?.groupValues?.get(1)?.toIntOrNull()
  }
}
