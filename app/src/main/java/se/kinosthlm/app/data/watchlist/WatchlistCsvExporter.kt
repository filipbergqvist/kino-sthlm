package se.kinosthlm.app.data.watchlist

import java.time.Instant
import java.time.format.DateTimeFormatter
import se.kinosthlm.app.data.model.WatchlistItem

/**
 * Writes the watchlist as a CSV Trakt will accept back.
 *
 * Deliberately shaped to Trakt's documented import format rather than our own: the point of an
 * export is to get your list out of this app and into something that outlives it. Every row is a
 * watchlist entry, so `watchlisted_at` is filled in and `watched_at` left empty — this app tracks
 * what you mean to see, and never claims to know what you have seen.
 *
 * Films with no external id are skipped: an id is what Trakt matches on, and a title alone would
 * import as the wrong film or not at all.
 */
object WatchlistCsvExporter {

  private const val HEADER = "id,type,watched_at,watchlisted_at,rating,rated_at"

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
          "$id,movie,,${isoDate(item.addedAt)},,"
        }

    return (listOf(HEADER) + rows).joinToString("\n", postfix = "\n")
  }

  /** Trakt wants ISO 8601; the column is when the film joined the list. */
  private fun isoDate(epochMillis: Long): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis).let {
      Instant.ofEpochSecond(it.epochSecond)
    })
}
