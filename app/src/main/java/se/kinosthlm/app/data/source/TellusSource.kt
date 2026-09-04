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

    val url = "$baseUrl?per_page=$PER_PAGE" +
      "&start_date=${DATE.format(LocalDateTime.ofInstant(from, SwedishDates.STOCKHOLM))}" +
      "&end_date=${DATE.format(LocalDateTime.ofInstant(to, SwedishDates.STOCKHOLM))}"

    parse(Http.getString(url), cinema).filter {
      !it.startTime.isBefore(from) && !it.startTime.isAfter(to)
    }
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

      val title = decodeEntities(event.optString("title")).takeIf { it.isNotBlank() }
        ?: return@mapNotNull null

      RawScreening(
        cinemaId = cinema.id,
        cinemaName = cinema.name,
        title = title,
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
    private const val PER_PAGE = 50
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val STORED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
  }
}
