package se.kinosthlm.app.data.watchlist

import android.util.Log
import kotlinx.coroutines.delay
import se.kinosthlm.app.data.model.TitleCandidate
import se.kinosthlm.app.data.model.WatchlistItem

/**
 * Turns bare titles into identified films.
 *
 * Runs over watchlist entries that have no IMDb id — in practice everything from a Google TV
 * export — and decides one of three things per title:
 *
 *  - **A film.** Attach the IMDb id, year and poster. Matching gets much more reliable.
 *  - **A TV series.** Mark it, so it stops cluttering the watchlist and is never matched.
 *  - **Ambiguous.** Several films share the name and nothing separates them. Record the options
 *    and flag it, so the user picks rather than the app guessing.
 *
 * Failure is not fatal: a title that cannot be looked up stays unresolved and is retried next
 * time. Nothing is invented and nothing is silently deleted.
 */
class TitleResolver(private val lookup: TitleLookup = TitleLookup()) {

  data class Resolution(
    val item: WatchlistItem,
    val candidates: List<TitleCandidate> = emptyList(),
    /** The entry's key before identification, which may have changed it. */
    val oldId: String = item.id,
  )

  data class Outcome(
    val resolutions: List<Resolution> = emptyList(),
    val identified: Int = 0,
    val series: Int = 0,
    val ambiguous: Int = 0,
    val failed: Int = 0,
    /** True when no TMDB key is configured, so nothing could be looked up at all. */
    val unavailable: Boolean = false,
  )

  /**
   * Resolve up to [limit] entries, newest first.
   *
   * [onProgress] reports (done, total) so a long first pass over a large import can show
   * something. A short pause between requests keeps us from hammering the endpoint.
   */
  suspend fun resolve(
    items: List<WatchlistItem>,
    limit: Int = Int.MAX_VALUE,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
  ): Outcome {
    // Without a key there is nothing to ask; titles stay unidentified rather than guessed at.
    if (!lookup.isConfigured) return Outcome(unavailable = true)

    val pending = items.filter { it.needsLookup() }.take(limit)
    if (pending.isEmpty()) return Outcome()

    val resolutions = mutableListOf<Resolution>()
    var identified = 0
    var series = 0
    var ambiguous = 0
    var failed = 0

    pending.forEachIndexed { index, item ->
      onProgress(index, pending.size)

      val result = runCatching { lookup.lookup(item.title) }.getOrElse { error ->
        Log.d(TAG, "Lookup failed for ${item.title}: ${error.message}")
        failed++
        return@forEachIndexed
      }

      when {
        result.candidates.isEmpty() -> failed++

        result.isSeries -> {
          series++
          resolutions +=
            Resolution(item.copy(titleType = WatchlistItem.TYPE_SERIES, needsReview = false))
        }

        else -> {
          // A year from the export settles it without troubling the user.
          val byYear = item.year?.let { year ->
            result.films.filter { it.year != null && kotlin.math.abs(it.year - year) <= 1 }
          }.orEmpty()

          val decided = if (byYear.size == 1) byYear.single()
          else if (result.films.size == 1) result.films.single()
          else null

          if (decided != null) {
            identified++
            // One extra call so entries from different providers share one identity: TMDB's
            // search results do not carry the IMDb id, and that is what Trakt and IMDb key on.
            val full = runCatching { lookup.attachImdbId(decided) }.getOrDefault(decided)
            val year = item.year ?: full.year
            resolutions +=
              Resolution(
                item = item.copy(
                  // Adopting the IMDb id changes the key, which is what merges the same film
                  // arriving from two different lists into one entry.
                  id = WatchlistItem.idFor(full.imdbId ?: item.imdbId, item.title, year),
                  imdbId = full.imdbId ?: item.imdbId,
                  tmdbId = full.tmdbId,
                  year = year,
                  posterUrl = item.posterUrl ?: full.posterUrl,
                  titleType = WatchlistItem.TYPE_MOVIE,
                  needsReview = false,
                ),
                oldId = item.id,
              )
          } else {
            ambiguous++
            resolutions +=
              Resolution(
                item.copy(titleType = WatchlistItem.TYPE_MOVIE, needsReview = true),
                result.films.map { candidate ->
                  TitleCandidate(
                    id = "${item.id}|${candidate.tmdbId}",
                    watchlistItemId = item.id,
                    tmdbId = candidate.tmdbId,
                    imdbId = candidate.imdbId,
                    title = candidate.title,
                    year = candidate.year,
                    titleType = candidate.type,
                    posterUrl = candidate.posterUrl,
                  )
                },
              )
          }
        }
      }

      // Be a considerate client of an endpoint nobody promised us.
      delay(REQUEST_SPACING_MILLIS)
    }

    onProgress(pending.size, pending.size)
    return Outcome(resolutions, identified, series, ambiguous, failed)
  }

  /**
   * Entries worth looking up: no IMDb id yet and not yet classified. Trakt and IMDb imports
   * arrive with ids and skip this entirely.
   *
   * Deliberately does *not* skip [WatchlistItem.needsReview]. An import flags same-named rows
   * for review before anything has been looked up, and those are precisely the ones that need
   * candidates fetched — skipping them would leave the user a review sheet with nothing to pick
   * from. Once resolved, the type is set, so they are not looked up twice.
   */
  private fun WatchlistItem.needsLookup(): Boolean =
    imdbId == null && titleType == WatchlistItem.TYPE_UNKNOWN

  private companion object {
    const val TAG = "TitleResolver"
    const val REQUEST_SPACING_MILLIS = 120L
  }
}
