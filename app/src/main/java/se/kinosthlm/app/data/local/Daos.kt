package se.kinosthlm.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.NotificationLog
import se.kinosthlm.app.data.model.Screening
import se.kinosthlm.app.data.model.ScreeningTitleCache
import se.kinosthlm.app.data.model.TitleCandidate
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.model.WatchlistSource

@Dao
interface WatchlistDao {
  @Query("SELECT * FROM watchlist_items ORDER BY addedAt DESC")
  fun observeAll(): Flow<List<WatchlistItem>>

  @Query("SELECT * FROM watchlist_items") suspend fun getAll(): List<WatchlistItem>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(items: List<WatchlistItem>)

  @Query("DELETE FROM watchlist_items WHERE id = :id") suspend fun deleteById(id: String)

  @Query("SELECT * FROM watchlist_sources") fun observeSources(): Flow<List<WatchlistSource>>

  @Query("SELECT * FROM watchlist_sources WHERE itemId = :itemId")
  suspend fun sourcesFor(itemId: String): List<WatchlistSource>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertSources(rows: List<WatchlistSource>)

  @Query("DELETE FROM watchlist_sources WHERE sourceId = :sourceId")
  suspend fun deleteSourceRows(sourceId: String)

  @Query("DELETE FROM watchlist_sources WHERE itemId = :itemId")
  suspend fun deleteSourceRowsFor(itemId: String)

  @Query("DELETE FROM watchlist_sources WHERE itemId = :itemId AND sourceId = :sourceId")
  suspend fun deleteSourceRow(itemId: String, sourceId: String)

  /** A film no source lists any more is off everybody's watchlist, so it goes for good. */
  @Query("DELETE FROM watchlist_items WHERE id NOT IN (SELECT itemId FROM watchlist_sources)")
  suspend fun deleteOrphans()

  /**
   * Make [items] the complete contents of [sourceId].
   *
   * Anything that source used to contribute and no longer does loses its claim; a film left
   * with no claims at all is deleted. Films other sources still list survive untouched, which is
   * the whole point — removing a title from IMDb should not take it out of your Trakt list.
   *
   * Existing rows are updated in place rather than replaced, so resolved IMDb ids, the
   * film/series verdict and a manual suppression all survive a re-sync.
   */
  @Transaction
  suspend fun replaceSource(sourceId: String, items: List<WatchlistItem>) {
    deleteSourceRows(sourceId)
    val existing = getAll().associateBy { it.id }
    val merged =
      items.map { incoming ->
        val current = existing[incoming.id] ?: return@map incoming
        current.copy(
          title = incoming.title,
          year = incoming.year ?: current.year,
          imdbId = incoming.imdbId ?: current.imdbId,
          tmdbId = incoming.tmdbId ?: current.tmdbId,
          traktId = incoming.traktId ?: current.traktId,
          posterUrl = incoming.posterUrl ?: current.posterUrl,
        )
      }
    insertAll(merged)
    insertSources(items.map { WatchlistSource(itemId = it.id, sourceId = sourceId) })
    deleteOrphans()
  }

  /**
   * Re-key an entry once it is identified.
   *
   * A Google TV title starts life keyed on its name; resolution gives it an IMDb id, which is
   * the identity Trakt and IMDb imports use. Moving it across means the same film from two lists
   * becomes one row rather than two — but the id is the primary key, so it needs a real move:
   * carry the provenance over, merge with any existing row, and drop the old one.
   */
  @Transaction
  suspend fun reIdentify(oldId: String, updated: WatchlistItem) {
    if (oldId == updated.id) {
      insertAll(listOf(updated))
      return
    }
    val existing = getAll().firstOrNull { it.id == updated.id }
    // An entry already under the new id wins on user-set fields; suppression must not be undone
    // just because another list also carries the film.
    val merged = existing?.copy(
      title = updated.title,
      year = updated.year ?: existing.year,
      imdbId = updated.imdbId ?: existing.imdbId,
      tmdbId = updated.tmdbId ?: existing.tmdbId,
      posterUrl = updated.posterUrl ?: existing.posterUrl,
      titleType = updated.titleType,
      needsReview = updated.needsReview,
    ) ?: updated

    insertAll(listOf(merged))
    insertSources(sourcesFor(oldId).map { WatchlistSource(updated.id, it.sourceId, it.addedAt) })
    deleteSourceRowsFor(oldId)
    deleteById(oldId)
  }

  /**
   * Add a film by hand. Manual provenance is only ever cleared by deleting it by hand, so it
   * survives every sync.
   */
  @Transaction
  suspend fun addManual(item: WatchlistItem) {
    val existing = getAll().firstOrNull { it.id == item.id }
    // Re-adding something previously deleted should bring it back, not stay hidden.
    insertAll(listOf(existing?.copy(suppressed = false) ?: item))
    insertSources(listOf(WatchlistSource(item.id, WatchlistItem.SOURCE_MANUAL)))
  }

  /**
   * Remove a film the user no longer wants to see here.
   *
   * If a source still lists it, the row is kept and suppressed — otherwise the next sync would
   * simply put it back. Once every source drops it, the row goes for good.
   */
  @Transaction
  suspend fun removeByUser(itemId: String) {
    deleteSourceRowsFor(itemId)
    val item = getAll().firstOrNull { it.id == itemId } ?: return
    insertAll(listOf(item.copy(suppressed = true)))
  }

  @Query("DELETE FROM watchlist_items") suspend fun clear()

