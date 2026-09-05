package se.kinosthlm.app.data.source

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http

/**
 * Bio Rio, Hornstull.
 *
 * The site publishes its programme as schema.org `ScreeningEvent` JSON-LD in the page head —
 * exact start times, the film, the director and a per-showing booking link. That is a published,
 * machine-readable contract, so we read it instead of scraping the rendered markup.
 *
 * **Known limitation: short lookahead.** This JSON-LD block always holds exactly the *soonest
 * 20 screenings* — verified by requesting the page repeatedly and with `?limit=`/`?days=` query
 * parameters, none of which changed the count or the date range returned. On a normal week that
 * covers only 4–9 days ahead, well short of the ~21 day horizon every other source here can see.
 * A film scheduled further out at Bio Rio will not appear, or notify, until it enters that
 * rolling window — a few days before showtime rather than weeks before.
 *
 * The rest of Bio Rio's programme exists — the site itself is a Next.js app that fetches it
 * client-side from `api.biorio.se` for infinite scroll — but every content endpoint on that API
 * answers `401 {"error":"Missing API key"}` to a plain request, and the site's own `robots.txt`
 * explicitly disallows `/api/`. Both are the operator's own access control, deliberately set;
 * working around either is not something to do just because it is technically reachable. If Bio
 * Rio ever publishes a public API or a wider SSR'd listing, switch to it here.
 */
class BioRioSource(private val homeUrl: String = HOME) : CinemaSource {

  override val id = SOURCE_ID
  override val label = "Bio Rio"

  override suspend fun fetchScreenings(
    cinemas: List<Cinema>,
    watchlist: List<WatchlistItem>,
    from: Instant,
    to: Instant,
  ): List<RawScreening> = withContext(Dispatchers.IO) {
    val cinema = cinemas.firstOrNull() ?: return@withContext emptyList()
    parse(Http.getString(homeUrl, accept = "text/html"), cinema)
      .filter { !it.startTime.isBefore(from) && !it.startTime.isAfter(to) }
  }

  /** Split out from the fetch so the JSON-LD extraction is testable against a saved page. */
  internal fun parse(html: String, cinema: Cinema): List<RawScreening> =
    JsonLd.screeningEvents(html).mapNotNull { event ->
      val start = runCatching { Instant.parse(event.optString("startDate")) }.getOrNull()
        ?: return@mapNotNull null

      val work = event.optJSONObject("workPresented")
      val title = work?.optString("name")?.takeIf { it.isNotBlank() }
        ?: event.optString("name").takeIf { it.isNotBlank() }
        ?: return@mapNotNull null

      val bookingUrl = event.optJSONObject("offers")?.optString("url")?.takeIf { it.isNotBlank() }
        ?: event.optString("url").takeIf { it.isNotBlank() }
        ?: homeUrl

      RawScreening(
        cinemaId = cinema.id,
        cinemaName = cinema.name,
        title = title,
        startTime = start,
        bookingUrl = bookingUrl,
        priceSek = event.optJSONObject("offers")?.optDouble("price")?.takeIf { it > 0 }?.toInt(),
      )
    }

  companion object {
    const val SOURCE_ID = "bio_rio"
    private const val HOME = "https://www.biorio.se/sv"
  }
}

/** Shared JSON-LD extraction: several Stockholm cinemas publish schema.org data. */
internal object JsonLd {

  private val BLOCK = Regex(
    """<script[^>]+type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
  )

  /** Every `ScreeningEvent` in the page, whether standalone or wrapped in an `ItemList`. */
  fun screeningEvents(html: String): List<JSONObject> {
    val events = mutableListOf<JSONObject>()
    for (match in BLOCK.findAll(html)) {
      val raw = match.groupValues[1].trim()
      val parsed = runCatching { JSONObject(raw) }.getOrNull()
        ?: runCatching { JSONArray(raw) }.getOrNull()
        ?: continue
      collect(parsed, events)
    }
    return events
  }

  private fun collect(node: Any?, into: MutableList<JSONObject>) {
    when (node) {
      is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), into)
      is JSONObject -> {
        if (node.optString("@type") == "ScreeningEvent") into += node
        for (key in listOf("itemListElement", "item", "@graph", "subEvent")) {
          if (node.has(key)) collect(node.opt(key), into)
        }
      }
    }
  }
}
