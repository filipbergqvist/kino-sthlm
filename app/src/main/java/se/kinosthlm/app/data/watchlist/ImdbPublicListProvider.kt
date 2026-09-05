package se.kinosthlm.app.data.watchlist

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http

/**
 * Best-effort refresh of a **public** IMDb watchlist or list.
 *
 * IMDb has no supported way to read a list from an app: the RSS feeds it used to publish now
 * return 404, and the CSV export endpoint requires a logged-in session. What is left is reading
 * the public page, which works today and will break whenever IMDb changes its markup or decides
 * this looks like a bot. Treat it as a convenience on top of [CsvWatchlistImporter], never as the
 * dependable path — the UI says as much.
 *
 * We read the page's embedded JSON (`__NEXT_DATA__`) rather than scraping links, because the
 * anchor soup on an IMDb page also contains recommendations, cast credits and "more like this".
 */
class ImdbPublicListProvider(private val listIdOrUrl: String) : WatchlistProvider {

  override val id = WatchlistItem.SOURCE_IMDB
  override val label = "IMDb (public list)"

  /** Technically automatable, but too fragile to run unattended and silently degrade. */
  override val supportsBackgroundSync = false

  override suspend fun isConnected(): Boolean = listIdOrUrl.isNotBlank()

  override suspend fun sync(): List<WatchlistItem> = withContext(Dispatchers.IO) {
    val html = Http.getString(pageUrl(listIdOrUrl), accept = "text/html")
    val payload = NEXT_DATA.find(html)?.groupValues?.get(1)
      ?: error("IMDb page had no data payload — the list may be private, or the page changed")

    val titles = mutableListOf<WatchlistItem>()
    collectTitles(JSONObject(payload), titles)

    if (titles.isEmpty()) {
      error("No titles found. Is the list public? Otherwise use the CSV export instead.")
    }
    titles.distinctBy { it.id }
  }

  /**
   * Walk the payload for objects that look like a title: an `tt` id plus a display title.
   * Structure-agnostic on purpose — IMDb reshuffles its nesting far more often than it renames
   * these two fields.
   */
  private fun collectTitles(node: Any?, into: MutableList<WatchlistItem>) {
    when (node) {
      is JSONArray -> for (i in 0 until node.length()) collectTitles(node.opt(i), into)
      is JSONObject -> {
        val imdbId = node.optString("id").takeIf { IMDB_ID.matches(it) }
        val title = node.optJSONObject("titleText")?.optString("text")
          ?: node.optString("titleText").takeIf { it.isNotBlank() && it != "null" }
        val isMovie = node.optJSONObject("titleType")?.optBoolean("isSeries") != true

        if (imdbId != null && !title.isNullOrBlank() && isMovie) {
          val year = node.optJSONObject("releaseYear")?.optInt("year")?.takeIf { it > 1800 }
          into += WatchlistItem(
            // No TMDB id from this source either; TitleResolver backfills it afterwards.
            id = WatchlistItem.idFor(tmdbId = null, imdbId, title, year),
            title = title,
            year = year,
            imdbId = imdbId,
          )
        }
        for (key in node.keys()) collectTitles(node.opt(key), into)
      }
    }
  }

  private companion object {
    val IMDB_ID = Regex("tt\\d{5,}")
    val NEXT_DATA = Regex(
      """<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""",
      RegexOption.DOT_MATCHES_ALL,
    )

    /** Accepts a full URL, a `ur…` user id, or an `ls…` list id. */
    fun pageUrl(input: String): String {
      val trimmed = input.trim()
      if (trimmed.startsWith("http")) return trimmed
      Regex("ls\\d+").find(trimmed)?.let { return "https://www.imdb.com/list/${it.value}/" }
      Regex("ur\\d+").find(trimmed)?.let {
        return "https://www.imdb.com/user/${it.value}/watchlist/"
      }
      error("Not an IMDb list — expected a list URL, an ls… list id, or a ur… user id")
    }
  }
}
