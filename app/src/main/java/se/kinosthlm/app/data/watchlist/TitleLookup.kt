package se.kinosthlm.app.data.watchlist

import java.net.URLEncoder
import java.text.Normalizer
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

  /**
   * When TMDB last answered 429, or 0 if it never has.
   *
   * Worth surfacing rather than swallowing: a shared key can be rate limited by other people's
   * installs entirely, and the symptom — posters and identification quietly not happening — is
   * indistinguishable from the app being broken unless we say so.
   */
  @Volatile var lastRateLimitedAt: Long = 0L
    private set

  /** Every TMDB request goes through here, so nothing can be throttled without us noticing. */
  private fun get(url: String): String =
    try {
      Http.getString(url)
    } catch (error: Http.HttpStatusException) {
      if (error.code == 429) lastRateLimitedAt = System.currentTimeMillis()
      throw error
    }

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
    /**
     * Other names TMDB has answered under for this same film.
     *
     * One film comes back as "Smultronstället" when asked in Swedish and "Wild Strawberries"
     * when asked unlocalised. Both are the film's name as far as a cinema listing is concerned,
     * so both have to count when checking whether a candidate really is the title we asked for.
     */
    val aliases: Set<String> = emptySet(),
    /** TMDB's own popularity score, used to rank which same-named film to offer first. */
    val popularity: Double = 0.0,
    /**
     * Genre names, e.g. "Drama". Only the per-film endpoint names them — search results carry
     * numeric ids against a separate list — so this is empty except after [fetchMovieDetails].
     */
    val genres: List<String> = emptyList(),
  ) {
    val isFilm: Boolean get() = type == TYPE_MOVIE

    /** True when [wanted] (already strict-normalised) is one of this film's own names. */
    internal fun isNamed(wanted: String): Boolean =
      TitleMatcher.normalizeStrict(title) == wanted ||
        originalTitle?.let { TitleMatcher.normalizeStrict(it) == wanted } == true ||
        aliases.any { TitleMatcher.normalizeStrict(it) == wanted }
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

    // Strict comparison: identity, not cinema matching. Article-insensitive comparison would
    // make "The Sopranos" collide with the unrelated film "Sopranos".
    val wanted = TitleMatcher.normalizeStrict(query)
    val found = LinkedHashMap<Int, Candidate>()

    for ((text, language) in attempts(query)) {
      merge(found, search(text, language))
      if (found.values.any { it.isFilm && it.isNamed(wanted) }) break
    }

    val all = found.values.toList()
    if (all.isEmpty()) return@withContext Result(emptyList())

    val exact = all.filter { it.isNamed(wanted) }
    if (exact.isNotEmpty()) return@withContext Result(exact, matchedByAlias = false)

    // Two ways a cinema writes a title that is the film's, only not letter for letter: it drops
    // the article ("Rocky Horror Picture Show") or adds the series it belongs to ("Indiana
    // Jones: Raiders of the Lost Ark"). Both are safe to allow because what remains still has to
    // match a real title exactly — unlike a substring rule, which is how "Alien" ends up meaning
    // "Alien: Romulus".
    for (relaxed in relaxations(query)) {
      val hits = all.filter { it.isNamed(relaxed) }
      if (hits.isNotEmpty()) return@withContext Result(hits, matchedByAlias = false)
    }

    // Nothing TMDB *displays* is called this. It may still be the film's name in some country —
    // "Xiao Wu" is how China lists 小武 — so ask, rather than assume.
    //
    // Assuming is what this used to do: take TMDB's top-ranked film and call that the answer.
    // TMDB ranks partial matches by popularity, so that quietly turned "Autofiktion" into
    // "Bitter Christmas" and "Seven" into "Seven Snipers" — and a wrong film on your schedule
    // is worse than a missing one, because you cannot tell it is wrong without going to check.
    val verified =
      all.asSequence()
        .filter { it.isFilm }
        .take(ALIAS_CHECKS)
        .firstOrNull { hasAlternativeTitle(it.tmdbId, wanted) }
        ?: return@withContext Result(emptyList())

    // Keep everything sharing the verified film's name, so a remake is still offered as a choice.
    val verifiedTitle = TitleMatcher.normalizeStrict(verified.title)
    Result(
      all.filter { TitleMatcher.normalizeStrict(it.title) == verifiedTitle },
      matchedByAlias = true,
    )
  }

  /**
   * Strict-normalised readings of [query] beyond the literal one, most likely first.
   *
   * Only two, and both keep the whole of what is left: an article added at the front, and a
   * series label cut from it. Nothing here shortens a title to a fragment.
   */
  private fun relaxations(query: String): List<String> = buildList {
    val strict = TitleMatcher.normalizeStrict(query)
    if (strict.isBlank()) return@buildList
    // "Rocky Horror Picture Show" is listed by TMDB as "The Rocky Horror Picture Show".
    if (LEADING_ARTICLES.none { strict.startsWith("$it ") }) {
      LEADING_ARTICLES.forEach { add("$it $strict") }
    }
    // "Indiana Jones: Raiders of the Lost Ark" — the cinema names the series, TMDB names the
    // film. Only when what follows is substantial, so ": Romulus" can never become the title.
    val afterLabel = query.substringAfter(':', "").trim()
    if (afterLabel.length >= 8 && query.substringBefore(':').isNotBlank()) {
      add(TitleMatcher.normalizeStrict(afterLabel))
    }
  }

  /**
   * The searches to try, in order, stopping at the first that actually names the film.
   *
   * Swedish first, because every Stockholm cinema lists films under their Swedish release title
   * and TMDB only *displays* that title when asked in Swedish — unlocalised, "Drottning
   * Kristina" comes back as "Queen Christina" and fails the identity check, which is how a film
   * TMDB knows perfectly well ended up reported as not found.
   *
   * Then unlocalised, so an English or original-language listing still resolves.
   *
   * Then both again accent-folded: TMDB's search returns nothing at all for "Amelie från
   * Montmartre" while "Amelie fran Montmartre" finds Amélie straight away.
   */
  private fun attempts(query: String): List<Pair<String, String?>> = buildList {
    add(query to SWEDISH)
    add(query to null)
    val folded = foldAccents(query)
    if (folded != query) {
      add(folded to SWEDISH)
      add(folded to null)
    }
  }

  /**
   * Fold search results into what we already have, keeping every name a film answered under.
   *
   * First answer wins for the displayed title — the Swedish search runs first, so a Swedish
   * listing keeps its Swedish name — while later answers only contribute aliases.
   */
  private fun merge(into: LinkedHashMap<Int, Candidate>, found: List<Candidate>) {
    for (candidate in found) {
      val existing = into[candidate.tmdbId]
      into[candidate.tmdbId] =
        if (existing == null) candidate
        else
          existing.copy(
            aliases = existing.aliases + candidate.title + setOfNotNull(candidate.originalTitle)
          )
    }
  }

  /** Does TMDB list [wanted] (strict-normalised) among this film's release titles anywhere? */
  private fun hasAlternativeTitle(tmdbId: Int, wanted: String): Boolean =
    runCatching {
      val titles =
        JSONObject(get("$baseUrl/movie/$tmdbId/alternative_titles?$auth"))
          .optJSONArray("titles") ?: return false
      (0 until titles.length()).any { index ->
        val alternative = titles.optJSONObject(index)?.optString("title").orEmpty()
        alternative.isNotBlank() && TitleMatcher.normalizeStrict(alternative) == wanted
      }
    }
      .getOrDefault(false)

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
    val films = runCatching { lookup(title) }.getOrNull()?.films.orEmpty()
    if (films.size == 1) return films.single()

    if (films.isNotEmpty()) {
      val byYear =
        year?.let { wanted ->
          films.filter { it.year != null && kotlin.math.abs(it.year - wanted) <= 1 }
        }
      return byYear?.singleOrNull()
    }

    // Multi-search found nothing named this. When the cinema published a year, there is one more
    // thing to try: a crowded title buries its film below twenty namesakes and a page of TV, and
    // multi-search has no year filter to cut through that — Bio Rio's "House (1977)" never
    // reached Hausu for exactly that reason. The movie endpoint does take a year.
    return year?.let { searchByYear(title, it) }
  }

  /** A year-scoped film search, accepting only a result actually named [title]. */
  private suspend fun searchByYear(title: String, year: Int): Candidate? =
    withContext(Dispatchers.IO) {
      val wanted = TitleMatcher.normalizeStrict(title)
      for ((text, language) in attempts(title)) {
        val hit =
          runCatching { searchFilms(text, year, language) }
            .getOrDefault(emptyList())
            .firstOrNull { it.isNamed(wanted) }
        if (hit != null) return@withContext hit
      }
      null
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

    val json = get("$baseUrl/find/$id?external_source=imdb_id&$auth")
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
      val entry = JSONObject(get("$baseUrl/movie/$tmdbId?$auth"))
      candidateOf(entry, forcedType = TYPE_MOVIE)
    }.getOrNull()
  }

  /** Fetch the IMDb id for a film, so entries from different providers share one identity. */
  suspend fun attachImdbId(candidate: Candidate): Candidate = withContext(Dispatchers.IO) {
    if (candidate.imdbId != null || !candidate.isFilm) return@withContext candidate
    val imdbId =
      runCatching {
        JSONObject(get("$baseUrl/movie/${candidate.tmdbId}/external_ids?$auth"))
          .optString("imdb_id")
          .takeIf { it.startsWith("tt") }
      }
        .getOrNull()
    candidate.copy(imdbId = imdbId)
  }

  /** One multi-search round trip, mapped to candidates. */
  private fun search(query: String, language: String? = null): List<Candidate> =
    resultsOf(
      "$baseUrl/search/multi?query=${encode(query)}&include_adult=false" +
        "${language.orEmpty().let { if (it.isEmpty()) "" else "&language=$it" }}&$auth"
    )

  /** The film-only endpoint, which unlike multi-search accepts a year. */
  private fun searchFilms(query: String, year: Int, language: String? = null): List<Candidate> =
    resultsOf(
      "$baseUrl/search/movie?query=${encode(query)}&include_adult=false&year=$year" +
        "${language.orEmpty().let { if (it.isEmpty()) "" else "&language=$it" }}&$auth",
      // /search/movie omits media_type; everything it returns is a film by definition.
      forcedType = TYPE_MOVIE,
    )

  private fun resultsOf(url: String, forcedType: String? = null): List<Candidate> {
    val results = JSONObject(get(url)).optJSONArray("results") ?: return emptyList()
    return (0 until results.length()).mapNotNull { index ->
      results.optJSONObject(index)?.let { candidateOf(it, forcedType) }
    }
  }

  /** "Amelie från Montmartre" → "Amelie fran Montmartre". Case and spacing are left alone. */
  private fun foldAccents(raw: String): String =
    Normalizer.normalize(raw, Normalizer.Form.NFD)
      .replace(Regex("""\p{Mn}+"""), "")
      .replace('ø', 'o')
      .replace('Ø', 'O')
      .replace('æ', 'a')
      .replace('Æ', 'A')
      .replace('ß', 's')

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
      genres = genreNames(entry),
    )
  }

  /** Genre names, present only on the per-film endpoint. Absent elsewhere, which is fine. */
  private fun genreNames(entry: JSONObject): List<String> {
    val array = entry.optJSONArray("genres") ?: return emptyList()
    return (0 until array.length()).mapNotNull {
      array.optJSONObject(it)?.optString("name")?.takeIf { name -> name.isNotBlank() }
    }
  }

  private val auth: String get() = "api_key=$apiKey"

  private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

  companion object {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/w342"

    const val TYPE_MOVIE = "movie"
    const val TYPE_TV = "tv"

    /** The language Stockholm cinemas publish in, and so the one to ask TMDB in first. */
    private const val SWEDISH = "sv-SE"

    /** Articles a cinema may drop from the front of a title, in the languages we see. */
    private val LEADING_ARTICLES = listOf("the", "a", "an", "den", "det", "en", "ett", "les", "la", "le")

    /**
     * How many top films to ask for alternative titles before giving up.
     *
     * One request each, on the miss path only. Three is enough for a genuine foreign-release
     * title to show up without turning every unmatched listing into a round of twenty calls.
     */
    private const val ALIAS_CHECKS = 3

    private val IMDB_ID = Regex("""tt\d{5,}""")
    private val TMDB_URL_ID = Regex("""themoviedb\.org/movie/(\d+)""")

    /** Pull the title id out of anything from a full URL to a bare "tt0013442". */
    fun extractImdbId(input: String): String? = IMDB_ID.find(input.trim())?.value

    /** Pull the numeric id out of a themoviedb.org film URL, e.g. ".../movie/603-the-matrix". */
    fun extractTmdbId(input: String): Int? =
      TMDB_URL_ID.find(input.trim())?.groupValues?.get(1)?.toIntOrNull()
  }
}
