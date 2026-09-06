package se.kinosthlm.app.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.Screening
import se.kinosthlm.app.data.model.SourceResult
import se.kinosthlm.app.data.model.SyncReport
import se.kinosthlm.app.data.model.TitleCandidate
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.model.WatchlistSource
import se.kinosthlm.app.data.prefs.SettingsStore
import se.kinosthlm.app.data.repository.KinoRepository
import se.kinosthlm.app.data.watchlist.TitleLookup
import se.kinosthlm.app.worker.SyncWorker

/** A watchlisted film together with the showings we found for it. */
data class WatchlistEntry(
  val item: WatchlistItem,
  val screenings: List<Screening> = emptyList(),
  /** Which connected lists this film came from; empty means it was added by hand. */
  val sources: List<String> = emptyList(),
) {
  val nextScreening: Screening? get() = screenings.minByOrNull { it.screeningTime }

  /** Protected from disappearing if its real sources later drop it. */
  val isPinned: Boolean get() = WatchlistItem.SOURCE_PINNED in sources

  /** Actual watchlists this came from; a pin is protection, not provenance. */
  val realSources: List<String>
    get() = sources.filter { it != WatchlistItem.SOURCE_PINNED }

  /** Still matched and shown, but never pushes a notification. */
  val isMuted: Boolean get() = item.notificationsMuted
}

/** Results of a manual-add search, kept separate from [UiState] since it is dialog-local. */
data class AddSearchState(
  val results: List<TitleLookup.Candidate> = emptyList(),
  val isSearching: Boolean = false,
  val error: String? = null,
)

/** One ambiguous title and the films it could be, for the review sheet. */
data class ReviewEntry(
  val item: WatchlistItem,
  val candidates: List<TitleCandidate>,
)

/**
 * How the watchlist is ordered — each direction is its own option rather than a field plus a
 * separate up/down toggle, so one chip says exactly what you are looking at and the header row
 * stays narrow enough not to wrap.
 */
enum class WatchlistSort(val label: String) {
  ALPHABETICAL("A–Z"),
  REVERSE_ALPHABETICAL("Z–A"),
  NEWEST("Newest"),
  OLDEST("Oldest"),
  RECENTLY_ADDED("Recently added");

  fun next(): WatchlistSort = entries[(ordinal + 1) % entries.size]
}

/**
 * How often the background sync runs.
 *
 * Cinema programmes are updated once a day at most, so anything finer than daily re-reads the
 * same pages — which is why the old hourly options came with a note asking people not to sync too
 * often. Making the choices themselves considerate is better than asking.
 */
enum class SyncCadence(val hours: Long, val label: String) {
  DAILY(24, "Every day"),
  EVERY_OTHER_DAY(48, "Every other day"),
  EVERY_THIRD_DAY(72, "Every third day"),
  WEEKLY(168, "Once a week");

  companion object {
    /** Nearest cadence to a stored interval, so an old six-hourly setting maps to Daily. */
    fun of(hours: Long): SyncCadence = entries.minBy { kotlin.math.abs(it.hours - hours) }
  }
}

/** State of the Trakt device-code flow, driven from Settings. */
sealed interface TraktState {
  data object Disconnected : TraktState

  data class AwaitingCode(val code: String, val url: String) : TraktState

  data object Connected : TraktState
}

