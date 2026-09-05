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
import se.kinosthlm.app.data.match.ScreeningMatcher
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.NotificationLog
import se.kinosthlm.app.data.model.Screening
import se.kinosthlm.app.data.model.SourceResult
import se.kinosthlm.app.data.model.SyncReport
import se.kinosthlm.app.data.model.TitleCandidate
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.model.WatchlistSource
import se.kinosthlm.app.data.prefs.SettingsStore
import se.kinosthlm.app.data.source.CinemaSourceRegistry
import se.kinosthlm.app.data.source.RawScreening
import se.kinosthlm.app.data.watchlist.CsvWatchlistImporter
import se.kinosthlm.app.data.watchlist.ImdbPublicListProvider
import se.kinosthlm.app.data.watchlist.TitleLookup
import se.kinosthlm.app.data.watchlist.TitleResolver
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
  private val resolver = TitleResolver()
  private val lookup = TitleLookup()

  val watchlist: Flow<List<WatchlistItem>> = database.watchlistDao().observeAll()
  val upcomingScreenings: Flow<List<Screening>> = database.screeningDao().observeUpcoming()
  val cinemas: Flow<List<Cinema>> = database.cinemaDao().observeAll()
  val notificationLogs: Flow<List<NotificationLog>> = database.notificationDao().observeAll()

  /** Entries where several films share the title and the user has to choose. */
  val needingReview: Flow<List<WatchlistItem>> = database.watchlistDao().observeNeedingReview()
  val reviewCandidates: Flow<List<TitleCandidate>> = database.titleCandidateDao().observeAll()
  val seriesCount: Flow<Int> = database.watchlistDao().observeSeriesCount()

  /** Which lists each film came from, so the UI can show its provenance. */
  val watchlistSources: Flow<List<WatchlistSource>> = database.watchlistDao().observeSources()

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
      // The import *is* that source's list now: anything it used to contribute and no longer
      // does loses its claim, and a film nothing claims any more is deleted.
      database.watchlistDao().replaceSource(sourceId, items)
      database.titleCandidateDao().deleteOrphans()
      items.size
    }

  suspend fun importImdbPublicList(listUrl: String): Int =
    withContext(Dispatchers.IO) {
      val items = ImdbPublicListProvider(listUrl).sync()
      database.watchlistDao().replaceSource(WatchlistItem.SOURCE_IMDB, items)
      database.titleCandidateDao().deleteOrphans()
      settings.setImdbListUrl(listUrl)
      items.size
    }

  /**
   * Add a film by hand from an IMDb link.
   *
   * Taking the link rather than a typed title and year means the entry arrives already
   * identified — exact id, exact year — so it can never be the wrong film of two sharing a name,
   * and it needs no review pass. Manual provenance is only cleared by deleting it by hand, so it
   * survives every sync.
   *
   * Returns the film's title.
   */
  suspend fun addByImdbLink(input: String): String =
    withContext(Dispatchers.IO) {
      val imdbId =
        TitleLookup.extractImdbId(input)
          ?: error("That does not look like an IMDb link. Expected something containing tt…")

      val candidate =
        lookup.lookupByImdbId(imdbId)
          ?: error("IMDb has no title with the id $imdbId, or the lookup is unavailable.")

      if (!candidate.isFilm) {
        error("\"${candidate.title}\" is a TV series, which never plays in cinemas.")
      }

      database.watchlistDao()
        .addManual(
          WatchlistItem(
            id = WatchlistItem.idFor(candidate.tmdbId, candidate.imdbId, candidate.title, candidate.year),
            title = candidate.title,
            year = candidate.year,
            imdbId = candidate.imdbId,
            tmdbId = candidate.tmdbId,
            posterUrl = candidate.posterUrl,
            overview = candidate.overview,
            titleType = WatchlistItem.TYPE_MOVIE,
          )
        )
      candidate.title
    }

  /**
   * Remove a film from the watchlist here.
   *
   * If a connected list still contains it, the entry is suppressed rather than deleted —
   * otherwise the next sync would put it straight back. Remove it upstream too and it goes for
   * good on the following sync.
   */
  suspend fun removeItem(id: String) =
    withContext(Dispatchers.IO) {
      database.watchlistDao().removeByUser(id)
      database.titleCandidateDao().deleteFor(id)
    }

  /**
   * Protect a film against disappearing when its real sources later drop it, or lift that
   * protection. See [se.kinosthlm.app.data.local.WatchlistDao.setPinned].
   */
  suspend fun setPinned(itemId: String, pinned: Boolean) =
    withContext(Dispatchers.IO) { database.watchlistDao().setPinned(itemId, pinned) }

  // --- Identifying titles ---

  /**
   * Put IMDb ids, years and a film/series verdict on entries that arrived as bare titles.
   *
   * Google TV exports need this; Trakt and IMDb already carry ids and skip it. Safe to call
   * repeatedly — it only touches entries that are still unidentified.
   */
  suspend fun resolveTitles(
    limit: Int = RESOLVE_PER_RUN,
    onProgress: (Int, Int) -> Unit = { _, _ -> },
  ): TitleResolver.Outcome =
    withContext(Dispatchers.IO) {
      val before = database.watchlistDao().getAll()
      val outcome = resolver.resolve(before, limit, onProgress)
      applyResolutions(outcome.resolutions, before)
      outcome
    }

  /**
   * Give a TMDB id to entries that already have an IMDb id but not yet a TMDB one — IMDb CSV and
   * public-list imports, which never otherwise touch TMDB.
   */
  private suspend fun backfillTmdbIds(limit: Int = RESOLVE_PER_RUN) {
    val before = database.watchlistDao().getAll()
    val resolutions = resolver.backfillTmdbIds(before, limit)
    applyResolutions(resolutions, before)
  }

  /**
   * Fetch a poster and synopsis for entries that already have a TMDB id but skipped every path
   * that would have picked one up — Trakt imports, mainly. Capped low: this is cosmetic, not
   * something worth spending TMDB's rate limit on ahead of matching or identification.
   */
  private suspend fun backfillPosters(limit: Int = POSTER_BACKFILL_PER_RUN) {
    val before = database.watchlistDao().getAll()
    val resolutions = resolver.backfillPosters(before, limit)
    applyResolutions(resolutions, before)
  }

  /** Move each resolved entry onto its (possibly new) key and persist any review candidates. */
  private suspend fun applyResolutions(
    resolutions: List<TitleResolver.Resolution>,
    before: List<WatchlistItem>,
  ) {
    for (resolution in resolutions) {
      val original = before.first { it.id == resolution.item.id || it.id == resolution.oldId }
      // Identification can change an entry's key, so route it through the move.
      database.watchlistDao().reIdentify(original.id, resolution.item)
    }
    val candidates = resolutions.flatMap { it.candidates }
    if (candidates.isNotEmpty()) database.titleCandidateDao().insertAll(candidates)
    if (resolutions.isNotEmpty()) database.titleCandidateDao().deleteOrphans()
  }

  /**
   * Record the user's choice for an ambiguous title: adopt that film's id and year, clear the
   * flag, and discard the alternatives.
   */
  suspend fun resolveAmbiguity(itemId: String, candidate: TitleCandidate) =
    withContext(Dispatchers.IO) {
      val item = database.watchlistDao().getAll().firstOrNull { it.id == itemId }
        ?: return@withContext
      val imdbId = candidate.imdbId ?: lookupImdbId(candidate)
      val resolved =
        item.copy(
          id = WatchlistItem.idFor(candidate.tmdbId, imdbId, item.title, candidate.year ?: item.year),
          imdbId = imdbId,
          tmdbId = candidate.tmdbId,
          title = candidate.title,
          year = candidate.year ?: item.year,
          posterUrl = candidate.posterUrl ?: item.posterUrl,
          titleType = WatchlistItem.TYPE_MOVIE,
          needsReview = false,
        )
      database.watchlistDao().reIdentify(itemId, resolved)
      database.titleCandidateDao().deleteFor(itemId)
    }

  /** Candidates from search carry no IMDb id; fetch it once the user has settled on one. */
  private suspend fun lookupImdbId(candidate: TitleCandidate): String? =
    runCatching {
      lookup
        .attachImdbId(
          TitleLookup.Candidate(
            tmdbId = candidate.tmdbId,
            title = candidate.title,
            originalTitle = null,
            year = candidate.year,
            type = TitleLookup.TYPE_MOVIE,
            posterUrl = candidate.posterUrl,
          )
        )
        .imdbId
    }
      .getOrNull()

  /** The user says this entry is not a film; stop showing and matching it. */
  suspend fun markAsSeries(itemId: String) =
    withContext(Dispatchers.IO) {
      val item = database.watchlistDao().getAll().firstOrNull { it.id == itemId }
        ?: return@withContext
      database.watchlistDao()
        .insertAll(listOf(item.copy(titleType = WatchlistItem.TYPE_SERIES, needsReview = false)))
      database.titleCandidateDao().deleteFor(itemId)
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
            // Films dropped from Trakt lose their Trakt claim here, and vanish entirely unless
            // another list still has them.
            database.watchlistDao().replaceSource(WatchlistItem.SOURCE_TRAKT, items)
            imported = items.size
            results += SourceResult(trakt.id, trakt.label, items.size)
          }
          .onFailure { error ->
            results += SourceResult(trakt.id, trakt.label, error = error.readableMessage())
          }
      }

      // 2. Put ids and a film/series verdict on anything that arrived as a bare title. Capped
      // per run so a large first import spreads over a few syncs rather than one long stall.
      runCatching { resolveTitles() }
        .onFailure { Log.d(TAG, "Title resolution skipped: ${it.message}") }

      // 2b. Give IMDb-only entries (from CSV / public-list imports) a TMDB id too, so every
      // source ends up on the same standardized key rather than just the Google TV path.
      runCatching { backfillTmdbIds() }
        .onFailure { Log.d(TAG, "TMDB backfill skipped: ${it.message}") }

      // 2c. Fill in posters/synopses for entries that never went through a search — Trakt
      // imports, which get a TMDB id but not the details that come with looking one up.
      runCatching { backfillPosters() }
        .onFailure { Log.d(TAG, "Poster backfill skipped: ${it.message}") }

      // Series can never have a cinema screening, and an ambiguous title would match the wrong
      // film, so neither is worth asking a cinema about.
      val stored = database.watchlistDao().getAll()
      val currentWatchlist = stored.filter { it.isMatchable }
      if (stored.isEmpty()) {
        return@withContext SyncReport(
          timestamp = startedAt,
          sourceResults = results,
          statusMessage = "Your watchlist is empty. Connect Trakt or import a CSV to get started.",
          isSuccess = false,
        )
      }

      if (currentWatchlist.isEmpty()) {
        val series = stored.count { !it.isFilm }
        val review = stored.count { it.needsReview }
        return@withContext SyncReport(
          timestamp = startedAt,
          watchlistSize = stored.size,
          watchlistImported = imported,
          sourceResults = results,
          statusMessage =
            when {
              review > 0 -> "$review title(s) need you to pick the right film."
              series > 0 -> "Your watchlist holds only TV series, which never play in cinemas."
              else -> "No films to look for yet."
            },
          isSuccess = false,
        )
      }

      // 3. Poll each enabled cinema, grouped so one request serves a whole chain.
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

      // 4. Determine what TMDB film each screening actually is, then match against the
      // watchlist by that id first — the "how we link" the board asked for — falling back to
      // title/year comparison for whatever does not resolve (no TMDB key, an unlisted title, or
      // a watchlist entry not yet identified).
      val tmdbIdCache = resolveScreeningTmdbIds(raw)
      val matched =
        ScreeningMatcher.match(raw, currentWatchlist) { tmdbIdCache[it.tmdbCacheKey()] }
          .map { (screening, item, _) ->
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

      // 5. Persist. Only prune venues we actually reached, so a failed source does not wipe the
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

      // 6. Notify about showings the user has not been told about yet.
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

  /**
   * Resolve the TMDB id of every distinct film among [screenings], once each rather than once
   * per showing — several venues typically screen the same film. Empty (not per-item errors)
   * when TMDB is unconfigured, which is what makes [ScreeningMatcher] fall back to text matching
   * uniformly rather than partially.
   */
  private suspend fun resolveScreeningTmdbIds(screenings: List<RawScreening>): Map<String, Int?> {
    if (!lookup.isConfigured) return emptyMap()
    val distinct = screenings.distinctBy { it.tmdbCacheKey() }
    return distinct.associate { screening ->
      screening.tmdbCacheKey() to
        runCatching { lookup.resolveBestMatch(screening.title, screening.year) }
          .getOrNull()
          ?.tmdbId
    }
  }

  private fun RawScreening.tmdbCacheKey(): String = "$title|$year"

  fun sendTestNotification() = notifications.sendTestNotification()

  /** Exception messages reach the UI, so make them readable rather than class names. */
  private fun Throwable.readableMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "Unknown error"

  companion object {
    private const val TAG = "KinoRepository"

    /**
     * Titles identified per sync. A 270-film Google TV import is spread over a few runs rather
     * than firing hundreds of lookups at once.
     */
    private const val RESOLVE_PER_RUN = 120

    /**
     * Posters are cosmetic, not something to burn TMDB's public rate limit on — a handful per
     * sync means a large Trakt watchlist fills in "over time" rather than in one burst.
     */
    private const val POSTER_BACKFILL_PER_RUN = 20

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
