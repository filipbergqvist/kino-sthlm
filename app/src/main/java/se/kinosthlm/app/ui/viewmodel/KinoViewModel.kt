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

/** How the watchlist is ordered. Newly added, alphabetical, or by release year. */
enum class WatchlistSort {
  ADDED,
  ALPHABETICAL,
  YEAR;

  /**
   * The direction that reads as "normal" for this field, applied when you switch to it: newest
   * first for dates, A first for names. Flipping from there is one tap.
   */
  val defaultDescending: Boolean
    get() = this != ALPHABETICAL
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
  val watchlistQuery: String = "",
  val watchlistSort: WatchlistSort = WatchlistSort.ADDED,
  val watchlistSortDescending: Boolean = true,
  /** Ambiguous titles waiting for the user to pick the right film. */
  val needsReview: List<ReviewEntry> = emptyList(),
  /** TV series found in the watchlist; hidden from the list because they never play in cinemas. */
  val seriesCount: Int = 0,
  val isResolving: Boolean = false,
  val resolveProgress: Pair<Int, Int>? = null,
  val traktState: TraktState = TraktState.Disconnected,
  val traktConfigured: Boolean = false,
  val tmdbConfigured: Boolean = false,
  val message: String? = null,
  /** Films selected for a bulk action, entered with a long press. Empty means not selecting. */
  val selectedIds: Set<String> = emptySet(),
) {
  val failedSources: List<SourceResult> get() = lastReport?.failedSources.orEmpty()
  val isSelecting: Boolean get() = selectedIds.isNotEmpty()
}

class KinoViewModel(application: Application) : AndroidViewModel(application) {

  private val repository = KinoRepository.getInstance(application)
  private val settings = SettingsStore(application)

  private val isSyncing = MutableStateFlow(false)
  private val syncStep = MutableStateFlow<String?>(null)
  private val lastReport = MutableStateFlow<SyncReport?>(null)
  private val cinemaFilter = MutableStateFlow<String?>(null)
  private val showingSoonOnly = MutableStateFlow(false)
  private val watchlistQuery = MutableStateFlow("")
  private val watchlistSort = MutableStateFlow(WatchlistSort.ADDED)
  private val watchlistSortDescending = MutableStateFlow(WatchlistSort.ADDED.defaultDescending)
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
          combine(cinemaFilter, showingSoonOnly, watchlistQuery, watchlistSort, watchlistSortDescending) {
            filter,
            soonOnly,
            query,
            sort,
            descending ->
            Filters(filter, soonOnly, query, sort, descending)
          },
        ) { watchlist, screenings, cinemas, sources, filters ->
          Content(watchlist, screenings, cinemas, sources, filters)
        },
        combine(
          combine(settings.autoSyncEnabled, settings.syncIntervalHours, settings.horizonDays) {
            auto,
            interval,
            horizon ->
            Triple(auto, interval, horizon)
          },
          settings.notificationsEnabled,
          settings.lastSyncAt,
          settings.lastSyncSummary,
        ) { schedule, notifications, lastAt, summary ->
          Prefs(schedule.first, schedule.second, schedule.third, notifications, lastAt, summary)
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
        combine(repository.needingReview, repository.reviewCandidates, repository.seriesCount) {
          review,
          candidates,
          series ->
          val byItem = candidates.groupBy { it.watchlistItemId }
          Review(review.map { ReviewEntry(it, byItem[it.id].orEmpty()) }, series)
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
              content.filters.query.trim().takeIf { it.isNotEmpty() }?.let { query ->
                list.filter { entry -> entry.item.title.contains(query, ignoreCase = true) }
              } ?: list
            }
            .let { list ->
              val ascending =
                when (content.filters.sort) {
                  WatchlistSort.ADDED -> list.sortedBy { it.item.addedAt }
                  WatchlistSort.ALPHABETICAL -> list.sortedBy { it.item.title.lowercase() }
                  WatchlistSort.YEAR -> list.sortedBy { it.item.year ?: 0 }
                }
              if (content.filters.sortDescending) ascending.reversed() else ascending
            }

        UiState(
          watchlist = entries,
          screenings =
            content.filters.cinemaId?.let { id -> content.screenings.filter { it.cinemaId == id } }
              ?: content.screenings,
          allScreenings = content.screenings,
          cinemas = content.cinemas,
          hasFilms = matchable.isNotEmpty(),
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
          watchlistQuery = content.filters.query,
          watchlistSort = content.filters.sort,
          watchlistSortDescending = content.filters.sortDescending,
          traktState = transient.trakt,
          traktConfigured = repository.trakt.isConfigured,
          tmdbConfigured = repository.tmdbConfigured,
          needsReview = review.entries,
          seriesCount = review.seriesCount,
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
            sync()
          } else {
            traktState.value = TraktState.Disconnected
            message.value = "Trakt code expired. Try again."
          }
        } catch (error: Exception) {
          traktState.value = TraktState.Disconnected
          message.value = "Trakt: ${error.message}"
        }
      }
  }

  fun cancelTraktConnect() {
    traktJob?.cancel()
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
        sync()
      } catch (error: Exception) {
        message.value = error.message ?: "Import failed"
      }
    }
  }

  fun importImdbList(url: String) {
    viewModelScope.launch {
      try {
        val count = repository.importImdbPublicList(url)
        message.value = "Imported $count films from IMDb"
        sync()
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
          "Identified ${outcome.identified}, ${outcome.series} series hidden, " +
            "${outcome.ambiguous} need a choice"
      } catch (error: Exception) {
        message.value = "Could not identify titles: ${error.message}"
      } finally {
        resolveProgress.value = null
      }
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

  fun setWatchlistQuery(query: String) {
    watchlistQuery.value = query
  }

  /**
   * Cycles Added → A–Z → Year → Added, for the sort chip in the watchlist header. Each field
   * arrives in its natural direction; flipping it is the separate arrow button.
   */
  fun cycleWatchlistSort() {
    val next =
      when (watchlistSort.value) {
        WatchlistSort.ADDED -> WatchlistSort.ALPHABETICAL
        WatchlistSort.ALPHABETICAL -> WatchlistSort.YEAR
        WatchlistSort.YEAR -> WatchlistSort.ADDED
      }
    watchlistSort.value = next
    watchlistSortDescending.value = next.defaultDescending
  }

  fun toggleWatchlistSortDirection() {
    watchlistSortDescending.value = !watchlistSortDescending.value
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
    val sortDescending: Boolean,
  )

  private data class Content(
    val watchlist: List<WatchlistItem>,
    val screenings: List<Screening>,
    val cinemas: List<Cinema>,
    val sources: List<WatchlistSource>,
    val filters: Filters,
  )

  private data class Prefs(
    val autoSync: Boolean,
    val intervalHours: Long,
    val horizonDays: Long,
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

  private data class Review(val entries: List<ReviewEntry>, val seriesCount: Int)

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
