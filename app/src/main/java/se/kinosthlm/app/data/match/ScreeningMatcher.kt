package se.kinosthlm.app.data.match

import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.source.RawScreening

/**
 * Links a cinema screening to the watchlist entry it is a showing of.
 *
 * TMDB id is the standardized key across the whole watchlist (see [WatchlistItem.idFor]), so the
 * authoritative link is: resolve what film the screening actually is, then look for a watchlist
 * entry with that same TMDB id. That resolution is a network call, so it happens once per sync in
 * the repository and is handed in here as a plain lookup function — this class itself does no
 * I/O, which is what makes it testable without a fake server.
 *
 * Text matching remains the fallback, for exactly the cases id-matching cannot cover: TMDB is not
 * configured, the lookup failed for this title, or the watchlist entry has not been identified
 * yet (a bare Google TV import between syncs). It is never used to override an id-based verdict —
 * see the negative-match note on [match].
 */
object ScreeningMatcher {

  data class Match(
    val screening: RawScreening,
    val item: WatchlistItem,
    /** True when this came from comparing TMDB ids rather than titles. */
    val matchedByTmdbId: Boolean,
  )

  /**
   * Match every screening in [screenings] against [watchlist].
   *
   * [tmdbIdFor] resolves a screening to the TMDB id of the film it actually is, or null if that
   * could not be determined (typically because [se.kinosthlm.app.data.watchlist.TitleLookup] is
   * unconfigured or the title did not resolve to exactly one film). Pass a cache-backed lookup —
   * several screenings usually share one film — the repository is where that caching lives.
   */
  fun match(
    screenings: List<RawScreening>,
    watchlist: List<WatchlistItem>,
    tmdbIdFor: (RawScreening) -> Int?,
  ): List<Match> {
    val byTmdbId = watchlist.filter { it.tmdbId != null }.associateBy { it.tmdbId }

    return screenings.mapNotNull { screening ->
      val resolvedTmdbId = tmdbIdFor(screening)

      // Authoritative: TMDB says this screening is exactly this watchlist entry.
      resolvedTmdbId?.let { byTmdbId[it] }?.let { return@mapNotNull Match(screening, it, matchedByTmdbId = true) }

      val viaTitle =
        TitleMatcher.findMatch(
          MatchCandidate(
            title = screening.title,
            originalTitle = screening.originalTitle,
            year = screening.year,
            imdbId = screening.imdbId,
          ),
          watchlist,
        ) ?: return@mapNotNull null

      // TMDB explicitly identified the screening as a *different* film from the one text
      // matching picked — despite the similar title, these are not the same entry, so the
      // fuzzy match is a false positive and must not stand.
      if (resolvedTmdbId != null && viaTitle.tmdbId != null && viaTitle.tmdbId != resolvedTmdbId) {
        return@mapNotNull null
      }

      Match(screening, viaTitle, matchedByTmdbId = false)
    }
  }
}
