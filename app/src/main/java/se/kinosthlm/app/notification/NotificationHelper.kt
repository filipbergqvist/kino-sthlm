package se.kinosthlm.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import se.kinosthlm.app.MainActivity
import se.kinosthlm.app.R
import se.kinosthlm.app.data.model.Screening
import se.kinosthlm.app.data.source.SwedishDates

/**
 * Screening alerts.
 *
 * One notification per film rather than per showing: a film opening at four cinemas is one piece
 * of news, not four. They share a group so several films arriving in the same sync collapse into
 * a summary instead of burying everything else in the shade.
 */
class NotificationHelper(private val context: Context) {

  init {
    val channel =
      NotificationChannel(CHANNEL_ID, "Screening alerts", NotificationManager.IMPORTANCE_DEFAULT)
        .apply {
          description = "When a film you are tracking is scheduled at a Stockholm cinema"
        }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  /** Announce [screenings], grouped by film. */
  fun notifyNewScreenings(screenings: List<Screening>) {
    if (screenings.isEmpty()) return
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return

    val byFilm = screenings.groupBy { it.watchlistMovieId }
    for ((movieId, forFilm) in byFilm) {
      val soonestFirst = forFilm.sortedBy { it.screeningTime }
      val first = soonestFirst.first()

      val lines = soonestFirst.take(MAX_LINES).map(::describe)
      val extra = soonestFirst.size - lines.size

      val style = NotificationCompat.InboxStyle()
      style.setBigContentTitle("${first.movieTitle} is showing")
      for (line in lines) style.addLine(line)
      if (extra > 0) style.addLine("+ $extra more showing(s)")

      val summary =
        if (soonestFirst.size == 1) describe(first)
        else "${soonestFirst.size} showings, first ${describe(first)}"

      val notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
          .setSmallIcon(R.drawable.ic_notification)
          .setContentTitle(first.movieTitle)
          .setContentText(summary)
          .setStyle(style)
          .setCategory(NotificationCompat.CATEGORY_EVENT)
          .setGroup(GROUP_KEY)
          .setAutoCancel(true)
          .setContentIntent(openApp(movieId))
          .addAction(0, "Buy tickets", openBooking(first))
          .build()

      runCatching { manager.notify(movieId.hashCode(), notification) }
    }

    if (byFilm.size > 1) {
      val summary =
        NotificationCompat.Builder(context, CHANNEL_ID)
          .setSmallIcon(R.drawable.ic_notification)
          .setContentTitle("${byFilm.size} films you are tracking are showing")
          .setGroup(GROUP_KEY)
          .setGroupSummary(true)
          .setAutoCancel(true)
          .setContentIntent(openApp(null))
          .build()
      runCatching { manager.notify(GROUP_SUMMARY_ID, summary) }
    }
  }

  /**
   * A line the user can act on: when, where, and only the details we actually have. The previous
   * version padded these with invented prices and format tags.
   */
  private fun describe(screening: Screening): String =
    buildString {
      append(formatTime(screening.screeningTime))
      append(" · ")
      append(screening.cinemaName)
      screening.auditorium?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
      screening.formatTag?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
      screening.priceSek?.let { append(" · from ").append(it).append(" kr") }
    }

  private fun openApp(movieId: String?): PendingIntent {
    val intent =
      Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        // Land on the schedule, focused on the film that was announced.
        putExtra(MainActivity.EXTRA_FOCUS_MOVIE_ID, movieId)
      }
    return PendingIntent.getActivity(
      context,
      movieId?.hashCode() ?: 0,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun openBooking(screening: Screening): PendingIntent {
    val intent =
      Intent(Intent.ACTION_VIEW, Uri.parse(screening.bookingUrl)).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
      }
    return PendingIntent.getActivity(
      context,
      screening.id.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  fun sendTestNotification() {
    val manager = NotificationManagerCompat.from(context)
    val notification =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("KinoSthlm notifications work")
        .setContentText("You will get an alert like this when a tracked film is scheduled.")
        .setAutoCancel(true)
        .setContentIntent(openApp(null))
        .build()
    runCatching { manager.notify(TEST_ID, notification) }
  }

  companion object {
    const val CHANNEL_ID = "screening_alerts"
    private const val GROUP_KEY = "se.kinosthlm.app.SCREENINGS"
    private const val GROUP_SUMMARY_ID = 1
    private const val TEST_ID = 2
    private const val MAX_LINES = 5

    private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)

    fun formatTime(epochMillis: Long): String =
      DAY_TIME.format(
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), SwedishDates.STOCKHOLM)
      )
  }
}
