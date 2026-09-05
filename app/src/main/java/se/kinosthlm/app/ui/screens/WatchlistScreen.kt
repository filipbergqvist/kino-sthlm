package se.kinosthlm.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import se.kinosthlm.app.notification.NotificationHelper
import se.kinosthlm.app.ui.viewmodel.UiState
import se.kinosthlm.app.ui.viewmodel.WatchlistEntry
import se.kinosthlm.app.ui.viewmodel.WatchlistSort

/**
 * The watchlist, mirrored from whatever the user already uses.
 *
 * Mostly a mirror of the lists the user already keeps elsewhere, so the widget at the top is
 * about *syncing* rather than editing. Films can still be added and removed by hand, but each
 * entry remembers which lists it came from: removing it upstream removes it here, and removing
 * it here keeps it hidden even while a source still lists it.
 */
@Composable
fun WatchlistScreen(
  uiState: UiState,
  onSync: () -> Unit,
  onToggleShowingSoon: () -> Unit,
  onOpenBooking: (String) -> Unit,
  onOpenSources: () -> Unit,
  onReview: () -> Unit,
  onAddFilm: () -> Unit,
  onOpenDetail: (WatchlistEntry) -> Unit,
  onQueryChange: (String) -> Unit,
  onCycleSort: () -> Unit,
  onStartSelecting: (String) -> Unit,
  onToggleSelected: (String) -> Unit,
  onPosterNeeded: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()

  @Composable
  fun renderCard(entry: WatchlistEntry) {
    WatchlistCard(
      entry = entry,
      onOpenBooking = onOpenBooking,
      onClick = { onOpenDetail(entry) },
      onPosterNeeded = onPosterNeeded,
      isSelectionMode = uiState.isSelecting,
      isSelected = entry.item.id in uiState.selectedIds,
      onLongPress = { onStartSelecting(entry.item.id) },
      onToggleSelect = { onToggleSelected(entry.item.id) },
    )
  }

  Box(modifier.fillMaxWidth()) {
    LazyColumn(
      Modifier.fillMaxWidth(),
      state = listState,
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item { SyncWidget(uiState, onSync, onOpenSources) }

      if (uiState.needsReview.isNotEmpty()) {
        item { ReviewBanner(count = uiState.needsReview.size, onReview = onReview) }
      }

      // Below the review banner rather than in the filter row, which it was making too wide.
      if (uiState.seriesCount > 0) {
        item {
          Text(
            "${uiState.seriesCount} TV series hidden",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (uiState.hasFilms) {
        item {
          OutlinedTextField(
            value = uiState.watchlistQuery,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("watchlist_search"),
            placeholder = { Text("Search your watchlist") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
              if (uiState.watchlistQuery.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                  Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
              }
            },
            singleLine = true,
          )
        }
        item {
          Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            FilterChip(
              selected = uiState.showingSoonOnly,
              onClick = onToggleShowingSoon,
              label = { Text("Showing soon") },
              modifier = Modifier.testTag("filter_showing_soon"),
            )
            // One chip naming the whole order, rather than a field plus a direction arrow: two
            // controls plus "Add" made this row wide enough to wrap on a long label.
            AssistChip(
              onClick = onCycleSort,
              label = { Text(uiState.watchlistSort.label) },
              leadingIcon = {
                Icon(
                  Icons.AutoMirrored.Filled.Sort,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
              },
              border = null,
              colors =
                AssistChipDefaults.assistChipColors(
                  containerColor = MaterialTheme.colorScheme.secondaryContainer,
                  labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                  leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
              modifier = Modifier.testTag("sort_watchlist"),
            )
          }
        }
      }

      if (uiState.watchlist.isEmpty()) {
        item {
          EmptyState(
            title =
              when {
                uiState.watchlistQuery.isNotEmpty() -> "No matches"
                uiState.showingSoonOnly -> "Nothing scheduled yet"
                else -> "No films synced yet"
              },
            body =
              when {
                uiState.watchlistQuery.isNotEmpty() ->
                  "Nothing in your watchlist matches \"${uiState.watchlistQuery}\"."
                uiState.showingSoonOnly ->
                  "None of your films have a Stockholm screening in the window yet. " +
                    "You will get a notification the moment one is announced."
                else -> "Connect Trakt or import a watchlist export to get started."
              },
          )
        }
      }

      // Films with a current showing sort to the top; each gets a colored border instead of a
      // wrapping box, so it reads as "this one matters" without a second nested card look.
      val showingNow = uiState.watchlist.filter { it.nextScreening != null }
      val rest = uiState.watchlist.filter { it.nextScreening == null }
      items(showingNow + rest, key = { it.item.id }) { entry -> renderCard(entry) }
    }

    VerticalScrollbar(listState, modifier = Modifier.padding(vertical = 16.dp, horizontal = 2.dp))

    // Add lives here rather than as a chip in the filter row, which was getting too wide to fit
    // a long sort label. Scroll-to-top stacks above it, and only once there is somewhere to go.
    val scope = rememberCoroutineScope()
    Column(
      Modifier.align(Alignment.BottomEnd).padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (listState.firstVisibleItemIndex > 0) {
        SmallFloatingActionButton(
          onClick = { scope.launch { listState.animateScrollToItem(0) } },
          modifier = Modifier.testTag("scroll_to_top"),
        ) {
          Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Scroll to top")
        }
      }
      FloatingActionButton(onClick = onAddFilm, modifier = Modifier.testTag("add_film")) {
        Icon(Icons.Default.Add, contentDescription = "Add a film")
      }
    }
  }
}

/**
 * Sync status, at the top of the list where it belongs — how many films we are tracking, when we
 * last looked, whether anything broke, and a button to look again now.
 */
@Composable
private fun SyncWidget(uiState: UiState, onSync: () -> Unit, onOpenSources: () -> Unit) {
  Card(
    Modifier.fillMaxWidth().testTag("sync_widget"),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(
            // The whole list, not the filtered view — "tracked" is about what the app is
            // watching for, which a filter chip does not change.
            "${uiState.trackedCount} films tracked",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            when {
              uiState.isResolving -> {
                val (done, total) = uiState.resolveProgress ?: (0 to 0)
                if (total > 0) "Identifying titles… $done of $total" else "Identifying titles…"
              }
              uiState.isSyncing -> uiState.syncStep ?: "Syncing…"
              uiState.lastSyncAt > 0L ->
                "Last synced ${NotificationHelper.formatTime(uiState.lastSyncAt)}"
              else -> "Not synced yet"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (uiState.isSyncing || uiState.isResolving) {
          CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
          FilledTonalButton(onClick = onSync, modifier = Modifier.testTag("sync_now_widget")) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Sync")
          }
        }
      }

      if (uiState.isResolving) {
        val (done, total) = uiState.resolveProgress ?: (0 to 0)
        Spacer(Modifier.height(8.dp))
        if (total > 0) {
          LinearProgressIndicator(
            progress = { done.toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
          )
        } else {
          LinearProgressIndicator(Modifier.fillMaxWidth())
        }
      }

      if (uiState.lastSyncSummary.isNotBlank() && !uiState.isSyncing) {
        Spacer(Modifier.height(8.dp))
        Text(
          uiState.lastSyncSummary,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // Posters and identification quietly not happening looks exactly like a broken app, so
      // when it is actually TMDB throttling a shared key, say so and point at the way out.
      if (uiState.tmdbRateLimited) {
        Spacer(Modifier.height(8.dp))
        Text(
          "TMDB is rate limiting this build's shared key, so posters and titles may lag. " +
            "Adding your own key under Settings → TMDB avoids sharing the limit.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.testTag("tmdb_rate_limited"),
        )
      }

      // Name what broke rather than leaving the user to wonder why nothing turned up.
      if (uiState.failedSources.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        for (failure in uiState.failedSources) {
          Text(
            "${failure.label} could not be reached",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }

      if (uiState.watchlist.isEmpty() && uiState.needsReview.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onOpenSources, modifier = Modifier.testTag("open_sources")) {
          Text("Connect a watchlist")
        }
      }
    }
  }
}

@Composable
private fun ReviewBanner(count: Int, onReview: () -> Unit) {
  Card(
    Modifier.fillMaxWidth().testTag("review_banner"),
    colors =
      CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
  ) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          if (count == 1) "1 title needs a choice" else "$count titles need a choice",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
          "Several films share these names. Pick the right one so we track the right film.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
      }
      TextButton(onClick = onReview, modifier = Modifier.testTag("open_review")) { Text("Review") }
    }
  }
}

/**
 * Tapping the card opens the detail popup — poster, synopsis, IMDb link, sources, and the one
 * place removal lives (see [se.kinosthlm.app.ui.screens.WatchlistDetailDialog]) — rather than an
 * inline delete icon, so removing a film is a deliberate second step, not a stray tap in a list.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun WatchlistCard(
  entry: WatchlistEntry,
  onOpenBooking: (String) -> Unit,
  onClick: () -> Unit,
  onPosterNeeded: (String) -> Unit,
  isSelectionMode: Boolean = false,
  isSelected: Boolean = false,
  onLongPress: () -> Unit = {},
  onToggleSelect: () -> Unit = {},
) {
  Card(
    Modifier.fillMaxWidth()
      .combinedClickable(
        onClick = if (isSelectionMode) onToggleSelect else onClick,
        onLongClick = onLongPress,
      )
      .testTag("watchlist_item_${entry.item.id}"),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    border =
      if (entry.nextScreening != null) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
      } else {
        null
      },
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      // While selecting, the card is one big checkbox: the Tickets buttons inside it are easy to
      // hit by accident and would send you off to a booking page mid-selection.
      WatchlistCardContent(
        entry = entry,
        onOpenBooking = if (isSelectionMode) null else onOpenBooking,
        onPosterNeeded = onPosterNeeded,
        modifier = Modifier.weight(1f),
      )
      // Trailing, not leading: a checkbox on the left shoves the poster and title sideways
      // every time selection mode turns on.
      if (isSelectionMode) {
        Checkbox(
          checked = isSelected,
          onCheckedChange = { onToggleSelect() },
          modifier = Modifier.padding(end = 8.dp).testTag("select_${entry.item.id}"),
        )
      }
    }
  }
}

@Composable
private fun WatchlistCardContent(
  entry: WatchlistEntry,
  /** Null while multi-selecting, which renders the showing rows inert rather than hiding them. */
  onOpenBooking: ((String) -> Unit)?,
  onPosterNeeded: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
    Column(modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.Top) {
        val posterShape = Modifier.size(width = 48.dp, height = 72.dp).clip(RoundedCornerShape(6.dp))
        if (entry.item.posterUrl != null) {
          AsyncImage(
            model = entry.item.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = posterShape,
          )
        } else {
          // Ask for this film's poster only now that its card is actually on screen, so a long
          // list fills in what you are looking at instead of what happens to be first in the
          // database. Idempotent, so recomposition costs nothing.
          LaunchedEffect(entry.item.id) { onPosterNeeded(entry.item.id) }
          PosterPlaceholder(posterShape)
        }
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              entry.item.title,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.weight(1f, fill = false),
            )
            if (entry.isPinned) {
              Icon(
                Icons.Default.PushPin,
                contentDescription = "Pinned",
                modifier = Modifier.padding(start = 6.dp).size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
              )
            }
            if (entry.isMuted) {
              Icon(
                Icons.Default.NotificationsOff,
                contentDescription = "Muted",
                modifier = Modifier.padding(start = 6.dp).size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          watchlistDescriptor(entry.item, entry.sources)?.let { descriptor ->
            Text(
              descriptor,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          SourceTags(entry.sources, modifier = Modifier.padding(top = 4.dp))
        }
      }

      if (entry.screenings.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
          "No Stockholm screenings found yet",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return@Column
      }

      Spacer(Modifier.height(8.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      Spacer(Modifier.height(8.dp))

      for (screening in entry.screenings.sortedBy { it.screeningTime }.take(3)) {
        ScreeningRow(
          when_ = NotificationHelper.formatTime(screening.screeningTime),
          where = screening.cinemaName,
          detail =
            listOfNotNull(screening.auditorium, screening.formatTag)
              .joinToString(" · ")
              .ifBlank { null },
          onClick = onOpenBooking?.let { open -> { open(screening.bookingUrl) } },
        )
      }
      if (entry.screenings.size > 3) {
        Text(
          "+ ${entry.screenings.size - 3} more on the Schedule tab",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
    }
}
