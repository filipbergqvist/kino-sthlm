package se.kinosthlm.app.data.source

import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http

/**
 * Kulturhuset Stadsteatern's three cinemas — Klarabiografen, Skärisbiografen and Bio Husby.
 *
 * The website is Drupal with a Vue calendar, so its HTML arrives empty and the programme is
 * fetched afterwards; reading the page would have found nothing. What the page fetches is a
 * public Elasticsearch index of everything the house is putting on — 1,700 events across theatre,
 * exhibitions, concerts and film — which is far better than the calendar it draws: each entry
 * carries an exact start with its UTC offset, the auditorium, the ticket link and the price.
 *
 * We ask it exactly what the calendar asks it: the film category, from now on, in time order.
 * Nothing here reaches for anything the site does not show a visitor.
 *
 * Three venues out of one query, routed by `tixHall` — the same shape as Cinemateket's two.
 */
class KulturhusetSource(private val searchUrl: String = SEARCH) : CinemaSource {

  override val id = SOURCE_ID
  override val label = "Kulturhuset Stadsteatern"

  override suspend fun fetchScreenings(
    cinemas: List<Cinema>,
    watchlist: List<WatchlistItem>,
    from: Instant,
    to: Instant,
  ): List<RawScreening> = withContext(Dispatchers.IO) {
    if (cinemas.isEmpty()) return@withContext emptyList()

    val screenings = mutableListOf<RawScreening>()
    var offset = 0
    while (offset < MAX_EVENTS) {
      val page = parse(Http.postJson(searchUrl, query(from, offset), AUTHORIZATION), cinemas)
      screenings += page.screenings
      if (page.wasLastPage) break
      offset += PAGE_SIZE
    }

    screenings.filter { !it.startTime.isBefore(from) && !it.startTime.isAfter(to) }
  }

  /**
   * The same query the site's own calendar sends: film events, starting from [from], in order.
   *
   * The category is matched by its label rather than its numeric id because the label is what
   * the site's own facet shows and what survives a Drupal content rebuild.
   */
  private fun query(from: Instant, offset: Int): String =
    """
    {"size":$PAGE_SIZE,"from":$offset,
     "query":{"bool":{
       "must":[{"range":{"tixStartDate":{"gte":"${from.toString()}"}}}],
       "filter":[{"nested":{"path":"drupalCategory",
         "query":{"term":{"drupalCategory.label.keyword":"$FILM_CATEGORY"}}}}]}},
     "sort":[{"tixStartDate":{"order":"asc"}}]}
    """
      .trimIndent()

  /** One page of hits, and whether it was the last. */
  internal data class Page(val screenings: List<RawScreening>, val wasLastPage: Boolean)

  /** Split out from the fetch so the mapping can be tested against a saved response. */
  internal fun parse(json: String, cinemas: List<Cinema>): Page {
    val byHall = cinemas.associateBy { (it.remoteId ?: it.name).lowercase() }
    val hits = JSONObject(json).optJSONObject("hits")?.optJSONArray("hits") ?: return Page(emptyList(), true)

    val screenings =
      (0 until hits.length()).mapNotNull { index ->
        val source = hits.optJSONObject(index)?.optJSONObject("_source") ?: return@mapNotNull null

        // tixName is the ticketing system's name and the cleaner of the two: the Drupal title
        // wraps it in whatever season it belongs to ("Lions Love (Timelessfest)").
        val listed =
          source.optString("tixName").takeIf { it.isNotBlank() }
            ?: source.optString("drupalTitle").takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        if (ProgrammeStrands.isNonFilmEvent(listed)) return@mapNotNull null

        val hall =
          source.optJSONArray("tixHall")?.optJSONObject(0)?.optString("label").orEmpty()
        val cinema = byHall[hall.lowercase()] ?: return@mapNotNull null

        val start =
          runCatching { OffsetDateTime.parse(source.optString("tixStartDate")).toInstant() }
            .getOrNull() ?: return@mapNotNull null

        // Kulturhuset runs strands like everyone else — "Knattebio: Lilla Spöket Laban".
        val cleaned = ProgrammeStrands.clean(listed)

        RawScreening(
          cinemaId = cinema.id,
          cinemaName = cinema.name,
          title = cleaned.title,
          originalTitle = cleaned.originalTitle,
          year = cleaned.year,
          startTime = start,
          auditorium = hall.takeIf { it.isNotBlank() },
          formatTags = cleaned.formats,
          bookingUrl =
            source.optString("tixTicketLink").takeIf { it.isNotBlank() && it != "null" }
              ?: source.optString("drupalLink").takeIf { it.isNotBlank() }
              ?: searchUrl,
          priceSek = source.optInt("tixMinPrice", 0).takeIf { it > 0 },
        )
      }

    return Page(screenings, wasLastPage = hits.length() < PAGE_SIZE)
  }

  companion object {
    const val SOURCE_ID = "kulturhuset"

    /** The index the site's own calendar reads, and the credentials its own page ships with. */
    private const val SEARCH = "https://elastic.kulturhusetstadsteatern.se/khst-events/_search"
    private const val AUTHORIZATION = "Basic ZWxhc3RpYzplbGFzdGlj"

    /** The facet the site labels "Bio". Theatre, dance and exhibitions share the index. */
    private const val FILM_CATEGORY = "Bio"

    private const val PAGE_SIZE = 100

    /**
     * A stop, not a target. The whole house schedules about 1,700 events at a time and film is a
     * tenth of that, so this is far beyond a full programme — it exists so a change at the other
     * end can never turn one sync into an unbounded walk.
     */
    private const val MAX_EVENTS = 1000
  }
}
