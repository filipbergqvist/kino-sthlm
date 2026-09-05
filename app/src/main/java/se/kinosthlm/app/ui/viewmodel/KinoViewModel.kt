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
  val isSyncing: Boolean = false,
  val syncStep: String? = null,
  val lastReport: SyncReport? = null,
  val lastSyncAt: Long = 0L,
  val lastSyncSummary: String = "",
  val autoSyncEnabled: Boolean = true,
  val syncIntervalHours: Long = SettingsStore.DEFAULT_INTERVAL_HOURS,
  val horizonDays: Long = SettingsStore.DEFAULT_HORIZON_DAYS,
  val notificationsEnabled: Boolean = true,
  val cinemaFilter: String? = null,
  val showingSoonOnly: Boolean = false,
  /** Show only films contributed by this source id; null means every source. */
  val sourceFilter: String? = null,
  /** Show only films TMDB puts in this genre; null means every genre. */
  val genreFilter: String? = null,
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

  /** How many of the tucked-away filters are on, so the chip can say so without opening. */
  val activeFilterCount: Int
    get() = listOfNotNull(sourceFilter, genreFilter).size
}

class KinoViewModel(application: Application) : AndroidViewModel(application) {

  private val repository = KinoRepository.getInstance(application)
  private val settings = SettingsStore(application)

  private val isSyncing = MutableStateFlow(false)
  private val syncStep = MutableStateFlow<String?>(null)
  private val lastReport = MutableStateFlow<SyncReport?>(null)
  private val cinemaFilter = MutableStateFlow<String?>(null)
  private val showingSoonOnly = MutableStateFlow(false)
  private val sourceFilter = MutableStateFlow<String?>(null)
  private val genreFilter = MutableStateFlow<String?>(null)
  private val watchlistQuery = MutableStateFlow("")
  private val watchlistSort = MutableStateFlow(WatchlistSort.RECENTLY_ADDED)
  private val traktState = MutableStateFlow<TraktState>(TraktState.Disconnected)
  private val message = MutableStateFlow<String?>(null)
  private val resolveProgress = MutableStateFlow<Pair<Int, Int>?>(null)
  private val selectedIds = MutableStateFlow<Set<String>>(emptySet())
  private val addSearchResults = MutableStateFlow<List<TitleLookup.Candidate>>(emptyList())
  private val addSearching = MutableStateFlow(false)
  private val addSearchError = MutableStateFlow<String?>(null)

  val addSearchState: StateFlow<AddSearchState> =
    combine(addSearchResults, addSearching, addSearchError) { results, searching, error ->
      AddSearchState(results, searching, error)
    }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddSearchState())

  private var traktJob: Job? = null
  private var addSearchJob: Job? = null

  val uiState: StateFlow<UiState> =
    combine(
        combine(
          repository.watchlist,
          repository.upcomingScreenings,
          repository.cinemas,
          repository.watchlistSources,
          combine(
            cinemaFilter,
            showingSoonOnly,
            watchlistQuery,
            watchlistSort,
            combine(sourceFilter, genreFilter) { source, genre -> source to genre },
          ) { filter, soonOnly, query, sort, narrowing ->
            Filters(filter, soonOnly, query, sort, narrowing.first, narrowing.second)
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
          ) { auto, interval, horizon, tmdbKey ->
            Schedule(auto, interval, horizon, tmdbKey)
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
        val matchable = content.watchlist.filter { it.isMatchable }
        val entries =
          matchable
            .map {
              WatchlistEntry(
                item = it,
                screenings = byMovie[it.id].orEmpty(),
                sources = sourcesByItem[it.id].orEmpty().map { row -> row.sourceId },
              )
            }
            .let {
              if (content.filters.soonOnly) it.filter { entry -> entry.nextScreening != null }
              else it
            }
            .let { list ->
              // "Manual Add" is the absence of a real source rather than a row of its own for
              // films added by hand and never claimed by a list, so match it that way too.
              content.filters.source?.let { source ->
                list.filter { entry ->
                  source in entry.sources ||
                    (source == WatchlistItem.SOURCE_MANUAL && entry.realSources.isEmpty())
                }
              } ?: list
            }
            .let { list ->
              content.filters.genre?.let { genre ->
                list.filter { entry -> genre in entry.item.genreList }
              } ?: list
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
          isSyncing = transient.syncing,
          syncStep = transient.step,
          lastReport = transient.report,
          lastSyncAt = prefs.lastSyncAt,
          lastSyncSummary = prefs.summary,
          autoSyncEnabled = prefs.autoSync,
          syncIntervalHours = prefs.intervalHours,
          horizonDays = prefs.horizonDays,
          notificationsEnabled = prefs.notifications,
          cinemaFilter = content.filters.cinemaId,
          showingSoonOnly = content.filters.soonOnly,
          sourceFilter = content.filters.source,
          genreFilter = content.filters.genre,
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
        SyncWorker.schedulePeriodic(getApplication(), settings.currentIntervalHours())
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
        SyncWorker.schedulePeriodic(getApplication(), settings.currentIntervalHours())
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
        SyncWorker.schedulePeriodic(getApplication(), hours)
      }
      message.value = "Syncing every ${hours}h"
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
  fun resolveByLink(itemId: String, input: String) {
    if (input.isBlank()) return
    viewModelScope.launch {
      try {
        val candidate =
          repository.searchToAdd(input).firstOrNull()
            ?: error("Nothing found for that link.")
        repository.resolveAmbiguity(itemId, candidate.asTitleCandidate(itemId))
        message.value = "Set to ${candidate.title}${candidate.year?.let { " ($it)" } ?: ""}"
      } catch (error: Exception) {
        message.value = error.message ?: "Could not resolve that link"
      }
    }
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

  fun toggleShowingSoonOnly() {
    showingSoonOnly.value = !showingSoonOnly.value
  }

  /** Show only films from one watchlist source; null shows every source. */
  fun setSourceFilter(sourceId: String?) {
    sourceFilter.value = sourceId
  }

  /** Show only films in one TMDB genre; null shows every genre. */
  fun setGenreFilter(genre: String?) {
    genreFilter.value = genre
  }

  fun clearFilters() {
    sourceFilter.value = null
    genreFilter.value = null
  }

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
    val soonOnly: Boolean,
    val query: String,
    val sort: WatchlistSort,
    val source: String? = null,
    val genre: String? = null,
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
  )

  private data class Prefs(
    val autoSync: Boolean,
    val intervalHours: Long,
    val horizonDays: Long,
    val tmdbKey: String,
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
