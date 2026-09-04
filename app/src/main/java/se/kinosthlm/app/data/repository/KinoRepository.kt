package se.kinosthlm.app.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import se.kinosthlm.app.data.local.AppDatabase
import se.kinosthlm.app.data.match.MatchCandidate
import se.kinosthlm.app.data.match.TitleMatcher
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.NotificationLog
import se.kinosthlm.app.data.model.Screening
import se.kinosthlm.app.data.model.SourceResult
import se.kinosthlm.app.data.model.SyncReport
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.prefs.SettingsStore
import se.kinosthlm.app.data.source.CinemaSourceRegistry
import se.kinosthlm.app.data.source.RawScreening
import se.kinosthlm.app.data.watchlist.CsvWatchlistImporter
import se.kinosthlm.app.data.watchlist.ImdbPublicListProvider
import se.kinosthlm.app.data.watchlist.TraktProvider
import se.kinosthlm.app.notification.NotificationHelper

/**
 * Single entry point for the app's data.
 *
 * The important method is [sync]: refresh the watchlists, poll every enabled cinema, match the
 * two, and notify about genuinely new showings. Manual refresh and the scheduled worker both
 * call it, so the two paths cannot drift apart.
 */
class KinoRepository
private constructor(
  private val context: Context,
  private val database: AppDatabase,
  private val settings: SettingsStore,
  private val notifications: NotificationHelper,
) {

  val trakt: TraktProvider = TraktProvider(context)

  val watchlist: Flow<List<WatchlistItem>> = database.watchlistDao().observeAll()
  val upcomingScreenings: Flow<List<Screening>> = database.screeningDao().observeUpcoming()
  val cinemas: Flow<List<Cinema>> = database.cinemaDao().observeAll()
  val notificationLogs: Flow<List<NotificationLog>> = database.notificationDao().observeAll()

  // --- Cinemas ---

  /**
   * Insert any venue this app version ships that the database has not seen. IGNORE conflicts, so
   * a user who switched off Filmstaden Täby keeps it switched off across updates.
   */
  suspend fun seedCinemas() =
    withContext(Dispatchers.IO) {
      database.cinemaDao().insertAll(AppDatabase.defaultCinemas)
    }

  suspend fun setCinemaEnabled(cinemaId: String, isEnabled: Boolean) =
    withContext(Dispatchers.IO) { database.cinemaDao().setEnabled(cinemaId, isEnabled) }

  // --- Watchlist ---

  suspend fun addManualItem(title: String, year: Int?) =
    withContext(Dispatchers.IO) {
      val item =
        WatchlistItem(
          id = WatchlistItem.idFor(null, title, year),
          title = title.trim(),
          year = year,
          source = WatchlistItem.SOURCE_MANUAL,
        )
      database.watchlistDao().insertAll(listOf(item))
    }

  suspend fun removeItem(id: String) =
    withContext(Dispatchers.IO) { database.watchlistDao().deleteById(id) }

  /** Import an IMDb or Google TV CSV the user picked with the system file picker. */
  suspend fun importCsv(uri: Uri, sourceId: String): Int =
    withContext(Dispatchers.IO) {
      val items =
        context.contentResolver.openInputStream(uri)?.use {
          CsvWatchlistImporter.parse(it, sourceId)
        } ?: error("Could not open the selected file")

      if (items.isEmpty()) {
        error("No films found in that file. Is it the watchlist export?")
      }
      // Replace this provider's previous import rather than accumulating stale titles.
      database.watchlistDao().deleteBySource(sourceId)
      database.watchlistDao().insertAll(items)
      items.size
    }

  suspend fun importImdbPublicList(listUrl: String): Int =
    withContext(Dispatchers.IO) {
      val items = ImdbPublicListProvider(listUrl).sync()
      database.watchlistDao().deleteBySource(WatchlistItem.SOURCE_IMDB)
      database.watchlistDao().insertAll(items)
      settings.setImdbListUrl(listUrl)
      items.size
    }

  // --- The sync ---

  /**
   * Refresh everything and return what happened.
   *
   * Never throws: a failure in one cinema or provider is reported in [SyncReport.sourceResults]
   * so the UI can name it, while the rest of the sync still completes.
   */
  suspend fun sync(): SyncReport =
    withContext(Dispatchers.IO) {
      val startedAt = System.currentTimeMillis()
      seedCinemas()

      // 1. Refresh whatever can refresh itself. Currently that means Trakt.
      var imported = 0
      val results = mutableListOf<SourceResult>()
      if (trakt.isConnected()) {
        runCatching { trakt.sync() }
          .onSuccess { items ->
            database.watchlistDao().deleteBySource(WatchlistItem.SOURCE_TRAKT)
            database.watchlistDao().insertAll(items)
            imported = items.size
            results += SourceResult(trakt.id, trakt.label, items.size)
          }
          .onFailure { error ->
            results += SourceResult(trakt.id, trakt.label, error = error.readableMessage())
          }
      }

      val currentWatchlist = database.watchlistDao().getAll()
      if (currentWatchlist.isEmpty()) {
        return@withContext SyncReport(
          timestamp = startedAt,
          sourceResults = results,
          statusMessage = "Your watchlist is empty. Connect Trakt or import a CSV to get started.",
          isSuccess = false,
        )
      }

      // 2. Poll each enabled cinema, grouped so one request serves a whole chain.
      val enabled = database.cinemaDao().getEnabled()
      if (enabled.isEmpty()) {
        return@withContext SyncReport(
          timestamp = startedAt,
          watchlistSize = currentWatchlist.size,
          watchlistImported = imported,
          sourceResults = results,
          statusMessage = "No cinemas selected. Enable at least one on the Cinemas tab.",
          isSuccess = false,
        )
      }

      val from = Instant.now()
      val to = from.plus(settings.currentHorizonDays(), ChronoUnit.DAYS)

      val raw = mutableListOf<RawScreening>()
      for ((sourceId, venues) in enabled.groupBy { it.sourceId }) {
        val source = CinemaSourceRegistry[sourceId]
        if (source == null) {
          results += SourceResult(sourceId, sourceId, error = "No adapter registered")
          continue
        }
        runCatching { source.fetchScreenings(venues, currentWatchlist, from, to) }
          .onSuccess { found ->
            raw += found
            results += SourceResult(source.id, source.label, found.size)
          }
          .onFailure { error ->
            // Deliberately no fallback data. A broken source reads as broken, never as "nothing
            // is playing", and never as invented showings.
            Log.w(TAG, "Source ${source.id} failed", error)
            results += SourceResult(source.id, source.label, error = error.readableMessage())
          }
      }

      // 3. Match against the watchlist.
      val matched =
        raw.mapNotNull { screening ->
          val item =
            TitleMatcher.findMatch(
              MatchCandidate(
                title = screening.title,
                originalTitle = screening.originalTitle,
                year = screening.year,
                imdbId = screening.imdbId,
              ),
              currentWatchlist,
            ) ?: return@mapNotNull null

          Screening(
            id = "${screening.cinemaId}|${item.id}|${screening.startTime.toEpochMilli()}",
            watchlistMovieId = item.id,
            movieTitle = item.title,
            cinemaId = screening.cinemaId,
            cinemaName = screening.cinemaName,
            auditorium = screening.auditorium,
            screeningTime = screening.startTime.toEpochMilli(),
            formatTag = screening.formatTags.joinToString(" • ").ifBlank { null },
            bookingUrl = screening.bookingUrl,
            priceSek = screening.priceSek,
            foundAt = startedAt,
          )
        }
          .distinctBy { it.id }

      // 4. Persist. Only prune venues we actually reached, so a failed source does not wipe the
      // screenings we already knew about there.
      database.screeningDao().deleteExpired()
      val reachedSourceIds = results.filter { it.isSuccess }.map { it.sourceId }.toSet()
      val reachedCinemaIds = enabled.filter { it.sourceId in reachedSourceIds }.map { it.id }
      if (reachedCinemaIds.isNotEmpty()) {
        database.screeningDao().deleteStale(reachedCinemaIds, startedAt)
      }
      if (matched.isNotEmpty()) database.screeningDao().insertAll(matched)

      for (cinema in enabled) {
        database.cinemaDao()
          .updateStats(cinema.id, startedAt, matched.count { it.cinemaId == cinema.id })
      }

      // 5. Notify about showings the user has not been told about yet.
      val alreadyNotified = database.notificationDao().notifiedIds().toSet()
      val fresh = matched.filter { it.id !in alreadyNotified }
      var sent = 0
      if (fresh.isNotEmpty() && settings.notificationsEnabled.first()) {
        notifications.notifyNewScreenings(fresh)
        sent = fresh.size
      }
      // Log even when notifications are off, so switching them on does not replay history.
      for (screening in fresh) {
        database.notificationDao()
          .insert(
            NotificationLog(
              screeningId = screening.id,
              movieId = screening.watchlistMovieId,
              movieTitle = screening.movieTitle,
              cinemaName = screening.cinemaName,
              bookingUrl = screening.bookingUrl,
              notifiedAt = startedAt,
            )
          )
      }
      database.notificationDao().deleteOlderThan(startedAt - LOG_RETENTION_MILLIS)

      val failures = results.filter { !it.isSuccess }
      val summary =
        when {
          matched.isEmpty() && failures.isEmpty() ->
            "No screenings yet for your ${currentWatchlist.size} films."
          matched.isEmpty() ->
            "No screenings found. ${failures.size} source(s) failed."
          failures.isEmpty() ->
            "${matched.size} screening(s) across ${enabled.size} cinemas, $sent new."
          else ->
            "${matched.size} screening(s), $sent new. ${failures.size} source(s) failed."
        }
      settings.recordSync(startedAt, summary)

      SyncReport(
        timestamp = startedAt,
        watchlistSize = currentWatchlist.size,
        watchlistImported = imported,
        cinemasPolled = enabled.size,
        screeningsScanned = raw.size,
        matchedScreenings = matched.size,
        newNotifications = sent,
        sourceResults = results,
        statusMessage = summary,
        isSuccess = failures.isEmpty(),
      )
    }

  fun sendTestNotification() = notifications.sendTestNotification()

  /** Exception messages reach the UI, so make them readable rather than class names. */
  private fun Throwable.readableMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "Unknown error"

  companion object {
    private const val TAG = "KinoRepository"

    /** Ninety days: long past any screening we would re-announce. */
    private const val LOG_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000

    @Volatile private var INSTANCE: KinoRepository? = null

    fun getInstance(context: Context): KinoRepository =
      INSTANCE
        ?: synchronized(this) {
          INSTANCE
            ?: KinoRepository(
                context = context.applicationContext,
                database = AppDatabase.getDatabase(context),
                settings = SettingsStore(context.applicationContext),
                notifications = NotificationHelper(context.applicationContext),
              )
              .also { INSTANCE = it }
        }
  }
}