data class UiState(
  val watchlist: List<WatchlistEntry> = emptyList(),
  /** Screenings after [cinemaFilter] is applied — what the schedule list shows. */
  val screenings: List<Screening> = emptyList(),
  /** Every upcoming screening, unfiltered. The filter chips need this or they vanish on use. */
  val allScreenings: List<Screening> = emptyList(),
  val cinemas: List<Cinema> = emptyList(),
  /** Whether any matchable film is synced at all, independent of the search box or filter chip. */
  val hasFilms: Boolean = false,
  /** Everything being tracked, before any filter or search narrows [watchlist] down. */
  val trackedCount: Int = 0,
  /**
   * Imported films still waiting on a TMDB id. Kept out of the list — they have no poster and
   * are about to be re-keyed — but counted, so an import never looks like it lost titles.
   */
  val identifyingCount: Int = 0,
  val isSyncing: Boolean = false,
  val syncStep: String? = null,
  val lastReport: SyncReport? = null,
  val lastSyncAt: Long = 0L,
  val lastSyncSummary: String = "",
  val autoSyncEnabled: Boolean = true,
  val syncIntervalHours: Long = SettingsStore.DEFAULT_INTERVAL_HOURS,
  /** Local hour the scheduled sync aims for. */
  val syncHourOfDay: Int = SettingsStore.DEFAULT_SYNC_HOUR,
  val horizonDays: Long = SettingsStore.DEFAULT_HORIZON_DAYS,
  val notificationsEnabled: Boolean = true,
  val cinemaFilter: String? = null,
  /**
   * The watchlist filters, all of them additive within a facet and combined across facets: pick
   * Action *and* Comedy to see both, then add Trakt to see only those from Trakt. An empty set
   * means that facet is not filtering at all, which is not the same as selecting nothing.
   */
  val filters: WatchlistFilters = WatchlistFilters(),
  /** Sources that actually contribute something, so the picker offers only real choices. */
  val availableSources: List<String> = emptyList(),
  /** Genres present in the watchlist, for the same reason. */
  val availableGenres: List<String> = emptyList(),
  val watchlistQuery: String = "",
  val watchlistSort: WatchlistSort = WatchlistSort.RECENTLY_ADDED,
  /** Ambiguous titles waiting for the user to pick the right film. */
  val needsReview: List<ReviewEntry> = emptyList(),
  val isResolving: Boolean = false,
  val resolveProgress: Pair<Int, Int>? = null,
  val traktState: TraktState = TraktState.Disconnected,
  val traktConfigured: Boolean = false,
  val tmdbConfigured: Boolean = false,
  /** TMDB has throttled us recently — worth saying, since the symptoms look like a broken app. */
  val tmdbRateLimited: Boolean = false,
  /** The user's own TMDB key, if they set one. Blank means the build's key (if any) is used. */
  val tmdbKey: String = "",
  val message: String? = null,
  /** Films selected for a bulk action, entered with a long press. Empty means not selecting. */
  val selectedIds: Set<String> = emptySet(),
) {
  val failedSources: List<SourceResult> get() = lastReport?.failedSources.orEmpty()
  val isSelecting: Boolean get() = selectedIds.isNotEmpty()

  /** How many filters are on, so the chip can say so without being opened. */
  val activeFilterCount: Int get() = filters.activeCount
}

/**
 * Everything narrowing the watchlist down, in one place.
 *
 * Each facet is a *set* rather than a single choice: "Action or Comedy" and "Trakt or Google TV"
 * are the obvious things to want, and single-select made them impossible. Within a facet the
 * selections are alternatives (any of these); across facets they compound (all of these).
 */
data class WatchlistFilters(
  /** Only films with a screening already found. Was a chip of its own; it belongs with the rest. */
  val showingSoon: Boolean = false,
  /** Only films whose notifications the user silenced. */
  val mutedOnly: Boolean = false,
  val sources: Set<String> = emptySet(),
  val genres: Set<String> = emptySet(),
  /** Only films with a screening at a cinema carrying one of these [Cinema.tagList] tags. */
  val venueTags: Set<String> = emptySet(),
) {
  val activeCount: Int
    get() =
      sources.size + genres.size + venueTags.size +
        (if (showingSoon) 1 else 0) +
        (if (mutedOnly) 1 else 0)

  val isEmpty: Boolean get() = activeCount == 0
}

class KinoViewModel(application: Application) : AndroidViewModel(application) {

  private val repository = KinoRepository.getInstance(application)
  private val settings = SettingsStore(application)

  private val isSyncing = MutableStateFlow(false)
  private val syncStep = MutableStateFlow<String?>(null)
  private val lastReport = MutableStateFlow<SyncReport?>(null)
  private val cinemaFilter = MutableStateFlow<String?>(null)
  private val watchlistFilters = MutableStateFlow(WatchlistFilters())
  private val watchlistQuery = MutableStateFlow("")
  private val watchlistSort = MutableStateFlow(WatchlistSort.RECENTLY_ADDED)
  private val traktState = MutableStateFlow<TraktState>(TraktState.Disconnected)
  private val message = MutableStateFlow<String?>(null)
  private val resolveProgress = MutableStateFlow<Pair<Int, Int>?>(null)
  private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
  /** A pasted link resolved to this film, awaiting the user's yes. */
  private val linkPreview = MutableStateFlow<TitleCandidate?>(null)
  private val addSearchResults = MutableStateFlow<List<TitleLookup.Candidate>>(emptyList())
  private val addSearching = MutableStateFlow(false)
  private val addSearchError = MutableStateFlow<String?>(null)

