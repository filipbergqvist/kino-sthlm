package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a film from the user's IMDb or Google TV watchlist.
 */
@Entity(tableName = "watchlist_items")
data class WatchlistItem(
    @PrimaryKey val id: String, // IMDb ID e.g. "tt15398776" or unique ID
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val director: String? = null,
    val source: String = "IMDb", // "IMDb", "Google TV", "Manual"
    val addedAt: Long = System.currentTimeMillis(),
    val imdbRating: Float? = null,
    val genres: String? = null,
    val overview: String? = null
)

/**
 * Stockholm cinema venue details.
 */
@Entity(tableName = "cinemas")
data class Cinema(
    @PrimaryKey val id: String,
    val name: String,
    val district: String, // e.g. "Vasastan", "Hornstull", "Östermalm", "Norrmalm"
    val address: String,
    val websiteUrl: String,
    val bookingUrlTemplate: String,
    val specialty: String,
    val isEnabled: Boolean = true,
    val lastPolledAt: Long = 0L,
    val activeScreeningsCount: Int = 0
)

/**
 * A scheduled movie screening found at a Stockholm cinema matching a watchlist item.
 */
@Entity(tableName = "screenings")
data class Screening(
    @PrimaryKey val id: String, // CinemaId + MovieId + timestamp
    val watchlistMovieId: String,
    val movieTitle: String,
    val cinemaId: String,
    val cinemaName: String,
    val auditorium: String? = null,
    val screeningTime: Long, // Epoch millis
    val formattedDateTime: String, // e.g. "Fri 5 Sep • 19:30"
    val formatTag: String? = null, // e.g. "70mm Bistro", "35mm", "Originalspråk", "Q&A"
    val bookingUrl: String,
    val priceSek: Int? = null,
    val isSoldOut: Boolean = false,
    val foundAt: Long = System.currentTimeMillis()
)

/**
 * Records dispatched push notifications to avoid duplicate spam.
 */
@Entity(tableName = "notification_logs")
data class NotificationLog(
    @PrimaryKey val screeningId: String,
    val movieId: String,
    val movieTitle: String,
    val cinemaName: String,
    val bookingUrl: String,
    val notifiedAt: Long = System.currentTimeMillis()
)

/**
 * Status and metrics of the last automated or manual scan.
 */
data class ScanReport(
    val timestamp: Long = System.currentTimeMillis(),
    val cinemasPolledCount: Int = 0,
    val totalScreeningsScanned: Int = 0,
    val matchedScreeningsCount: Int = 0,
    val newNotificationsSentCount: Int = 0,
    val statusMessage: String = "",
    val isSuccess: Boolean = true
)
