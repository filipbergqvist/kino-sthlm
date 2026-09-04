package se.kinosthlm.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.NotificationLog
import se.kinosthlm.app.data.model.Screening
import se.kinosthlm.app.data.model.WatchlistItem

@Dao
interface WatchlistDao {
  @Query("SELECT * FROM watchlist_items ORDER BY addedAt DESC")
  fun observeAll(): Flow<List<WatchlistItem>>

  @Query("SELECT * FROM watchlist_items") suspend fun getAll(): List<WatchlistItem>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(items: List<WatchlistItem>)

  @Query("DELETE FROM watchlist_items WHERE id = :id") suspend fun deleteById(id: String)

  /**
   * Replace everything from one provider, so titles the user removed upstream disappear here
   * too. Manual additions and other providers are untouched.
   */
  @Query("DELETE FROM watchlist_items WHERE source = :source")
  suspend fun deleteBySource(source: String)

  @Query("DELETE FROM watchlist_items") suspend fun clear()
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
