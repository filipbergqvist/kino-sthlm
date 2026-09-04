package se.kinosthlm.app.data.source

import java.time.Instant
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem

/**
 * One programme provider. A source may serve a single venue (Bio Rio) or a whole chain
 * (Filmstaden and everything else on the Nordic Cinema Group API).
 *
 * ## Adding a cinema
 * 1. Implement this interface in `data/source/`.
 * 2. Register it in [CinemaSourceRegistry].
 * 3. Add the venue rows to `AppDatabase.defaultCinemas` with a matching `sourceId`.
 *
 * ## Contract
 * Throw on failure. Never invent, cache-substitute or pad results: an empty list means "this
 * venue genuinely has nothing scheduled", and callers report a thrown error to the user as a
 * broken source. Returning plausible-looking placeholder data is strictly worse than failing.
 */
interface CinemaSource {

  /** Stable id, referenced by [Cinema.sourceId]. */
  val id: String

  /** Human-readable name for error messages, e.g. "Filmstaden". */
  val label: String

  /**
   * Fetch every screening at [cinemas] between [from] and [to].
   *
   * [watchlist] is passed so a source can narrow its queries — the Filmstaden API, for example,
   * can ask for one film at a time instead of paging thousands of irrelevant shows. Sources are
   * free to ignore it and return everything; matching happens afterwards either way.
   */
  suspend fun fetchScreenings(
    cinemas: List<Cinema>,
    watchlist: List<WatchlistItem>,
    from: Instant,
    to: Instant,
  ): List<RawScreening>
}

/** A screening as the cinema describes it, before matching against the watchlist. */
data class RawScreening(
  /** [Cinema.id] this belongs to. */
  val cinemaId: String,
  val cinemaName: String,
  val title: String,
  val originalTitle: String? = null,
  val year: Int? = null,
  val imdbId: String? = null,
  val startTime: Instant,
  val auditorium: String? = null,
  val formatTags: List<String> = emptyList(),
  val bookingUrl: String,
  val priceSek: Int? = null,
)
