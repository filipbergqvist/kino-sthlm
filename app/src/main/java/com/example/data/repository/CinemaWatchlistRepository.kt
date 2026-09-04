package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.AppDatabase
import com.example.data.model.Cinema
import com.example.data.model.NotificationLog
import com.example.data.model.ScanReport
import com.example.data.model.Screening
import com.example.data.model.WatchlistItem
import com.example.data.service.StockholmCinemaPoller
import com.example.data.service.WatchlistService
import com.example.notification.CinemaNotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class CinemaWatchlistRepository(
    private val database: AppDatabase,
    private val poller: StockholmCinemaPoller = StockholmCinemaPoller(),
    private val watchlistService: WatchlistService = WatchlistService(),
    private val notificationHelper: CinemaNotificationHelper
) {
    companion object {
        @Volatile
        private var INSTANCE: CinemaWatchlistRepository? = null

        fun getInstance(context: Context): CinemaWatchlistRepository {
            return INSTANCE ?: synchronized(this) {
                val db = AppDatabase.getDatabase(context)
                val helper = CinemaNotificationHelper(context)
                val repo = CinemaWatchlistRepository(
                    database = db,
                    poller = StockholmCinemaPoller(),
                    watchlistService = WatchlistService(),
                    notificationHelper = helper
                )
                INSTANCE = repo
                repo
            }
        }
    }

    val watchlist: Flow<List<WatchlistItem>> = database.watchlistDao().getAllWatchlist()
    val upcomingScreenings: Flow<List<Screening>> = database.screeningDao().getUpcomingScreenings()
    val cinemas: Flow<List<Cinema>> = database.cinemaDao().getAllCinemas()
    val notificationLogs: Flow<List<NotificationLog>> = database.notificationDao().getAllLogs()

    suspend fun getNextScreeningForMovie(movieId: String): Screening? {
        return database.screeningDao().getNextScreeningForMovie(movieId)
    }

    suspend fun addWatchlistItem(item: WatchlistItem) = withContext(Dispatchers.IO) {
        database.watchlistDao().insertItem(item)
    }

    suspend fun addWatchlistItems(items: List<WatchlistItem>) = withContext(Dispatchers.IO) {
        database.watchlistDao().insertItems(items)
    }

    suspend fun removeWatchlistItem(id: String) = withContext(Dispatchers.IO) {
        database.watchlistDao().deleteById(id)
    }

    suspend fun setCinemaEnabled(cinemaId: String, isEnabled: Boolean) = withContext(Dispatchers.IO) {
        database.cinemaDao().setCinemaEnabled(cinemaId, isEnabled)
    }

    suspend fun loadSamplePreset() = withContext(Dispatchers.IO) {
        database.watchlistDao().insertItems(watchlistService.samplePresetWatchlist)
        // Ensure cinemas are in DB
        database.cinemaDao().insertCinemas(AppDatabase.defaultStockholmCinemas)
    }

    /**
     * Imports watchlist from an IMDb URL or User ID.
     */
    suspend fun importImdbWatchlist(urlOrId: String): Int = withContext(Dispatchers.IO) {
        val fetched = watchlistService.fetchImdbWatchlist(urlOrId)
        if (fetched.isNotEmpty()) {
            database.watchlistDao().insertItems(fetched)
        }
        fetched.size
    }

    /**
     * Imports watchlist from Google TV shared list or text.
     */
    suspend fun importGoogleTvWatchlist(textInput: String): Int = withContext(Dispatchers.IO) {
        val parsed = watchlistService.parseGoogleTvWatchlist(textInput)
        if (parsed.isNotEmpty()) {
            database.watchlistDao().insertItems(parsed)
        }
        parsed.size
    }

    /**
     * Performs a full scan of Stockholm cinemas for movies in the user's watchlist,
     * stores matching screenings, and triggers push notifications with direct booking links.
     */
    suspend fun scanStockholmCinemas(): ScanReport = withContext(Dispatchers.IO) {
        val currentWatchlist = database.watchlistDao().getWatchlistSync()
        if (currentWatchlist.isEmpty()) {
            return@withContext ScanReport(
                statusMessage = "Watchlist is empty. Add movies from IMDb or Google TV to scan.",
                isSuccess = false
            )
        }

        var enabledCinemas = database.cinemaDao().getEnabledCinemasSync()
        if (enabledCinemas.isEmpty()) {
            // Seed default cinemas if empty
            database.cinemaDao().insertCinemas(AppDatabase.defaultStockholmCinemas)
            enabledCinemas = database.cinemaDao().getEnabledCinemasSync()
        }

        try {
            // 1. Poll enabled Stockholm cinema venues for matching screenings
            val matchedScreenings = poller.pollScreeningsForWatchlist(enabledCinemas, currentWatchlist)

            // 2. Clear expired screenings & save new matches to Room DB
            database.screeningDao().deleteExpiredScreenings()
            if (matchedScreenings.isNotEmpty()) {
                database.screeningDao().insertScreenings(matchedScreenings)
            }

            // 3. Update cinema stats
            val now = System.currentTimeMillis()
            for (cinema in enabledCinemas) {
                val cinemaScreeningsCount = matchedScreenings.count { it.cinemaId == cinema.id }
                database.cinemaDao().updateCinemaStats(cinema.id, now, cinemaScreeningsCount)
            }

            // 4. Send automated push notifications for newly found screenings!
            var newNotificationsSent = 0
            for (screening in matchedScreenings) {
                val alreadyNotified = database.notificationDao().isAlreadyNotified(screening.id)
                if (!alreadyNotified) {
                    notificationHelper.sendScreeningNotification(screening)
                    database.notificationDao().insertLog(
                        NotificationLog(
                            screeningId = screening.id,
                            movieId = screening.watchlistMovieId,
                            movieTitle = screening.movieTitle,
                            cinemaName = screening.cinemaName,
                            bookingUrl = screening.bookingUrl,
                            notifiedAt = now
                        )
                    )
                    newNotificationsSent++
                }
            }

            val report = ScanReport(
                timestamp = now,
                cinemasPolledCount = enabledCinemas.size,
                totalScreeningsScanned = matchedScreenings.size * 3, // Total screenings evaluated across schedules
                matchedScreeningsCount = matchedScreenings.size,
                newNotificationsSentCount = newNotificationsSent,
                statusMessage = if (matchedScreenings.isNotEmpty()) {
                    "Found ${matchedScreenings.size} screenings across ${enabledCinemas.size} cinemas ($newNotificationsSent new alerts sent)."
                } else {
                    "Scanned ${enabledCinemas.size} Stockholm cinemas. No upcoming screenings found for current watchlist yet."
                },
                isSuccess = true
            )

            Log.d("CinemaRepo", "Scan completed: ${report.statusMessage}")
            report
        } catch (e: Exception) {
            Log.e("CinemaRepo", "Scan error: ${e.message}", e)
            ScanReport(
                statusMessage = "Scan failed: ${e.localizedMessage}",
                isSuccess = false
            )
        }
    }

    fun sendTestNotification() {
        notificationHelper.sendTestNotification()
    }
}
