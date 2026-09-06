package se.kinosthlm.app.data.watchlist

import java.time.Instant
import java.time.format.DateTimeFormatter
import se.kinosthlm.app.data.model.WatchlistItem

/**
 * Writes the watchlist as a CSV Trakt will accept back — and that this app can read back too.
 *
 * Shaped to Trakt's documented import format rather than our own: the point of an export is to
 * get your list out of this app and into something that outlives it. Every row is a watchlist
 * entry, so `watchlisted_at` is filled in and `watched_at` left empty — this app tracks what you
 * mean to see, and never claims to know what you have seen.
 *
 * Two columns beyond Trakt's list follow the required ones: `title` and `year`. Trakt's importer
 * is field-name based and ignores columns it does not know, so they cost nothing there — but
 * without them our own export is the one format we cannot usefully re-import, since a bare id
 * gives a watchlist of nameless rows until every one has been looked up again.
 *
 * Films with no external id are skipped: an id is what Trakt matches on, and a title alone would
 * import as the wrong film or not at all.
 */
object WatchlistCsvExporter {

  private const val HEADER = "id,type,watched_at,watchlisted_at,rating,rated_at,title,year"

  fun toCsv(items: List<WatchlistItem>): String {
    val rows =
      items
        .filter { it.isMatchable }
        .mapNotNull { item ->
          val id =
            when {
              item.imdbId != null -> "imdb_id:${item.imdbId}"
              item.tmdbId != null -> "tmdb_id:${item.tmdbId}"
              else -> null
            } ?: return@mapNotNull null
          val title = quote(item.title)
          "$id,movie,,${isoDate(item.addedAt)},,,$title,${item.year ?: ""}"
        }

    return (listOf(HEADER) + rows).joinToString("\n", postfix = "\n")
  }

  /** Titles contain commas and quotes; CSV escapes both by doubling the quote. */
  private fun quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

  /** Trakt wants ISO 8601; the column is when the film joined the list. */
  private fun isoDate(epochMillis: Long): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis).let {
      Instant.ofEpochSecond(it.epochSecond)
    })
}
