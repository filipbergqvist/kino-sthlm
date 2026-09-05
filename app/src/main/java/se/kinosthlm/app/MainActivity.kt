package se.kinosthlm.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.LocalActivity
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.ui.screens.AddFilmDialog
import se.kinosthlm.app.ui.screens.CinemasScreen
import se.kinosthlm.app.ui.screens.ImdbListDialog
import se.kinosthlm.app.ui.screens.ReviewDialog
import se.kinosthlm.app.ui.screens.ScheduleScreen
import se.kinosthlm.app.ui.screens.SettingsScreen
import se.kinosthlm.app.ui.screens.WatchlistDetailDialog
import se.kinosthlm.app.ui.screens.WatchlistScreen
import se.kinosthlm.app.ui.theme.KinoSthlmTheme
import se.kinosthlm.app.ui.viewmodel.KinoViewModel
import se.kinosthlm.app.ui.viewmodel.WatchlistEntry

class MainActivity : ComponentActivity() {

  private val viewModel: KinoViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // A notification tap asks for the Schedule tab.
    val startTab = if (intent?.hasExtra(EXTRA_FOCUS_MOVIE_ID) == true) TAB_SCHEDULE else TAB_WATCHLIST

    setContent { KinoSthlmTheme { KinoApp(viewModel = viewModel, startTab = startTab) } }
  }

  companion object {
    const val EXTRA_FOCUS_MOVIE_ID = "focus_movie_id"
    const val TAB_WATCHLIST = 0
    const val TAB_SCHEDULE = 1
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KinoApp(viewModel: KinoViewModel, startTab: Int = 0) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val addSearchState by viewModel.addSearchState.collectAsStateWithLifecycle()
  val context = LocalContext.current

  var tab by remember { mutableIntStateOf(startTab) }
  var showImdbListDialog by remember { mutableStateOf(false) }
  var showReviewDialog by remember { mutableStateOf(false) }
  var showAddDialog by remember { mutableStateOf(false) }
  // Bulk mute/unmute overwrites whatever each film was set to individually, and bulk remove is
  // just as blunt, so both ask first.
  var pendingBulkAction by remember { mutableStateOf<BulkAction?>(null) }
  // The id rather than a snapshot, so the popup reflects screenings arriving while it is open
  // instead of freezing the moment it was tapped.
  var detailEntryId by remember { mutableStateOf<String?>(null) }
  val detailEntry: WatchlistEntry? = uiState.watchlist.firstOrNull { it.item.id == detailEntryId }
  val snackbar = remember { SnackbarHostState() }

  /**
   * Android 13+ will not show a single notification until this is granted, which would look
   * exactly like a broken scraper. Ask once, on first launch.
   */
  val notificationPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
  LaunchedEffect(Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }

  // CSV import via the system file picker — no storage permission needed.
  var pendingCsvSource by remember { mutableStateOf(WatchlistItem.SOURCE_IMDB) }
  val pickCsv =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
      if (uri != null) viewModel.importCsv(uri, pendingCsvSource)
    }

  val openUrl: (String) -> Unit = { url ->
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
  }

  LaunchedEffect(uiState.message) {
    uiState.message?.let {
      snackbar.showSnackbar(it)
      viewModel.clearMessage()
    }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      if (uiState.isSelecting) {
        TopAppBar(
          title = { Text("${uiState.selectedIds.size} selected") },
          navigationIcon = {
            IconButton(
              onClick = { viewModel.clearSelection() },
              modifier = Modifier.testTag("cancel_selection"),
            ) {
              Icon(Icons.Default.Close, contentDescription = "Cancel selection")
            }
          },
          actions = {
            IconButton(
              onClick = { pendingBulkAction = BulkAction.MUTE },
              modifier = Modifier.testTag("mute_selected"),
            ) {
              Icon(Icons.Default.NotificationsOff, contentDescription = "Mute selected")
            }
            IconButton(
              onClick = { pendingBulkAction = BulkAction.UNMUTE },
              modifier = Modifier.testTag("unmute_selected"),
            ) {
              Icon(Icons.Outlined.Notifications, contentDescription = "Unmute selected")
            }
            IconButton(
              onClick = { pendingBulkAction = BulkAction.REMOVE },
              modifier = Modifier.testTag("remove_selected"),
            ) {
              Icon(Icons.Default.Delete, contentDescription = "Remove selected")
            }
          },
        )
      } else {
        // No refresh action here: the "films tracked" widget already has a sync button and its
        // own spinner, and two spinners going at once just looked broken.
        TopAppBar(
          title = {
            Column {
              Text("KinoSthlm", style = MaterialTheme.typography.titleMedium)
              Text(
                uiState.lastSyncSummary.ifBlank { "Stockholm cinema tracker" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
        )
      }
    },
    bottomBar = {
      NavigationBar {
        NavigationBarItem(
          selected = tab == 0,
          onClick = { tab = 0 },
          icon = { Icon(Icons.Default.LocalActivity, contentDescription = null) },
          label = { Text("Watchlist") },
          modifier = Modifier.testTag("tab_watchlist"),
        )
        NavigationBarItem(
          selected = tab == 1,
          onClick = { tab = 1 },
          icon = {
            BadgedBox(
              badge = {
                if (uiState.allScreenings.isNotEmpty()) {
                  Badge { Text("${uiState.allScreenings.size}") }
                }
              }
            ) {
              Icon(Icons.Default.EventSeat, contentDescription = null)
            }
          },
          label = { Text("Schedule") },
          modifier = Modifier.testTag("tab_schedule"),
        )
        NavigationBarItem(
          selected = tab == 2,
          onClick = { tab = 2 },
          icon = { Icon(Icons.Default.Business, contentDescription = null) },
          label = { Text("Cinemas") },
          modifier = Modifier.testTag("tab_cinemas"),
        )
        NavigationBarItem(
          selected = tab == 3,
          onClick = { tab = 3 },
          icon = { Icon(Icons.Default.Settings, contentDescription = null) },
          label = { Text("Settings") },
          modifier = Modifier.testTag("tab_settings"),
        )
      }
    },
  ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
      when (tab) {
        0 ->
          WatchlistScreen(
            uiState = uiState,
            onSync = { viewModel.sync() },
            onToggleShowingSoon = { viewModel.toggleShowingSoonOnly() },
            onOpenBooking = openUrl,
            onOpenSources = { tab = 3 },
            onReview = { showReviewDialog = true },
            onAddFilm = { showAddDialog = true },
            onOpenDetail = { detailEntryId = it.item.id },
            onQueryChange = { viewModel.setWatchlistQuery(it) },
            onCycleSort = { viewModel.cycleWatchlistSort() },
            onToggleSortDirection = { viewModel.toggleWatchlistSortDirection() },
            onStartSelecting = { viewModel.startSelecting(it) },
            onToggleSelected = { viewModel.toggleSelected(it) },
            onPosterNeeded = { viewModel.onPosterNeeded(it) },
          )
        1 ->
          ScheduleScreen(
            uiState = uiState,
            onSelectCinemaFilter = { viewModel.setCinemaFilter(it) },
            onOpenBooking = openUrl,
          )
        2 ->
          CinemasScreen(
            uiState = uiState,
            onToggle = { id, enabled -> viewModel.setCinemaEnabled(id, enabled) },
            onOpenWebsite = openUrl,
          )
        3 ->
          SettingsScreen(
            uiState = uiState,
            onConnectTrakt = { viewModel.connectTrakt() },
            onCancelTrakt = { viewModel.cancelTraktConnect() },
            onDisconnectTrakt = { viewModel.disconnectTrakt() },
            onImportImdbCsv = {
              pendingCsvSource = WatchlistItem.SOURCE_IMDB
              pickCsv.launch(CSV_MIME_TYPES)
            },
            onImportGoogleTvCsv = {
              pendingCsvSource = WatchlistItem.SOURCE_GOOGLE_TV
              pickCsv.launch(CSV_MIME_TYPES)
            },
            onImportImdbList = { showImdbListDialog = true },
            onSetAutoSync = { viewModel.setAutoSync(it) },
            onSetInterval = { viewModel.setSyncInterval(it) },
            onSetHorizon = { viewModel.setHorizonDays(it) },
            onSetNotifications = { viewModel.setNotificationsEnabled(it) },
            onSyncNow = { viewModel.sync() },
            onResolveTitles = { viewModel.resolveTitlesNow() },
            onTestNotification = { viewModel.sendTestNotification() },
            onOpenUrl = openUrl,
          )
      }
    }
  }

  if (showAddDialog) {
    AddFilmDialog(
      searchState = addSearchState,
      onSearch = { viewModel.searchToAdd(it) },
      onAdd = { viewModel.addCandidate(it) },
      onDismiss = {
        showAddDialog = false
        viewModel.clearAddSearch()
      },
    )
  }
  pendingBulkAction?.let { action ->
    val count = uiState.selectedIds.size
    AlertDialog(
      onDismissRequest = { pendingBulkAction = null },
      title = { Text("${action.verb} $count film${if (count == 1) "" else "s"}?") },
      text = { Text(action.explanation) },
      confirmButton = {
        TextButton(
          onClick = {
            when (action) {
              BulkAction.MUTE -> viewModel.muteSelected(true)
              BulkAction.UNMUTE -> viewModel.muteSelected(false)
              BulkAction.REMOVE -> viewModel.removeSelected()
            }
            pendingBulkAction = null
          },
          modifier = Modifier.testTag("confirm_bulk"),
        ) {
          Text(action.verb)
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingBulkAction = null }) { Text("Cancel") }
      },
    )
  }
  if (showReviewDialog) {
    ReviewDialog(
      entries = uiState.needsReview,
      onChoose = { itemId, candidate -> viewModel.chooseCandidate(itemId, candidate) },
      onResolveByLink = { itemId, link -> viewModel.resolveByLink(itemId, link) },
      onRemove = { viewModel.removeFilm(it) },
      onDismiss = { showReviewDialog = false },
    )
  }
  if (showImdbListDialog) {
    ImdbListDialog(
      onDismiss = { showImdbListDialog = false },
      onFetch = { viewModel.importImdbList(it) },
    )
  }
  detailEntry?.let { entry ->
    WatchlistDetailDialog(
      entry = entry,
      notificationsEnabled = uiState.notificationsEnabled,
      onOpenImdb = openUrl,
      onRemove = { viewModel.removeFilm(it) },
      onTogglePin = { id, pinned -> viewModel.togglePin(id, pinned) },
      onToggleMute = { id, muted -> viewModel.toggleMute(id, muted) },
      onSetRequiredVenueTag = { id, tag -> viewModel.setRequiredVenueTag(id, tag) },
      onDismiss = { detailEntryId = null },
    )
  }
}

/** A bulk action waiting on confirmation, with the wording its prompt uses. */
private enum class BulkAction(val verb: String, val explanation: String) {
  MUTE("Mute", "These films stay on your list and keep matching, but stop notifying."),
  UNMUTE("Unmute", "This overrides whatever each of these films was set to individually."),
  REMOVE(
    "Remove",
    "They stay hidden even if a connected list still has them. Remove them upstream too and " +
      "they go for good.",
  ),
}

/**
 * Exports are `.csv`, but file managers and cloud providers label them inconsistently — some
 * hand back `text/plain`, some `application/octet-stream`. Accept all three or the picker greys
 * out the file the user is trying to select.
 */
private val CSV_MIME_TYPES =
  arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")
