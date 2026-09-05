package se.kinosthlm.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A film the user wants to see, imported from Trakt / IMDb / Google TV or added by hand. */
@Entity(tableName = "watchlist_items")
data class WatchlistItem(
  /** Stable synthetic key. Prefer "imdb:tt0083658", else "title:<slug>-<year>". */
  @PrimaryKey val id: String,
  val title: String,
  /** Original-language title when it differs from [title]; improves matching. */
  val originalTitle: String? = null,
  val year: Int? = null,
  val imdbId: String? = null,
  val tmdbId: Int? = null,
  val traktId: Int? = null,
  val posterUrl: String? = null,
  val director: String? = null,
  /**
   * Whether this is a film. Google TV exports mix films and TV series with nothing to tell them
   * apart, and a series can never have a cinema screening, so we resolve and then exclude them.
   */
  val titleType: String = TYPE_UNKNOWN,
  /**
   * True when we could not tell which film this is — typically a title shared by several films
   * with no year to separate them. The user picks the right one; until then it is not matched.
   */
  val needsReview: Boolean = false,
  /**
   * Hidden because the user deleted it here, even though a source still lists it.
   *
   * Without this a manual delete would simply undo itself on the next sync. The row is kept so
   * the suppression survives; it is discarded for real once no source lists the film any more.
   */
  val suppressed: Boolean = false,
  val addedAt: Long = System.currentTimeMillis(),
  val overview: String? = null,
) {
  val isFilm: Boolean get() = titleType != TYPE_SERIES

  /** Only confidently identified, still-wanted films are worth matching against listings. */
  val isMatchable: Boolean get() = isFilm && !needsReview && !suppressed

  companion object {
    const val SOURCE_TRAKT = "trakt"
    const val SOURCE_IMDB = "imdb"
    const val SOURCE_GOOGLE_TV = "google_tv"
    const val SOURCE_MANUAL = "manual"

    const val TYPE_MOVIE = "movie"
    const val TYPE_SERIES = "series"
    const val TYPE_UNKNOWN = "unknown"

    fun idFor(imdbId: String?, title: String, year: Int?): String =
      if (!imdbId.isNullOrBlank()) {
        "imdb:$imdbId"
      } else {
        val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        "title:$slug${year?.let { "-$it" } ?: ""}"
      }
  }
}

/** A Stockholm cinema venue. */
@Entity(tableName = "cinemas")
data class Cinema(
  @PrimaryKey val id: String,
  val name: String,
  val district: String,
  val address: String,
  val websiteUrl: String,
  /** Which [se.kinosthlm.app.data.source.CinemaSource] fetches this venue's programme. */
  val sourceId: String,
  /** Source-specific venue id, e.g. the Filmstaden API's "NCG27927". Null for single-venue sources. */
  val remoteId: String? = null,
  val specialty: String = "",
  val isEnabled: Boolean = true,
  val lastPolledAt: Long = 0L,
  val upcomingScreeningsCount: Int = 0,
)

/** A showing of a watchlisted film at a Stockholm cinema. */
@Entity(
  tableName = "screenings",
  indices = [Index("watchlistMovieId"), Index("screeningTime")],
)
data class Screening(
  /** Deterministic: cinemaId + watchlist id + start time, so re-scans do not duplicate. */
  @PrimaryKey val id: String,
  val watchlistMovieId: String,
  val movieTitle: String,
  val cinemaId: String,
  val cinemaName: String,
  val auditorium: String? = null,
  /** Epoch millis, UTC. */
  val screeningTime: Long,
  /** e.g. "IMAX", "Originalspråk", "35mm". Comma-joined. */
  val formatTag: String? = null,
  val bookingUrl: String,
  val priceSek: Int? = null,
  val foundAt: Long = System.currentTimeMillis(),
)

/** One row per notification sent, so a screening is never announced twice. */
@Entity(tableName = "notification_logs")
data class NotificationLog(
  @PrimaryKey val screeningId: String,
  val movieId: String,
  val movieTitle: String,
  val cinemaName: String,
  val bookingUrl: String,
  val notifiedAt: Long = System.currentTimeMillis(),
)

/**
 * Which source put a film on the watchlist. A film can have several.
 *
 * This is what makes removal upstream work: a sync replaces one source's rows wholesale, and a
 * film left with no rows at all is no longer on anybody's watchlist, so it goes. Drop a film
 * from IMDb while keeping it in Trakt and it stays; drop it from both and it disappears.
 */
@Entity(
  tableName = "watchlist_sources",
  primaryKeys = ["itemId", "sourceId"],
  indices = [Index("sourceId")],
)
data class WatchlistSource(
  val itemId: String,
  /** One of [WatchlistItem]'s SOURCE_ constants. */
  val sourceId: String,
  val addedAt: Long = System.currentTimeMillis(),
)

/**
 * A possible identity for an ambiguous watchlist entry, offered to the user to choose between.
 */
@Entity(tableName = "title_candidates", indices = [Index("watchlistItemId")])
data class TitleCandidate(
  @PrimaryKey val id: String,
  val watchlistItemId: String,
  val tmdbId: Int,
  val imdbId: String? = null,
  val title: String,
  val year: Int?,
  val titleType: String,
  val posterUrl: String? = null,
)

/** Per-source outcome of one sync, so the UI can say *which* cinema failed and why. */
data class SourceResult(
  val sourceId: String,
  val label: String,
  val screeningsFound: Int = 0,
  val error: String? = null,
) {
  val isSuccess: Boolean get() = error == null
}

/** Summary of the last sync, surfaced in Settings. */
data class SyncReport(
  val timestamp: Long = System.currentTimeMillis(),
  val watchlistSize: Int = 0,
  val watchlistImported: Int = 0,
  val cinemasPolled: Int = 0,
  val screeningsScanned: Int = 0,
  val matchedScreenings: Int = 0,
  val newNotifications: Int = 0,
  val sourceResults: List<SourceResult> = emptyList(),
  val statusMessage: String = "",
  val isSuccess: Boolean = true,
) {
  val failedSources: List<SourceResult> get() = sourceResults.filter { !it.isSuccess }
}
