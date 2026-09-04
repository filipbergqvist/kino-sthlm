package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.Cinema
import com.example.data.model.NotificationLog
import com.example.data.model.Screening
import com.example.data.model.WatchlistItem
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist_items ORDER BY addedAt DESC")
    fun getAllWatchlist(): Flow<List<WatchlistItem>>

    @Query("SELECT * FROM watchlist_items")
    suspend fun getWatchlistSync(): List<WatchlistItem>

    @Query("SELECT * FROM watchlist_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WatchlistItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: WatchlistItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<WatchlistItem>)

    @Delete
    suspend fun deleteItem(item: WatchlistItem)

    @Query("DELETE FROM watchlist_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM watchlist_items")
    suspend fun clearWatchlist()
}

@Dao
interface ScreeningDao {
    @Query("SELECT * FROM screenings WHERE screeningTime >= :now ORDER BY screeningTime ASC")
    fun getUpcomingScreenings(now: Long = System.currentTimeMillis()): Flow<List<Screening>>

    @Query("SELECT * FROM screenings WHERE watchlistMovieId = :movieId AND screeningTime >= :now ORDER BY screeningTime ASC")
    fun getScreeningsForMovie(movieId: String, now: Long = System.currentTimeMillis()): Flow<List<Screening>>

    @Query("SELECT * FROM screenings WHERE watchlistMovieId = :movieId AND screeningTime >= :now ORDER BY screeningTime ASC LIMIT 1")
    suspend fun getNextScreeningForMovie(movieId: String, now: Long = System.currentTimeMillis()): Screening?

    @Query("SELECT * FROM screenings")
    suspend fun getAllScreeningsSync(): List<Screening>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScreenings(screenings: List<Screening>)

    @Query("DELETE FROM screenings WHERE cinemaId = :cinemaId")
    suspend fun clearScreeningsForCinema(cinemaId: String)

    @Query("DELETE FROM screenings WHERE screeningTime < :cutoff")
    suspend fun deleteExpiredScreenings(cutoff: Long = System.currentTimeMillis())

    @Query("DELETE FROM screenings")
    suspend fun clearAllScreenings()
}

@Dao
interface CinemaDao {
    @Query("SELECT * FROM cinemas ORDER BY name ASC")
    fun getAllCinemas(): Flow<List<Cinema>>

    @Query("SELECT * FROM cinemas WHERE isEnabled = 1")
    suspend fun getEnabledCinemasSync(): List<Cinema>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCinemas(cinemas: List<Cinema>)

    @Query("UPDATE cinemas SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setCinemaEnabled(id: String, isEnabled: Boolean)

    @Query("UPDATE cinemas SET lastPolledAt = :timestamp, activeScreeningsCount = :screeningsCount WHERE id = :id")
    suspend fun updateCinemaStats(id: String, timestamp: Long, screeningsCount: Int)
}

@Dao
interface NotificationDao {
    @Query("SELECT COUNT(*) > 0 FROM notification_logs WHERE screeningId = :screeningId")
    suspend fun isAlreadyNotified(screeningId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: NotificationLog)

    @Query("SELECT * FROM notification_logs ORDER BY notifiedAt DESC")
    fun getAllLogs(): Flow<List<NotificationLog>>

    @Query("DELETE FROM notification_logs")
    suspend fun clearLogs()
}
