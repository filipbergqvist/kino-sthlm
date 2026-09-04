package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.Cinema
import com.example.data.model.NotificationLog
import com.example.data.model.ScanReport
import com.example.data.model.Screening
import com.example.data.model.WatchlistItem
import com.example.data.repository.CinemaWatchlistRepository
import com.example.data.service.WatchlistService
import com.example.worker.CinemaPollingWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WatchlistItemWithScreening(
    val item: WatchlistItem,
    val nextScreening: Screening? = null,
    val allUpcomingScreenings: List<Screening> = emptyList()
)

data class UiState(
    val watchlistWithScreenings: List<WatchlistItemWithScreening> = emptyList(),
    val screenings: List<Screening> = emptyList(),
    val cinemas: List<Cinema> = emptyList(),
    val notificationLogs: List<NotificationLog> = emptyList(),
    val isScanning: Boolean = false,
    val lastScanReport: ScanReport? = null,
    val isAutoPollingEnabled: Boolean = true,
    val pollingIntervalHours: Long = 2,
    val selectedCinemaFilter: String? = null, // null = all
    val filterShowingSoonOnly: Boolean = false,
    val isImporting: Boolean = false,
    val statusMessage: String? = null
)

class CinemaWatchlistViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CinemaWatchlistRepository.getInstance(application)
    private val watchlistService = WatchlistService()

    private val _isScanning = MutableStateFlow(false)
    private val _lastScanReport = MutableStateFlow<ScanReport?>(null)
    private val _isAutoPollingEnabled = MutableStateFlow(true)
    private val _pollingIntervalHours = MutableStateFlow(2L)
    private val _selectedCinemaFilter = MutableStateFlow<String?>(null)
    private val _filterShowingSoonOnly = MutableStateFlow(false)
    private val _isImporting = MutableStateFlow(false)
    private val _statusMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<UiState> = combine(
        repository.watchlist,
        repository.upcomingScreenings,
        repository.cinemas,
        repository.notificationLogs,
        _isScanning,
        _lastScanReport,
        _selectedCinemaFilter,
        _filterShowingSoonOnly,
        _isImporting,
        _statusMessage
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val watchlist = args[0] as List<WatchlistItem>
        @Suppress("UNCHECKED_CAST")
        val screenings = args[1] as List<Screening>
        @Suppress("UNCHECKED_CAST")
        val cinemas = args[2] as List<Cinema>
        @Suppress("UNCHECKED_CAST")
        val notifications = args[3] as List<NotificationLog>
        val isScanning = args[4] as Boolean
        val lastReport = args[5] as ScanReport?
        val cinemaFilter = args[6] as String?
        val filterShowingSoon = args[7] as Boolean
        val isImporting = args[8] as Boolean
        val statusMessage = args[9] as String?

        // Pair each watchlist item with its upcoming Stockholm screenings
        val enrichedWatchlist = watchlist.map { item ->
            val movieScreenings = screenings.filter { it.watchlistMovieId == item.id }
            WatchlistItemWithScreening(
                item = item,
                nextScreening = movieScreenings.firstOrNull(),
                allUpcomingScreenings = movieScreenings
            )
        }

        val filteredScreenings = if (cinemaFilter != null) {
            screenings.filter { it.cinemaId == cinemaFilter }
        } else {
            screenings
        }

        UiState(
            watchlistWithScreenings = if (filterShowingSoon) enrichedWatchlist.filter { it.nextScreening != null } else enrichedWatchlist,
            screenings = filteredScreenings,
            cinemas = cinemas,
            notificationLogs = notifications,
            isScanning = isScanning,
            lastScanReport = lastReport,
            isAutoPollingEnabled = _isAutoPollingEnabled.value,
            pollingIntervalHours = _pollingIntervalHours.value,
            selectedCinemaFilter = cinemaFilter,
            filterShowingSoonOnly = filterShowingSoon,
            isImporting = isImporting,
            statusMessage = statusMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UiState()
    )

    init {
        // Initialize background automated polling & load defaults if empty
        viewModelScope.launch {
            CinemaPollingWorker.schedulePeriodic(getApplication(), _pollingIntervalHours.value)
            // If first launch, load initial preset and scan
            val current = repository.watchlist
            launch {
                current.collect { list ->
                    if (list.isEmpty()) {
                        repository.loadSamplePreset()
                        scanStockholmCinemas()
                    }
                }
            }
        }
    }

    fun scanStockholmCinemas() {
        viewModelScope.launch {
            _isScanning.value = true
            _statusMessage.value = "Scanning Stockholm cinema schedules..."
            try {
                val report = repository.scanStockholmCinemas()
                _lastScanReport.value = report
                _statusMessage.value = report.statusMessage
            } catch (e: Exception) {
                _statusMessage.value = "Scan error: ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun importImdb(identifierOrUrl: String) {
        viewModelScope.launch {
            _isImporting.value = true
            _statusMessage.value = "Fetching IMDb watchlist..."
            try {
                val count = repository.importImdbWatchlist(identifierOrUrl)
                if (count > 0) {
                    _statusMessage.value = "Successfully imported $count titles from IMDb!"
                    scanStockholmCinemas()
                } else {
                    _statusMessage.value = "No titles found. Check if your IMDb watchlist is set to Public."
                }
            } catch (e: Exception) {
                _statusMessage.value = "IMDb sync failed: ${e.message}"
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun importGoogleTv(textInput: String) {
        viewModelScope.launch {
            _isImporting.value = true
            _statusMessage.value = "Importing Google TV watchlist..."
            try {
                val count = repository.importGoogleTvWatchlist(textInput)
                if (count > 0) {
                    _statusMessage.value = "Successfully imported $count titles from Google TV!"
                    scanStockholmCinemas()
                } else {
                    _statusMessage.value = "Please enter one or more movie titles to import."
                }
            } catch (e: Exception) {
                _statusMessage.value = "Import failed: ${e.message}"
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun addCustomMovie(title: String, year: Int? = null, director: String? = null, source: String = "Manual") {
        if (title.isBlank()) return
        viewModelScope.launch {
            val item = WatchlistItem(
                id = "custom_${System.currentTimeMillis()}",
                title = title.trim(),
                year = year,
                director = director?.trim(),
                source = source
            )
            repository.addWatchlistItem(item)
            _statusMessage.value = "Added '${item.title}' to watchlist"
            scanStockholmCinemas()
        }
    }

    fun addFromCatalog(item: WatchlistItem) {
        viewModelScope.launch {
            repository.addWatchlistItem(item)
            _statusMessage.value = "Added '${item.title}' to watchlist"
            scanStockholmCinemas()
        }
    }

    fun removeMovie(id: String) {
        viewModelScope.launch {
            repository.removeWatchlistItem(id)
            _statusMessage.value = "Removed from watchlist"
        }
    }

    fun setCinemaEnabled(cinemaId: String, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setCinemaEnabled(cinemaId, isEnabled)
            scanStockholmCinemas()
        }
    }

    fun setCinemaFilter(cinemaId: String?) {
        _selectedCinemaFilter.value = cinemaId
    }

    fun toggleShowingSoonOnly() {
        _filterShowingSoonOnly.value = !_filterShowingSoonOnly.value
    }

    fun toggleAutoPolling(enabled: Boolean) {
        _isAutoPollingEnabled.value = enabled
        if (enabled) {
            CinemaPollingWorker.schedulePeriodic(getApplication(), _pollingIntervalHours.value)
            _statusMessage.value = "Automated polling enabled (${_pollingIntervalHours.value}h interval)"
        } else {
            CinemaPollingWorker.cancelPeriodic(getApplication())
            _statusMessage.value = "Automated polling disabled"
        }
    }

    fun setPollingInterval(hours: Long) {
        _pollingIntervalHours.value = hours
        if (_isAutoPollingEnabled.value) {
            CinemaPollingWorker.schedulePeriodic(getApplication(), hours)
            _statusMessage.value = "Polling interval set to $hours hours"
        }
    }

    fun sendTestNotification() {
        repository.sendTestNotification()
        _statusMessage.value = "Dispatched test push notification!"
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun getSearchableCatalog(): List<WatchlistItem> {
        return watchlistService.searchableCatalog
    }
}
