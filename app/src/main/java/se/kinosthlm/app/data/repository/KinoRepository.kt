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
import se.kinosthlm.app.BuildConfig
import se.kinosthlm.app.data.local.AppDatabase
import se.kinosthlm.app.data.match.ScreeningMatcher
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.NotificationLog
import se.kinosthlm.app.data.model.Screening
import se.kinosthlm.app.data.model.ScreeningTitleCache
import se.kinosthlm.app.data.model.SourceResult
import se.kinosthlm.app.data.model.SyncReport
import se.kinosthlm.app.data.model.TitleCandidate
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.model.WatchlistSource
import se.kinosthlm.app.data.prefs.SettingsStore
import se.kinosthlm.app.data.source.CinemaSourceRegistry
import se.kinosthlm.app.data.source.RawScreening
import se.kinosthlm.app.data.watchlist.CsvWatchlistImporter
import se.kinosthlm.app.data.watchlist.WatchlistCsvExporter
import se.kinosthlm.app.data.watchlist.ImdbPublicListProvider
import se.kinosthlm.app.data.watchlist.PastedTitleList
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

  /**
   * The user's own TMDB key, if they set one, refreshed by every suspending entry point that is
   * about to talk to TMDB. Kept as a plain field because [TitleLookup] reads it synchronously
   * per request, while the setting itself lives in DataStore.
   */
  @Volatile private var userTmdbKey: String = ""

  private val lookup = TitleLookup({ userTmdbKey.ifBlank { BuildConfig.TMDB_API_KEY } })
  private val resolver = TitleResolver(lookup)

  /** Whether the build shipped a key of its own; false means the user must supply one. */
  val hasBuiltInTmdbKey: Boolean get() = BuildConfig.TMDB_API_KEY.isNotBlank()

  /**
   * True if TMDB has rate limited us recently enough to still explain what the user is seeing.
   * A shared key is one budget for every install of that build, so this is worth saying out loud.
   */
  val isTmdbRateLimited: Boolean
    get() =
      lookup.lastRateLimitedAt != 0L &&
        System.currentTimeMillis() - lookup.lastRateLimitedAt < RATE_LIMIT_NOTICE_MILLIS

  /** Pick up a key the user changed in Settings before making any TMDB request. */
  private suspend fun refreshTmdbKey() {
    userTmdbKey = runCatching { settings.currentTmdbApiKey() }.getOrDefault("")
  }

  /** Poster requests already running, so a recomposing card cannot fire the same one twice. */
  private val inFlightPosters = java.util.Collections.synchronizedSet(mutableSetOf<String>())

  /**
   * Poster fetches run a couple at a time rather than one per visible card at once.
   *
   * Scrolling a long list would otherwise fire a dozen simultaneous TMDB requests, which both
   * starves whichever film is actually on screen — the queue is unordered, so the top of the
   * list can end up last — and is a good way to earn the 429 that makes everything slower still.
   */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private val posterDispatcher = Dispatchers.IO.limitedParallelism(2)

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

  /**
   * Follow or stop following a venue.
   *
   * Switching one off drops the showings we already found there, not just future polling. The
   * cinema is no longer visited, so the usual "did this sync see it again?" pruning can never
   * reach it, and its screenings would otherwise linger under "Showing soon" until each date
   * passed — which reads as the toggle not having worked.
   */
  suspend fun setCinemaEnabled(cinemaId: String, isEnabled: Boolean) =
    withContext(Dispatchers.IO) {
      database.cinemaDao().setEnabled(cinemaId, isEnabled)
      if (!isEnabled) {
        database.screeningDao().deleteForCinema(cinemaId)
        database.cinemaDao().updateStats(cinemaId, 0L, 0, 0)
      }
    }

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

  /**
   * Restore a CSV this app exported.
   *
   * Adds rather than replaces. A backup is something you reach for to get films *back* — losing
   * whatever you had added since would be the opposite of the point — so this merges, and a film
   * already present just gains a second claim on itself. It goes in under the manual source for
   * the same reason: nothing upstream is going to keep it alive.
   *
   * Returns how many films the file contained.
   */
  suspend fun importBackup(uri: Uri): Int =
    withContext(Dispatchers.IO) {
      val items =
        context.contentResolver.openInputStream(uri)?.use {
          CsvWatchlistImporter.parse(it, WatchlistItem.SOURCE_MANUAL)
        } ?: error("Could not open the selected file")

      if (items.isEmpty()) {
        error("No films found in that file. Is it a KinoSthlm export?")
      }
      for (item in items) database.watchlistDao().addManual(item)
      items.size
    }

  /** Write the watchlist to [uri] as a Trakt-importable CSV. Returns how many films were written. */
  suspend fun exportCsv(uri: Uri): Int =
    withContext(Dispatchers.IO) {
      val items = database.watchlistDao().getAll().filter { it.isMatchable }
      val csv = WatchlistCsvExporter.toCsv(items)
      context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
        ?: error("Could not write to that file")
      // The header line is not a film, and neither are entries with no id for Trakt to match on.
      csv.lineSequence().count { it.isNotBlank() } - 1
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
   * Free-text search for the manual add flow.
   *
   * An IMDb or TMDB link resolves to exactly the film it names — no ambiguity, so a one-item
   * list. A typed title (optionally "Title 1999" or "Title (1999)") goes through TMDB search and
   * comes back as up to three close matches, narrowed by year when one was given, for the user to
   * pick between rather than guessing which of several same-named films they meant.
   */
  suspend fun searchToAdd(input: String): List<TitleLookup.Candidate> =
    withContext(Dispatchers.IO) {
      refreshTmdbKey()
      if (!lookup.isConfigured) {
        error(
          "No TMDB API key. Add one under Settings → TMDB, or build with one. " +
            "See the README's \"API keys\" section."
        )
      }

      val trimmed = input.trim()
      if (trimmed.isEmpty()) return@withContext emptyList()

      TitleLookup.extractImdbId(trimmed)?.let { imdbId ->
        return@withContext listOfNotNull(
          lookup.lookupByImdbId(imdbId) ?: error("IMDb has no title with the id $imdbId.")
        )
      }
      TitleLookup.extractTmdbId(trimmed)?.let { tmdbId ->
        return@withContext listOfNotNull(
          lookup.fetchMovieDetails(tmdbId)?.let { withImdbId(it) }
            ?: error("TMDB has no title with the id $tmdbId.")
        )
      }

      val yearMatch = Regex("""^(.*?)[\s,(]+(\d{4})\)?$""").find(trimmed)
      val title = yearMatch?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: trimmed
      val year = yearMatch?.groupValues?.get(2)?.toIntOrNull()

      val films = lookup.lookup(title).films
      val narrowed =
        year
          ?.let { y -> films.filter { it.year != null && kotlin.math.abs(it.year - y) <= 1 } }
          ?.takeIf { it.isNotEmpty() }
      // TMDB is how the app identifies a film; IMDb is how the *user* looks one up. Search
      // results carry no IMDb id, so fetch it here rather than showing a TMDB link to whoever
      // happened to arrive via search — a handful of requests for at most three results.
      (narrowed ?: films).take(3).map { withImdbId(it) }
    }

  /** Best-effort IMDb id, so a candidate can always offer the link a person expects. */
  private suspend fun withImdbId(candidate: TitleLookup.Candidate): TitleLookup.Candidate =
    if (candidate.imdbId != null) candidate
    else runCatching { lookup.attachImdbId(candidate) }.getOrDefault(candidate)

  /** Add a film the user picked from [searchToAdd]'s results. Returns its title. */
  suspend fun addCandidate(candidate: TitleLookup.Candidate): String =
    withContext(Dispatchers.IO) {
      refreshTmdbKey()
      if (!candidate.isFilm) {
        error("\"${candidate.title}\" is a TV series, which never plays in cinemas.")
      }
      val imdbId = candidate.imdbId ?: runCatching { lookup.attachImdbId(candidate) }.getOrNull()?.imdbId

      database.watchlistDao()
        .addManual(
          WatchlistItem(
            id = WatchlistItem.idFor(candidate.tmdbId, imdbId, candidate.title, candidate.year),
            title = candidate.title,
            year = candidate.year,
            imdbId = imdbId,
            tmdbId = candidate.tmdbId,
            posterUrl = candidate.posterUrl,
            overview = candidate.overview,
            titleType = WatchlistItem.TYPE_MOVIE,
          )
        )
      candidate.title
    }

  /**
   * Add several films at once from typed names — one per line, for a watchlist kept somewhere
   * with no export at all (a notes app, an email to yourself).
   *
   * Nothing is looked up here. Each name is stored as a bare title, exactly as a Google TV import
   * arrives, and the identification pass that follows every import decides what each one is —
   * offering a choice where a name is shared. Guessing at this stage would be the one thing worse
   * than asking. Returns how many new lines were added.
   */
  suspend fun addManualTitles(text: String): Int =
    withContext(Dispatchers.IO) {
      val entries = PastedTitleList.parse(text)
      if (entries.isEmpty()) error("No titles found. Put one film per line.")

      for (entry in entries) {
        database.watchlistDao()
          .addManual(
            WatchlistItem(
              id = WatchlistItem.idFor(null, null, entry.title, entry.year),
              title = entry.title,
              year = entry.year,
            )
          )
      }
      entries.size
    }

  /**
   * The user says this really is a film, whatever TMDB thinks. Clears the series verdict and the
   * review flag together, so it goes straight back to being matched against listings.
   */
  suspend fun keepAsFilm(itemId: String) =
    withContext(Dispatchers.IO) {
      database.watchlistDao().keepAsFilm(itemId)
      database.titleCandidateDao().deleteFor(itemId)
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

  /** Keep matching and showing this film, but never push a notification for it. */
  suspend fun setNotificationsMuted(itemId: String, muted: Boolean) =
    withContext(Dispatchers.IO) { database.watchlistDao().setMuted(itemId, muted) }

  /** Only notify for this film at a cinema carrying [tag] (see [Cinema.tagList]); null means any. */
  suspend fun setRequiredVenueTag(itemId: String, tag: String?) =
    withContext(Dispatchers.IO) { database.watchlistDao().setRequiredVenueTag(itemId, tag) }

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
      refreshTmdbKey()
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
   * Fetch one film's poster and synopsis, on demand.
   *
   * Called when a card actually scrolls into view rather than in bulk during a sync: a 300-film
   * watchlist otherwise spends its whole TMDB budget fetching posters nobody is looking at, in
   * database order, so the films on screen are the *last* to fill in. Cheap to call repeatedly —
   * entries that already have a poster, or have no TMDB id to ask about, return immediately, and
   * [inFlightPosters] keeps a card that recomposes from firing the same request twice.
   */
  suspend fun fetchPosterFor(itemId: String) =
    withContext(posterDispatcher) {
      refreshTmdbKey()
      if (!lookup.isConfigured) return@withContext
      val item = database.watchlistDao().getById(itemId) ?: return@withContext
      val tmdbId = item.tmdbId ?: return@withContext
      // Once per film, whatever we already have. A Trakt import arrives with a poster but no
      // synopsis and no genres, so gating on a missing poster meant those films were never asked
      // about at all — and the genre filter had nothing to offer for most of the list.
      if (item.posterChecked) return@withContext
      if (!inFlightPosters.add(itemId)) return@withContext

      try {
        val details = runCatching { lookup.fetchMovieDetails(tmdbId) }.getOrNull() ?: return@withContext
        database.watchlistDao()
          .insertAll(
            listOf(
              item.copy(
                posterUrl = details.posterUrl ?: item.posterUrl,
                overview = item.overview ?: details.overview,
                // TMDB genuinely has no artwork for some films. Recording that we asked is what
                // lets the card stop pretending one is still on its way.
                posterChecked = true,
                genres = details.genres.joinToString(",").ifBlank { item.genres },
              )
            )
          )
      } finally {
        inFlightPosters.remove(itemId)
      }
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
      refreshTmdbKey()
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


  // --- The sync ---

  /**
   * Refresh everything and return what happened.
   *
   * Never throws: a failure in one cinema or provider is reported in [SyncReport.sourceResults]
   * so the UI can name it, while the rest of the sync still completes.
   */
  suspend fun sync(onStep: (String) -> Unit = {}): SyncReport =
    withContext(Dispatchers.IO) {
      val startedAt = System.currentTimeMillis()
      refreshTmdbKey()
      seedCinemas()

      // 1. Refresh whatever can refresh itself. Currently that means Trakt.
      var imported = 0
      val results = mutableListOf<SourceResult>()
      if (trakt.isConnected()) {
        onStep("Updating watchlist from Trakt…")
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
      onStep("Identifying titles…")
      runCatching { resolveTitles() }
        .onFailure { Log.d(TAG, "Title resolution skipped: ${it.message}") }

      // 2b. Give IMDb-only entries (from CSV / public-list imports) a TMDB id too, so every
      // source ends up on the same standardized key rather than just the Google TV path.
      runCatching { backfillTmdbIds() }
        .onFailure { Log.d(TAG, "TMDB backfill skipped: ${it.message}") }


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
        onStep("Fetching ${source.label}…")
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
      onStep("Matching screenings…")
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
          .updateStats(
            id = cinema.id,
            timestamp = startedAt,
            count = matched.count { it.cinemaId == cinema.id },
            // Everything we could read there, matched or not — the number that tells a quiet
            // week apart from a broken adapter.
            seen = raw.count { it.cinemaId == cinema.id },
          )
      }

      // 6. Notify about showings the user has not been told about yet.
      val mutedIds = currentWatchlist.filter { it.notificationsMuted }.map { it.id }.toSet()
      val requiredTagByItem = currentWatchlist.mapNotNull { item ->
        item.requiredVenueTag?.let { item.id to it }
      }.toMap()
      val cinemasById = enabled.associateBy { it.id }
      val alreadyNotified = database.notificationDao().notifiedIds().toSet()
      val fresh = matched.filter { it.id !in alreadyNotified }
      // A muted film's screenings still count as "seen" so unmuting it later does not replay
      // everything found while it was muted. Same for a venue-tag mismatch: the screening is
      // real and shown, it just does not push, so seeing it later at the right kind of cinema
      // still needs to notify.
      val toNotify = fresh.filterNot { it.watchlistMovieId in mutedIds }
        .filter { screening ->
          val requiredTag = requiredTagByItem[screening.watchlistMovieId] ?: return@filter true
          requiredTag in (cinemasById[screening.cinemaId]?.tagList ?: emptyList())
        }
      var sent = 0
      if (toNotify.isNotEmpty() && settings.notificationsEnabled.first()) {
        onStep("Sending notifications…")
        notifications.notifyNewScreenings(toNotify)
        sent = toNotify.size
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
   *
   * Answers are remembered in [ScreeningTitleCache] between syncs. Without that, every sync
   * re-asked TMDB what every title on every cinema's schedule was — a hundred-odd searches, four
   * times a day, per device, for answers that do not change. Misses are cached too, but expire
   * sooner: a title TMDB cannot place today might simply not be listed yet.
   */
  private suspend fun resolveScreeningTmdbIds(screenings: List<RawScreening>): Map<String, Int?> {
    if (!lookup.isConfigured) return emptyMap()

    val cacheDao = database.screeningTitleCacheDao()
    val now = System.currentTimeMillis()
    cacheDao.deleteStaleMisses(now - MISS_CACHE_MILLIS)
    cacheDao.deleteResolvedBefore(now - HIT_CACHE_MILLIS)

    val keys = screenings.map { it.tmdbCacheKey() }.distinct()
    val known = cacheDao.get(keys).associate { it.titleKey to it.tmdbId }

    val unknown = screenings.distinctBy { it.tmdbCacheKey() }.filter { it.tmdbCacheKey() !in known }
    if (unknown.isEmpty()) return known

    val resolved =
      unknown.associate { screening ->
        screening.tmdbCacheKey() to
          runCatching { lookup.resolveBestMatch(screening.title, screening.year) }
            .getOrNull()
            ?.tmdbId
      }
    cacheDao.insertAll(resolved.map { (key, id) -> ScreeningTitleCache(key, id, now) })

    return known + resolved
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

    /** Ninety days: long past any screening we would re-announce. */
    private const val LOG_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000

    /** A title's TMDB id does not change; re-check yearly only to catch renamed entries. */
    private const val HIT_CACHE_MILLIS = 365L * 24 * 60 * 60 * 1000

    /** A film TMDB could not place may just be too new to be listed, so retry within the week. */
    private const val MISS_CACHE_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** How long a 429 stays worth mentioning; TMDB's own windows are far shorter than this. */
    private const val RATE_LIMIT_NOTICE_MILLIS = 30L * 60 * 1000

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
