package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.LocalActivity
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.screens.AddMovieDialog
import com.example.ui.screens.CinemasScreen
import com.example.ui.screens.GoogleTvImportDialog
import com.example.ui.screens.ImdbImportDialog
import com.example.ui.screens.ScreeningsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.WatchlistScreen
import com.example.ui.theme.MinimalAlert
import com.example.ui.theme.MinimalAlertContainer
import com.example.ui.theme.MinimalBadgeContainer
import com.example.ui.theme.MinimalBadgeText
import com.example.ui.theme.MinimalBorder
import com.example.ui.theme.MinimalBorderSubtle
import com.example.ui.theme.MinimalDarkBg
import com.example.ui.theme.MinimalOnPrimary
import com.example.ui.theme.MinimalPrimary
import com.example.ui.theme.MinimalPrimaryContainer
import com.example.ui.theme.MinimalSurface
import com.example.ui.theme.MinimalSurfaceElevated
import com.example.ui.theme.MinimalSurfaceVariant
import com.example.ui.theme.MinimalTextMuted
import com.example.ui.theme.MinimalTextPrimary
import com.example.ui.theme.MinimalTextSecondary
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.CinemaWatchlistViewModel

class MainActivity : ComponentActivity() {

  private val viewModel: CinemaWatchlistViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      MyApplicationTheme {
        CinemaAppContent(viewModel = viewModel)
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CinemaAppContent(viewModel: CinemaWatchlistViewModel) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  var currentTab by remember { mutableIntStateOf(0) }

  var showAddDialog by remember { mutableStateOf(false) }
  var showImdbDialog by remember { mutableStateOf(false) }
  var showGoogleTvDialog by remember { mutableStateOf(false) }

  val snackbarHostState = remember { SnackbarHostState() }

  // Listen for status message updates
  LaunchedEffect(uiState.statusMessage) {
    uiState.statusMessage?.let { msg ->
      snackbarHostState.showSnackbar(msg)
      viewModel.clearStatusMessage()
    }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = MinimalDarkBg,
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopAppBar(
        title = {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(38.dp)
                .background(MinimalPrimary, RoundedCornerShape(19.dp)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                Icons.Default.Movie,
                contentDescription = null,
                tint = MinimalOnPrimary,
                modifier = Modifier.size(22.dp)
              )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
              Text(
                text = "CineSync",
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                letterSpacing = (-0.2).sp,
                color = MinimalTextPrimary
              )
              Text(
                text = "Stockholm • Connected",
                fontSize = 11.sp,
                color = MinimalTextSecondary
              )
            }
          }
        },
        actions = {
          if (uiState.isScanning) {
            CircularProgressIndicator(
              modifier = Modifier
                .padding(end = 12.dp)
                .size(20.dp),
              color = MinimalPrimary,
              strokeWidth = 2.dp
            )
          } else {
            IconButton(
              onClick = { viewModel.scanStockholmCinemas() },
              modifier = Modifier
                .testTag("top_bar_refresh_btn")
                .background(MinimalSurfaceElevated, RoundedCornerShape(20.dp))
                .size(40.dp)
            ) {
              Icon(
                Icons.Default.Refresh,
                contentDescription = "Scan Stockholm Cinemas",
                tint = MinimalTextPrimary,
                modifier = Modifier.size(20.dp)
              )
            }
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MinimalDarkBg,
          titleContentColor = MinimalTextPrimary
        ),
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
      )
    },
    bottomBar = {
      Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MinimalSurfaceElevated,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
      ) {
        NavigationBar(
          containerColor = Color.Transparent,
          contentColor = MinimalTextPrimary,
          tonalElevation = 0.dp
        ) {
          val screeningsCount = uiState.screenings.size

          // Tab 0: Watchlist
          NavigationBarItem(
            selected = currentTab == 0,
            onClick = { currentTab = 0 },
            icon = {
              Icon(Icons.Default.LocalActivity, contentDescription = "Watchlist")
            },
            label = { Text("Watchlist", fontSize = 10.sp, fontWeight = FontWeight.Medium) },
            colors = NavigationBarItemDefaults.colors(
              selectedIconColor = MinimalPrimary,
              selectedTextColor = MinimalPrimary,
              indicatorColor = MinimalPrimaryContainer,
              unselectedIconColor = MinimalTextSecondary,
              unselectedTextColor = MinimalTextSecondary
            ),
            modifier = Modifier.testTag("nav_tab_watchlist")
          )

          // Tab 1: Screenings in Stockholm
          NavigationBarItem(
            selected = currentTab == 1,
            onClick = { currentTab = 1 },
            icon = {
              if (screeningsCount > 0) {
                BadgedBox(
                  badge = {
                    Badge(containerColor = MinimalAlert, contentColor = MinimalAlertContainer) {
                      Text(screeningsCount.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                  }
                ) {
                  Icon(Icons.Default.EventSeat, contentDescription = "Screenings")
                }
              } else {
                Icon(Icons.Default.EventSeat, contentDescription = "Screenings")
              }
            },
            label = { Text("Schedule", fontSize = 10.sp, fontWeight = FontWeight.Medium) },
            colors = NavigationBarItemDefaults.colors(
              selectedIconColor = MinimalPrimary,
              selectedTextColor = MinimalPrimary,
              indicatorColor = MinimalPrimaryContainer,
              unselectedIconColor = MinimalTextSecondary,
              unselectedTextColor = MinimalTextSecondary
            ),
            modifier = Modifier.testTag("nav_tab_screenings")
          )

          // Tab 2: Stockholm Cinemas
          NavigationBarItem(
            selected = currentTab == 2,
            onClick = { currentTab = 2 },
            icon = {
              Icon(Icons.Default.Business, contentDescription = "Cinemas")
            },
            label = { Text("Cinemas", fontSize = 10.sp, fontWeight = FontWeight.Medium) },
            colors = NavigationBarItemDefaults.colors(
              selectedIconColor = MinimalPrimary,
              selectedTextColor = MinimalPrimary,
              indicatorColor = MinimalPrimaryContainer,
              unselectedIconColor = MinimalTextSecondary,
              unselectedTextColor = MinimalTextSecondary
            ),
            modifier = Modifier.testTag("nav_tab_cinemas")
          )

          // Tab 3: Settings & Alerts
          NavigationBarItem(
            selected = currentTab == 3,
            onClick = { currentTab = 3 },
            icon = {
              Icon(Icons.Default.Notifications, contentDescription = "Alerts")
            },
            label = { Text("Alerts", fontSize = 10.sp, fontWeight = FontWeight.Medium) },
            colors = NavigationBarItemDefaults.colors(
              selectedIconColor = MinimalPrimary,
              selectedTextColor = MinimalPrimary,
              indicatorColor = MinimalPrimaryContainer,
              unselectedIconColor = MinimalTextSecondary,
              unselectedTextColor = MinimalTextSecondary
            ),
            modifier = Modifier.testTag("nav_tab_settings")
          )
        }
      }
    }
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      when (currentTab) {
        0 -> WatchlistScreen(
          uiState = uiState,
          onScanNow = { viewModel.scanStockholmCinemas() },
          onOpenAddDialog = { showAddDialog = true },
          onOpenImdbDialog = { showImdbDialog = true },
          onOpenGoogleTvDialog = { showGoogleTvDialog = true },
          onToggleShowingSoonFilter = { viewModel.toggleShowingSoonOnly() },
          onRemoveMovie = { id -> viewModel.removeMovie(id) }
        )
        1 -> ScreeningsScreen(
          uiState = uiState,
          onSelectCinemaFilter = { filter -> viewModel.setCinemaFilter(filter) },
          onScanNow = { viewModel.scanStockholmCinemas() }
        )
        2 -> CinemasScreen(
          uiState = uiState,
          onToggleCinema = { cinemaId, isEnabled -> viewModel.setCinemaEnabled(cinemaId, isEnabled) }
        )
        3 -> SettingsScreen(
          uiState = uiState,
          onToggleAutoPolling = { enabled -> viewModel.toggleAutoPolling(enabled) },
          onSetPollingInterval = { hours -> viewModel.setPollingInterval(hours) },
          onScanNow = { viewModel.scanStockholmCinemas() },
          onSendTestNotification = { viewModel.sendTestNotification() },
          onOpenImdbDialog = { showImdbDialog = true },
          onOpenGoogleTvDialog = { showGoogleTvDialog = true }
        )
      }
    }
  }

  // Dialogs
  if (showAddDialog) {
    AddMovieDialog(
      catalog = viewModel.getSearchableCatalog(),
      onDismiss = { showAddDialog = false },
      onAddCustom = { title, year, director ->
        viewModel.addCustomMovie(title, year, director)
      },
      onSelectFromCatalog = { item ->
        viewModel.addFromCatalog(item)
      }
    )
  }

  if (showImdbDialog) {
    ImdbImportDialog(
      onDismiss = { showImdbDialog = false },
      onImport = { urlOrId -> viewModel.importImdb(urlOrId) }
    )
  }

  if (showGoogleTvDialog) {
    GoogleTvImportDialog(
      onDismiss = { showGoogleTvDialog = false },
      onImport = { text -> viewModel.importGoogleTv(text) }
    )
  }
}