  val addSearchState: StateFlow<AddSearchState> =
    combine(addSearchResults, addSearching, addSearchError) { results, searching, error ->
      AddSearchState(results, searching, error)
    }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddSearchState())

  /** The film a pasted link resolved to, waiting to be confirmed. Null when nothing is pending. */
  val linkPreviewState: StateFlow<TitleCandidate?> =
    linkPreview.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  private var traktJob: Job? = null
  private var addSearchJob: Job? = null

  val uiState: StateFlow<UiState> =
    combine(
        combine(
          repository.watchlist,
          repository.upcomingScreenings,
          repository.cinemas,
          repository.watchlistSources,
          combine(cinemaFilter, watchlistFilters, watchlistQuery, watchlistSort) {
            cinema,
            filters,
            query,
            sort ->
            Filters(cinema, filters, query, sort)
          },
        ) { watchlist, screenings, cinemas, sources, filters ->
          Content(watchlist, screenings, cinemas, sources, filters)
        },
        combine(
          combine(
            settings.autoSyncEnabled,
            settings.syncIntervalHours,
            settings.horizonDays,
            settings.tmdbApiKey,
            settings.syncHourOfDay,
          ) { auto, interval, horizon, tmdbKey, syncHour ->
            Schedule(auto, interval, horizon, tmdbKey, syncHour)
          },
          settings.notificationsEnabled,
          settings.lastSyncAt,
          settings.lastSyncSummary,
        ) { schedule, notifications, lastAt, summary ->
          Prefs(
            schedule.autoSync,
            schedule.intervalHours,
            schedule.horizonDays,
            schedule.tmdbKey,
            schedule.syncHour,
            notifications,
            lastAt,
            summary,
          )
        },
        combine(
          combine(isSyncing, syncStep, selectedIds) { syncing, step, selected ->
            Triple(syncing, step, selected)
          },
          lastReport,
          traktState,
          message,
          resolveProgress,
        ) { sync, report, trakt, msg, progress ->
          Transient(sync.first, sync.second, sync.third, report, trakt, msg, progress)
        },
        combine(repository.needingReview, repository.reviewCandidates) { review, candidates ->
          val byItem = candidates.groupBy { it.watchlistItemId }
          Review(review.map { ReviewEntry(it, byItem[it.id].orEmpty()) })
        },
      ) { content, prefs, transient, review ->
        val byMovie = content.screenings.groupBy { it.watchlistMovieId }
        val sourcesByItem = content.sources.groupBy { it.itemId }
        // Series never play in cinemas, ambiguous titles would match the wrong film, and
        // suppressed ones the user deleted. All three are handled elsewhere rather than
        // cluttering the list.
        val identified = content.watchlist.filter { it.isMatchable }
        // An entry keyed on its name alone is a half-finished import: no poster, no year, and
        // about to be replaced by the same film under its TMDB id the moment identification
        // reaches it. Showing that is showing our own workings — so it waits offstage, and the
        // count of what is still being worked on is surfaced instead.
        //
        // Only while TMDB can actually answer, though. With no key configured nothing would ever
        // gain an id, and hiding would empty the watchlist permanently.
        val matchable =
          if (prefs.tmdbKey.isNotBlank() || repository.hasBuiltInTmdbKey) {
            identified.filter { it.tmdbId != null }
          } else {
            identified
          }
        val entries =
          matchable
            .map {
              WatchlistEntry(
                item = it,
                screenings = byMovie[it.id].orEmpty(),
                sources = sourcesByItem[it.id].orEmpty().map { row -> row.sourceId },
              )
            }
            .let { list ->
              val filters = content.filters.watchlist
              if (filters.isEmpty) return@let list

              // Additive within a facet, compounding across them: "Action or Comedy", "from
              // Trakt or Google TV", "and showing soon". An empty facet is not a filter.
              val tagsByCinema = content.cinemas.associate { it.id to it.tagList }
              list.filter { entry ->
                if (filters.showingSoon && entry.nextScreening == null) return@filter false
                if (filters.mutedOnly && !entry.isMuted) return@filter false

                if (filters.sources.isNotEmpty()) {
                  // "Manual Add" is the absence of a real source rather than a row of its own,
                  // for films added by hand and never claimed by a list.
                  val matches =
                    filters.sources.any { source ->
                      source in entry.sources ||
                        (source == WatchlistItem.SOURCE_MANUAL && entry.realSources.isEmpty())
                    }
                  if (!matches) return@filter false
                }

                if (filters.genres.isNotEmpty() &&
                  entry.item.genreList.none { it in filters.genres }
                ) {
                  return@filter false
                }

                if (filters.venueTags.isNotEmpty()) {
                  // A venue tag is a fact about where a film is actually playing, so a film with
                  // no screening yet cannot satisfy one.
                  val playingAt =
                    entry.screenings.flatMap { tagsByCinema[it.cinemaId].orEmpty() }.toSet()
                  if (playingAt.none { it in filters.venueTags }) return@filter false
                }

                true
              }
            }
            .let { list ->
              content.filters.query.trim().takeIf { it.isNotEmpty() }?.let { query ->
                list.filter { entry -> entry.item.title.contains(query, ignoreCase = true) }
              } ?: list
            }
            .let { list ->
              when (content.filters.sort) {
                WatchlistSort.ALPHABETICAL -> list.sortedBy { it.item.title.lowercase() }
                WatchlistSort.REVERSE_ALPHABETICAL ->
                  list.sortedByDescending { it.item.title.lowercase() }
                WatchlistSort.NEWEST -> list.sortedByDescending { it.item.year ?: 0 }
                // Unknown years sink to the end rather than pretending to be the oldest films.
                WatchlistSort.OLDEST -> list.sortedBy { it.item.year ?: Int.MAX_VALUE }
                WatchlistSort.RECENTLY_ADDED -> list.sortedByDescending { it.item.addedAt }
              }
            }

        UiState(
          watchlist = entries,
          screenings =
            content.filters.cinemaId?.let { id -> content.screenings.filter { it.cinemaId == id } }
              ?: content.screenings,
          allScreenings = content.screenings,
          cinemas = content.cinemas,
          hasFilms = matchable.isNotEmpty(),
          trackedCount = matchable.size,
          identifyingCount = identified.size - matchable.size,
          isSyncing = transient.syncing,
          syncStep = transient.step,
          lastReport = transient.report,
          lastSyncAt = prefs.lastSyncAt,
          lastSyncSummary = prefs.summary,
          autoSyncEnabled = prefs.autoSync,
          syncIntervalHours = prefs.intervalHours,
          syncHourOfDay = prefs.syncHour,
          horizonDays = prefs.horizonDays,
          notificationsEnabled = prefs.notifications,
          cinemaFilter = content.filters.cinemaId,
          filters = content.filters.watchlist,
          // Offered options come from what is actually there, so the picker never lists a source
          // or genre that would filter the list down to nothing.
          availableSources =
            buildSet {
                for (entry in matchable) {
                  val rows = sourcesByItem[entry.id].orEmpty().map { it.sourceId }
                  addAll(rows.filter { it != WatchlistItem.SOURCE_PINNED })
                  if (rows.none { it != WatchlistItem.SOURCE_PINNED }) {
                    add(WatchlistItem.SOURCE_MANUAL)
                  }
                }
              }
              .sorted(),
          availableGenres = matchable.flatMap { it.genreList }.distinct().sorted(),
          watchlistQuery = content.filters.query,
          watchlistSort = content.filters.sort,
          traktState = transient.trakt,
          traktConfigured = repository.trakt.isConfigured,
          tmdbConfigured = prefs.tmdbKey.isNotBlank() || repository.hasBuiltInTmdbKey,
          tmdbRateLimited = repository.isTmdbRateLimited,
          tmdbKey = prefs.tmdbKey,
          needsReview = review.entries,
          isResolving = transient.progress != null,
          resolveProgress = transient.progress,
          message = transient.message,
          selectedIds = transient.selected,
        )
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

  init {
    viewModelScope.launch {
      repository.seedCinemas()
      if (repository.trakt.isConnected()) traktState.value = TraktState.Connected
      // Schedule (or re-schedule) background sync to match the persisted preference. This is a
      // one-shot on launch, not a subscription: the previous version re-ran a scan on every
      // watchlist emission, which looped forever once the list was empty.
      if (settings.autoSyncEnabled.first()) {
        SyncWorker.schedulePeriodic(getApplication(), settings.currentIntervalHours(), settings.currentSyncHour())
      }
    }
  }

  // --- Sync ---

  fun sync() {
    if (isSyncing.value) return
    viewModelScope.launch {
      isSyncing.value = true
      try {
        val report = repository.sync { step -> syncStep.value = step }
        lastReport.value = report
        message.value = report.statusMessage
      } catch (error: Exception) {
        message.value = "Sync failed: ${error.message}"
      } finally {
        isSyncing.value = false
        syncStep.value = null
      }
    }
  }

  fun setAutoSync(enabled: Boolean) {
    viewModelScope.launch {
      settings.setAutoSyncEnabled(enabled)
      if (enabled) {
        SyncWorker.schedulePeriodic(getApplication(), settings.currentIntervalHours(), settings.currentSyncHour())
        message.value = "Background sync on"
      } else {
        SyncWorker.cancelPeriodic(getApplication())
        message.value = "Background sync off"
      }
    }
  }

  fun setSyncInterval(hours: Long) {
    viewModelScope.launch {
      settings.setSyncIntervalHours(hours)
      if (settings.autoSyncEnabled.first()) {
        SyncWorker.schedulePeriodic(getApplication(), hours, settings.currentSyncHour())
      }
      message.value = "Syncing ${SyncCadence.of(hours).label.lowercase()}"
    }
  }

  /** What time of day the scheduled sync should aim for. */
  fun setSyncHour(hour: Int) {
    viewModelScope.launch {
      settings.setSyncHourOfDay(hour)
      if (settings.autoSyncEnabled.first()) {
        SyncWorker.schedulePeriodic(getApplication(), settings.currentIntervalHours(), hour)
      }
      message.value = "Syncing around ${"%02d".format(hour)}:00"
    }
  }

  /** Use the user's own TMDB key instead of the one this build shipped with; blank clears it. */
  fun setTmdbApiKey(key: String) {
    viewModelScope.launch {
      settings.setTmdbApiKey(key)
      message.value = if (key.isBlank()) "Using this build's TMDB key" else "TMDB key saved"
    }
  }

  fun setHorizonDays(days: Long) {
    viewModelScope.launch {
      settings.setHorizonDays(days)
      message.value = "Looking $days days ahead"
      sync()
    }
  }

  fun setNotificationsEnabled(enabled: Boolean) {
    viewModelScope.launch { settings.setNotificationsEnabled(enabled) }
  }

  /**
   * Whether the notification permission dialog has been shown before.
   *
   * Blocking, because the caller is a `LaunchedEffect` deciding whether to ask at all, and a
   * frame of "not asked yet" would fire the prompt every launch.
   */
  suspend fun hasAskedForNotifications(): Boolean = settings.hasAskedForNotificationPermission()

  fun markNotificationPermissionAsked() {
    viewModelScope.launch { settings.markNotificationPermissionAsked() }
  }

  /**
   * The user said no. Alerts goes off and stays off — leaving the switch on would promise
   * notifications the system will never deliver, and it is the switch, not us, that asks again.
   */
  fun onNotificationPermissionDenied() {
    viewModelScope.launch {
      if (settings.notificationsEnabled.first()) {
        settings.setNotificationsEnabled(false)
        message.value = "Alerts off — Android needs notification permission for those."
      }
    }
  }

  // --- Watchlist ---

  fun connectTrakt() {
    if (traktJob?.isActive == true) return
    traktJob =
      viewModelScope.launch {
        try {
          val code = repository.trakt.requestDeviceCode()
          traktState.value = TraktState.AwaitingCode(code.userCode, code.verificationUrl)
          if (repository.trakt.awaitAuthorization(code)) {
            traktState.value = TraktState.Connected
            message.value = "Trakt connected"
            // Same reasoning as a CSV import: identify first so a Trakt film already in the list
            // under a bare title merges into one entry rather than appearing twice.
            identifyThenSync()
          } else {
            traktState.value = TraktState.Disconnected
            message.value = "Trakt code expired. Try again."
          }
        } catch (error: Exception) {
          // Only the initial code request can land here now — polling rides out network
          // failures itself. Keep any code we did get on screen so the user can carry on with
          // it rather than being sent back to a blank Connect button.
          val pending = traktState.value as? TraktState.AwaitingCode
          traktState.value = pending ?: TraktState.Disconnected
          message.value = "Trakt: ${error.message}"
        }
      }
  }

  fun cancelTraktConnect() {
    traktJob?.cancel()
    repository.trakt.cancelPendingAuthorization()
    traktState.value = TraktState.Disconnected
  }

  fun disconnectTrakt() {
    traktJob?.cancel()
    repository.trakt.disconnect()
    traktState.value = TraktState.Disconnected
    message.value = "Trakt disconnected"
  }

  fun importCsv(uri: Uri, sourceId: String) {
    viewModelScope.launch {
      try {
        val count = repository.importCsv(uri, sourceId)
        message.value = "Imported $count films"
        identifyThenSync()
      } catch (error: Exception) {
        message.value = error.message ?: "Import failed"
      }
    }
  }

  /**
   * Identify everything the import brought in, then sync.
   *
   * A freshly imported title has no id yet, so it is keyed on its name — and the same film
   * already in the list from Trakt is keyed on its TMDB id. Two rows, two source badges, one
   * film. They merge the moment identification gives the new one an id, which is why this must
   * run *now* rather than being left to the sync's own capped pass to chip away at over several
   * runs. That gap is what put two Terminator 2s in the list.
   */
  private suspend fun identifyThenSync() {
    runIdentification()
    sync()
  }

  /** Identify every unidentified title, reporting progress. Returns what it managed. */
  private suspend fun runIdentification() {
    if (resolveProgress.value != null) return
    resolveProgress.value = 0 to 0
    try {
      repository.resolveTitles(limit = Int.MAX_VALUE) { done, total ->
        resolveProgress.value = done to total
      }
    } catch (error: Exception) {
      message.value = "Could not identify titles: ${error.message}"
    } finally {
      resolveProgress.value = null
    }
  }

  /** Restore a CSV this app exported, merging it into whatever is already here. */
  fun importBackup(uri: Uri) {
    viewModelScope.launch {
      try {
        val count = repository.importBackup(uri)
        message.value = "Restored $count films"
        identifyThenSync()
      } catch (error: Exception) {
        message.value = error.message ?: "Restore failed"
      }
    }
  }

  /** Write the watchlist out as a Trakt-importable CSV at the location the user picked. */
  fun exportCsv(uri: Uri) {
    viewModelScope.launch {
      try {
        val count = repository.exportCsv(uri)
        message.value = "Exported $count films"
      } catch (error: Exception) {
        message.value = error.message ?: "Export failed"
      }
    }
  }

  fun importImdbList(url: String) {
    viewModelScope.launch {
      try {
        val count = repository.importImdbPublicList(url)
        message.value = "Imported $count films from IMDb"
        identifyThenSync()
      } catch (error: Exception) {
        message.value = error.message ?: "IMDb import failed"
      }
    }
  }

  /** Search TMDB for the manual add dialog: an IMDb/TMDB link, or a typed title and year. */
  fun searchToAdd(query: String) {
    addSearchJob?.cancel()
    if (query.isBlank()) {
      addSearchResults.value = emptyList()
      addSearching.value = false
      addSearchError.value = null
      return
    }
    addSearchJob =
      viewModelScope.launch {
        addSearching.value = true
        addSearchError.value = null
        runCatching { repository.searchToAdd(query) }
          .onSuccess { addSearchResults.value = it }
          .onFailure {
            addSearchResults.value = emptyList()
            addSearchError.value = it.message ?: "Search failed"
          }
        addSearching.value = false
      }
  }

  /** The user picked one of [searchToAdd]'s results. */
  fun addCandidate(candidate: TitleLookup.Candidate) {
    viewModelScope.launch {
      try {
        val title = repository.addCandidate(candidate)
        message.value = "Added $title"
        clearAddSearch()
        sync()
      } catch (error: Exception) {
        message.value = error.message ?: "Could not add that film"
      }
    }
  }

  fun clearAddSearch() {
    addSearchJob?.cancel()
    addSearchResults.value = emptyList()
    addSearching.value = false
    addSearchError.value = null
  }

  fun removeFilm(id: String) {
    viewModelScope.launch {
      repository.removeItem(id)
      message.value = "Removed. Delete it from the source list too, or it will return."
    }
  }

  /** Protect a film from disappearing when its real sources later drop it, or lift that. */
  fun togglePin(itemId: String, pinned: Boolean) {
    viewModelScope.launch {
      repository.setPinned(itemId, pinned)
      message.value = if (pinned) "Pinned — it will stay even if its source removes it" else "Unpinned"
    }
  }

  /** Keep matching and showing this film, but stop (or resume) pushing notifications for it. */
  fun toggleMute(itemId: String, muted: Boolean) {
    viewModelScope.launch {
      repository.setNotificationsMuted(itemId, muted)
      message.value = if (muted) "Muted — won't notify for this film" else "Unmuted"
    }
  }

  /** Only notify for this film at a cinema carrying [tag]; null clears the restriction. */
  fun setRequiredVenueTag(itemId: String, tag: String?) {
    viewModelScope.launch {
      repository.setRequiredVenueTag(itemId, tag)
      message.value = if (tag != null) "Will only notify for $tag cinemas" else "Will notify for any cinema"
    }
  }

  /** The user picked which film an ambiguous title refers to. */
  fun chooseCandidate(itemId: String, candidate: TitleCandidate) {
    viewModelScope.launch {
      repository.resolveAmbiguity(itemId, candidate)
      message.value = "Set to ${candidate.title}${candidate.year?.let { " ($it)" } ?: ""}"
    }
  }

  /**
   * Settle an ambiguous title from a pasted IMDb or TMDB link, for the cases where none of the
   * offered candidates is the right film.
   */
  /**
   * Look up a pasted link and offer what it found, rather than adopting it silently.
   *
   * A mistyped or mis-copied link resolves to a real film just as happily as the right one, and
   * the whole reason this entry is in the queue is that the app was not sure. So the answer goes
   * back as a candidate to confirm — poster, year and all — and [chooseCandidate] commits it.
   */
  fun previewLink(itemId: String, input: String) {
    if (input.isBlank()) return
    viewModelScope.launch {
      linkPreview.value = null
      try {
        val candidate =
          repository.searchToAdd(input).firstOrNull()
            ?: error("Nothing found for that link.")
        linkPreview.value = candidate.asTitleCandidate(itemId)
      } catch (error: Exception) {
        message.value = error.message ?: "Could not resolve that link"
      }
    }
  }

  fun clearLinkPreview() {
    linkPreview.value = null
  }

  /**
   * A card scrolled into view without a poster yet — fetch just that one.
   *
   * Cheap and idempotent (see [KinoRepository.fetchPosterFor]), so calling it from every card's
   * composition is fine, and means posters load for what is actually on screen rather than in
   * database order from the bottom of the list up.
   */
  fun onPosterNeeded(itemId: String) {
    viewModelScope.launch { repository.fetchPosterFor(itemId) }
  }

  // --- Multi-selection ---

  /** Long-pressing a card starts selecting with just that film checked. */
  fun startSelecting(itemId: String) {
    selectedIds.value = setOf(itemId)
  }

  fun toggleSelected(itemId: String) {
    selectedIds.value =
      if (itemId in selectedIds.value) selectedIds.value - itemId else selectedIds.value + itemId
  }

  fun clearSelection() {
    selectedIds.value = emptySet()
  }

  fun removeSelected() {
    val ids = selectedIds.value
    if (ids.isEmpty()) return
    viewModelScope.launch {
      for (id in ids) repository.removeItem(id)
      message.value = "Removed ${ids.size} film(s). Delete them from the source list too, or they will return."
      selectedIds.value = emptySet()
    }
  }

  /** Sets an explicit mute state on every selected film, overriding whatever each already had. */
  fun muteSelected(muted: Boolean) {
    val ids = selectedIds.value
    if (ids.isEmpty()) return
    viewModelScope.launch {
      for (id in ids) repository.setNotificationsMuted(id, muted)
      message.value = "${if (muted) "Muted" else "Unmuted"} ${ids.size} film(s)"
      selectedIds.value = emptySet()
    }
  }

  /** Identify bare titles now, rather than waiting for the next sync to chip away at them. */
  fun resolveTitlesNow() {
    if (resolveProgress.value != null) return
    viewModelScope.launch {
      resolveProgress.value = 0 to 0
      try {
        val outcome =
          repository.resolveTitles(limit = Int.MAX_VALUE) { done, total ->
            resolveProgress.value = done to total
          }
        message.value =
          "Identified ${outcome.identified}, ${outcome.ambiguous + outcome.series} need a choice"
      } catch (error: Exception) {
        message.value = "Could not identify titles: ${error.message}"
      } finally {
        resolveProgress.value = null
      }
    }
  }

  /**
   * Add films from a pasted list of names, one per line, then identify them — for a watchlist
   * kept somewhere that has no export at all.
   */
  fun addManualTitles(text: String) {
    viewModelScope.launch {
      try {
        val count = repository.addManualTitles(text)
        message.value = "Added $count title(s)"
        identifyThenSync()
      } catch (error: Exception) {
        message.value = error.message ?: "Could not add those titles"
      }
    }
  }

  /** The user says a title we called a TV series is really a film after all. */
  fun keepAsFilm(itemId: String) {
    viewModelScope.launch {
      repository.keepAsFilm(itemId)
      message.value = "Kept as a film — we'll keep watching for it"
    }
  }

  // --- Cinemas & UI ---

  fun setCinemaEnabled(cinemaId: String, enabled: Boolean) {
    viewModelScope.launch { repository.setCinemaEnabled(cinemaId, enabled) }
  }

  fun setCinemaFilter(cinemaId: String?) {
    cinemaFilter.value = cinemaId
  }

  fun toggleShowingSoon() {
    watchlistFilters.value =
      watchlistFilters.value.let { it.copy(showingSoon = !it.showingSoon) }
  }

  fun toggleMutedOnly() {
    watchlistFilters.value = watchlistFilters.value.let { it.copy(mutedOnly = !it.mutedOnly) }
  }

  /** Add or remove one source from the filter; an empty set means every source. */
  fun toggleSourceFilter(sourceId: String) {
    watchlistFilters.value =
      watchlistFilters.value.let { it.copy(sources = it.sources.toggle(sourceId)) }
  }

  fun toggleGenreFilter(genre: String) {
    watchlistFilters.value =
      watchlistFilters.value.let { it.copy(genres = it.genres.toggle(genre)) }
  }

  fun toggleVenueTagFilter(tag: String) {
    watchlistFilters.value =
      watchlistFilters.value.let { it.copy(venueTags = it.venueTags.toggle(tag)) }
  }

  fun clearFilters() {
    watchlistFilters.value = WatchlistFilters()
  }

  private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

  fun setWatchlistQuery(query: String) {
    watchlistQuery.value = query
  }

  /** Steps the sort chip through A–Z, Z–A, Newest, Oldest, Recently added and back round. */
  fun cycleWatchlistSort() {
    watchlistSort.value = watchlistSort.value.next()
  }

  fun sendTestNotification() {
    repository.sendTestNotification()
    message.value = "Test notification sent"
  }

  fun clearMessage() {
    message.value = null
  }

  // Grouping helpers keep the combine() above within its arity limit and readable.
  private data class Filters(
    val cinemaId: String?,
    val watchlist: WatchlistFilters,
    val query: String,
    val sort: WatchlistSort,
  )

  private data class Content(
    val watchlist: List<WatchlistItem>,
    val screenings: List<Screening>,
    val cinemas: List<Cinema>,
    val sources: List<WatchlistSource>,
    val filters: Filters,
  )

  /** Keeps the preferences combine inside its five-flow arity limit. */
  private data class Schedule(
    val autoSync: Boolean,
    val intervalHours: Long,
    val horizonDays: Long,
    val tmdbKey: String,
    val syncHour: Int,
  )

  private data class Prefs(
    val autoSync: Boolean,
    val intervalHours: Long,
    val horizonDays: Long,
    val tmdbKey: String,
    val syncHour: Int,
    val notifications: Boolean,
    val lastSyncAt: Long,
    val summary: String,
  )

  private data class Transient(
    val syncing: Boolean,
    val step: String?,
    val selected: Set<String>,
    val report: SyncReport?,
    val trakt: TraktState,
    val message: String?,
    val progress: Pair<Int, Int>?,
  )

  private data class Review(val entries: List<ReviewEntry>)

  /** A lookup result reshaped as the stored candidate [resolveAmbiguity] expects. */
  private fun TitleLookup.Candidate.asTitleCandidate(itemId: String) =
    TitleCandidate(
      id = "$itemId|$tmdbId",
      watchlistItemId = itemId,
      tmdbId = tmdbId,
      imdbId = imdbId,
      title = title,
      year = year,
      titleType = type,
      posterUrl = posterUrl,
    )
}