  /**
   * Protect or unprotect an entry from automatic removal.
   *
   * Pinning just adds a [WatchlistItem.SOURCE_PINNED] provenance row — the same mechanism real
   * sources use — so a pinned film survives even after every list that actually contributed it
   * drops it. Unpinning removes only that row and re-runs orphan cleanup, so a film with no real
   * source left is deleted the moment its pin is lifted rather than lingering until the next sync.
   */
  @Transaction
  suspend fun setPinned(itemId: String, pinned: Boolean) {
    if (pinned) {
      insertSources(listOf(WatchlistSource(itemId, WatchlistItem.SOURCE_PINNED)))
    } else {
      deleteSourceRow(itemId, WatchlistItem.SOURCE_PINNED)
      deleteOrphans()
    }
  }

  @Query("UPDATE watchlist_items SET notificationsMuted = :muted WHERE id = :itemId")
  suspend fun setMuted(itemId: String, muted: Boolean)

  @Query("UPDATE watchlist_items SET requiredVenueTag = :tag WHERE id = :itemId")
  suspend fun setRequiredVenueTag(itemId: String, tag: String?)

  /** Entries still awaiting the user's choice between several same-named films. */
  @Query("SELECT * FROM watchlist_items WHERE needsReview = 1 ORDER BY title ASC")
  fun observeNeedingReview(): Flow<List<WatchlistItem>>

  @Query("SELECT COUNT(*) FROM watchlist_items WHERE titleType = 'series'")
  fun observeSeriesCount(): Flow<Int>
}

@Dao
interface TitleCandidateDao {
  @Query("SELECT * FROM title_candidates ORDER BY year DESC")
  fun observeAll(): Flow<List<TitleCandidate>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(candidates: List<TitleCandidate>)

  @Query("DELETE FROM title_candidates WHERE watchlistItemId = :itemId")
  suspend fun deleteFor(itemId: String)

  /** Drop candidates whose entry is gone, so a re-import does not leave orphans behind. */
  @Query(
    "DELETE FROM title_candidates WHERE watchlistItemId NOT IN (SELECT id FROM watchlist_items)"
  )
  suspend fun deleteOrphans()

  @Query("DELETE FROM title_candidates") suspend fun clear()
}

@Dao
interface ScreeningDao {
  @Query("SELECT * FROM screenings WHERE screeningTime >= :now ORDER BY screeningTime ASC")
  fun observeUpcoming(now: Long = System.currentTimeMillis()): Flow<List<Screening>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(screenings: List<Screening>)

  @Query("DELETE FROM screenings WHERE screeningTime < :cutoff")
  suspend fun deleteExpired(cutoff: Long = System.currentTimeMillis())

  /**
   * Drop screenings this sync did not see again — a cancelled or rescheduled showing must not
   * linger in the list.
   */
  @Query("DELETE FROM screenings WHERE cinemaId IN (:cinemaIds) AND foundAt < :before")
  suspend fun deleteStale(cinemaIds: List<String>, before: Long)

  @Query("DELETE FROM screenings") suspend fun clear()
}

@Dao
interface CinemaDao {
  @Query("SELECT * FROM cinemas ORDER BY name ASC") fun observeAll(): Flow<List<Cinema>>

  @Query("SELECT * FROM cinemas WHERE isEnabled = 1") suspend fun getEnabled(): List<Cinema>

  @Query("SELECT COUNT(*) FROM cinemas") suspend fun count(): Int

  /**
   * IGNORE, not REPLACE: re-seeding on launch must add venues shipped in a new app version
   * without resetting the enable/disable choices the user already made.
   */
  @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(cinemas: List<Cinema>)

  @Query("UPDATE cinemas SET isEnabled = :isEnabled WHERE id = :id")
  suspend fun setEnabled(id: String, isEnabled: Boolean)

  @Query(
    "UPDATE cinemas SET lastPolledAt = :timestamp, upcomingScreeningsCount = :count WHERE id = :id"
  )
  suspend fun updateStats(id: String, timestamp: Long, count: Int)
}

@Dao
interface ScreeningTitleCacheDao {
  @Query("SELECT * FROM screening_title_cache WHERE titleKey IN (:keys)")
  suspend fun get(keys: List<String>): List<ScreeningTitleCache>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(entries: List<ScreeningTitleCache>)

  /** Drop stale answers so a title TMDB could not place is eventually asked about again. */
  @Query("DELETE FROM screening_title_cache WHERE resolvedAt < :cutoff")
  suspend fun deleteResolvedBefore(cutoff: Long)

  /** Same, but only for the misses — a successful match is worth keeping far longer. */
  @Query("DELETE FROM screening_title_cache WHERE tmdbId IS NULL AND resolvedAt < :cutoff")
  suspend fun deleteStaleMisses(cutoff: Long)

  @Query("DELETE FROM screening_title_cache") suspend fun clear()
}

@Dao
interface NotificationDao {
  @Query("SELECT screeningId FROM notification_logs") suspend fun notifiedIds(): List<String>

  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(log: NotificationLog)

  @Query("SELECT * FROM notification_logs ORDER BY notifiedAt DESC")
  fun observeAll(): Flow<List<NotificationLog>>

  /** Keep the log from growing without bound; anything this old can never be re-notified. */
  @Query("DELETE FROM notification_logs WHERE notifiedAt < :cutoff")
  suspend fun deleteOlderThan(cutoff: Long)

  @Query("DELETE FROM notification_logs") suspend fun clear()
}
