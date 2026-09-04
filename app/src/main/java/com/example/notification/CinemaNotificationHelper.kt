package com.example.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.data.model.Screening

class CinemaNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "stockholm_cinema_alerts"
        const val CHANNEL_NAME = "Stockholm Cinema Screenings"
        const val CHANNEL_DESCRIPTION = "Alerts when a movie from your IMDb or Google TV watchlist has a screening in Stockholm"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESCRIPTION
                enableLights(true)
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Dispatches push notification for a matched screening with a direct booking link.
     */
    fun sendScreeningNotification(screening: Screening) {
        val notificationManager = NotificationManagerCompat.from(context)

        // Booking Intent: Opens the cinema's official ticket booking webpage directly
        val bookingIntent = Intent(Intent.ACTION_VIEW, Uri.parse(screening.bookingUrl)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val bookingPendingIntent = PendingIntent.getActivity(
            context,
            screening.id.hashCode(),
            bookingIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // App Intent: Opens the app
        val appIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val appPendingIntent = PendingIntent.getActivity(
            context,
            0,
            appIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formatDesc = if (!screening.formatTag.isNullOrBlank()) " (${screening.formatTag})" else ""
        val auditoriumDesc = if (!screening.auditorium.isNullOrBlank()) " • ${screening.auditorium}" else ""
        val priceDesc = if (screening.priceSek != null) " • ${screening.priceSek} SEK" else ""

        val bigText = "🎟️ Watchlist Match!\n" +
                "${screening.movieTitle} has a screening at ${screening.cinemaName}$auditoriumDesc on ${screening.formattedDateTime}$formatDesc$priceDesc.\n" +
                "Tap 'Book Tickets' to reserve your seats!"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("🎬 Screening: ${screening.movieTitle}")
            .setContentText("${screening.cinemaName} • ${screening.formattedDateTime}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(appPendingIntent)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_view,
                "Book Tickets",
                bookingPendingIntent
            )
            .build()

        try {
            notificationManager.notify(screening.id.hashCode(), notification)
        } catch (e: SecurityException) {
            // Android 13+ permission not yet granted
            e.printStackTrace()
        }
    }

    /**
     * Sends a test notification to verify push notification setup.
     */
    fun sendTestNotification() {
        val testScreening = Screening(
            id = "test_screening_${System.currentTimeMillis()}",
            watchlistMovieId = "tt15398776",
            movieTitle = "The Substance",
            cinemaId = "bio_capitol",
            cinemaName = "Bio Capitol",
            auditorium = "Salong 1 (Bistro)",
            screeningTime = System.currentTimeMillis() + 86400000L,
            formattedDateTime = "Tomorrow • 19:30",
            formatTag = "Bistro Dinner & Wine",
            bookingUrl = "https://www.capitolbio.se/boka/the-substance",
            priceSek = 220,
            foundAt = System.currentTimeMillis()
        )
        sendScreeningNotification(testScreening)
    }
}
