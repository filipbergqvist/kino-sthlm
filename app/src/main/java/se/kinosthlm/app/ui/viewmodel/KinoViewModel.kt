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

/** One ambiguous title and the films it could be, for the review sheet. */
data class ReviewEntry(
  val item: WatchlistItem,
  val candidates: List<TitleCandidate>,
)

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
  val isSyncing: Boolean = false,
  val syncStep: String? = null,
  val lastReport: SyncReport? = null,
  val lastSyncAt: Long = 0L,
  val lastSyncSummary: String = "",
  val autoSyncEnabled: Boolean = true,
  val syncIntervalHours: Long = SettingsStore.DEFAULT_INTERVAL_HOURS,
  val notificationsEnabled: Boolean = true,
  val cinemaFilter: String? = null,
  val showingSoonOnly: Boolean = false,
  /** Ambiguous titles waiting for the user to pick the right film. */
  val needsReview: List<ReviewEntry> = emptyList(),
  /** TV series found in the watchlist; hidden from the list because they never play in cinemas. */
  val seriesCount: Int = 0,
  val isResolving: Boolean = false,
  val resolveProgress: Pair<Int, Int>? = null,
  val traktState: TraktState = TraktState.Disconnected,
  val traktConfigured: Boolean = false,
  val message: String? = null,
) {
  val failedSources: List<SourceResult> get() = lastReport?.failedSources.orEmpty()
}

class KinoViewModel(application: Application) : AndroidViewModel(application) {

  private val repository = KinoRepository.getInstance(application)
  private val settings = SettingsStore(application)

  private val isSyncing = MutableStateFlow(false)
  private val syncStep = MutableStateFlow<String?>(null)
  private val lastReport = MutableStateFlow<SyncReport?>(null)
  private val cinemaFilter = MutableStateFlow<String?>(null)
  private val showingSoonOnly = MutableStateFlow(false)
  private val traktState = MutableStateFlow<TraktState>(TraktState.Disconnected)
  private val message = MutableStateFlow<String?>(null)
  private val resolveProgress = MutableStateFlow<Pair<Int, Int>?>(null)

  private var traktJob: Job? = null

  val uiState: StateFlow<UiState> =
    combine(
        combine(
          repository.watchlist,
          repository.upcomingScreenings,
          repository.cinemas,
          repository.watchlistSources,
          combine(cinemaFilter, showingSoonOnly) { filter, soonOnly -> filter to soonOnly },
        ) { watchlist, screenings, cinemas, sources, filters ->
          Content(watchlist, screenings, cinemas, sources, filters.first, filters.second)
        },
        combine(
          settings.autoSyncEnabled,
          settings.syncIntervalHours,
          settings.notificationsEnabled,
          settings.lastSyncAt,
          settings.lastSyncSummary,
        ) { auto, interval, notifications, lastAt, summary ->
          Prefs(auto, interval, notifications, lastAt, summary)
        },
        combine(
          combine(isSyncing, syncStep) { syncing, step -> syncing to step },
          lastReport,
          traktState,
          message,
          resolveProgress,
        ) { sync, report, trakt, msg, progress ->
          Transient(sync.first, sync.second, report, trakt, msg, progress)
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
        val entries =
          content.watchlist
            // Series never play in cinemas, ambiguous titles would match the wrong film, and
            // suppressed ones the user deleted. All three are handled elsewhere rather than
            // cluttering the list.
            .filter { it.isMatchable }
            .map {
              WatchlistEntry(
                item = it,
                screenings = byMovie[it.id].orEmpty(),
                sources = sourcesByItem[it.id].orEmpty().map { row -> row.sourceId },
              )
            }
            .let { if (content.soonOnly) it.filter { entry -> entry.nextScreening != null } else it }

        UiState(
          watchlist = entries,
          screenings =
            content.filter?.let { id -> content.screenings.filter { it.cinemaId == id } }
              ?: content.screenings,
          allScreenings = content.screenings,
          cinemas = content.cinemas,
          isSyncing = transient.syncing,
          syncStep = transient.step,
          lastReport = transient.report,
          lastSyncAt = prefs.lastSyncAt,
          lastSyncSummary = prefs.summary,
          autoSyncEnabled = prefs.autoSync,
          syncIntervalHours = prefs.intervalHours,
          notificationsEnabled = prefs.notifications,
          cinemaFilter = content.filter,
          showingSoonOnly = content.soonOnly,
          traktState = transient.trakt,
          traktConfigured = repository.trakt.isConfigured,
          needsReview = review.entries,
          seriesCount = review.seriesCount,
          isResolving = transient.progress != null,
          resolveProgress = transient.progress,
          message = transient.message,
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

  /** Add a film by hand from an IMDb link, which identifies it exactly. */
  fun addByImdbLink(input: String) {
    if (input.isBlank()) return
    viewModelScope.launch {
      try {
        val title = repository.addByImdbLink(input)
        message.value = "Added $title"
        sync()
      } catch (error: Exception) {
        message.value = error.message ?: "Could not add that film"
      }
    }
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

  /** The user picked which film an ambiguous title refers to. */
  fun chooseCandidate(itemId: String, candidate: TitleCandidate) {
    viewModelScope.launch {
      repository.resolveAmbiguity(itemId, candidate)
      message.value = "Set to ${candidate.title}${candidate.year?.let { " ($it)" } ?: ""}"
    }
  }

  /** The user says an entry is a TV series, so it should stop appearing. */
  fun markAsSeries(itemId: String) {
    viewModelScope.launch {
      repository.markAsSeries(itemId)
      message.value = "Hidden as a TV series"
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

  fun sendTestNotification() {
    repository.sendTestNotification()
    message.value = "Test notification sent"
  }

  fun clearMessage() {
    message.value = null
  }

  // Grouping helpers keep the combine() above within its arity limit and readable.
  private data class Content(
    val watchlist: List<WatchlistItem>,
    val screenings: List<Screening>,
    val cinemas: List<Cinema>,
    val sources: List<WatchlistSource>,
    val filter: String?,
    val soonOnly: Boolean,
  )

  private data class Prefs(
    val autoSync: Boolean,
    val intervalHours: Long,
    val notifications: Boolean,
    val lastSyncAt: Long,
    val summary: String,
  )

  private data class Transient(
    val syncing: Boolean,
    val step: String?,
    val report: SyncReport?,
    val trakt: TraktState,
    val message: String?,
    val progress: Pair<Int, Int>?,
  )

  private data class Review(val entries: List<ReviewEntry>, val seriesCount: Int)
}
