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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import se.kinosthlm.app.notification.NotificationHelper
import se.kinosthlm.app.ui.viewmodel.UiState
import se.kinosthlm.app.ui.viewmodel.WatchlistEntry
import se.kinosthlm.app.ui.viewmodel.WatchlistSort

private fun WatchlistSort.label(): String =
  when (this) {
    WatchlistSort.ADDED -> "Recently added"
    WatchlistSort.ALPHABETICAL -> "A–Z"
    WatchlistSort.YEAR -> "Year"
  }

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
  onClearSelection: () -> Unit,
  onRemoveSelected: () -> Unit,
  onMuteSelected: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()

  @Composable
  fun renderCard(entry: WatchlistEntry) {
    WatchlistCard(
      entry = entry,
      onOpenBooking = onOpenBooking,
      onClick = { onOpenDetail(entry) },
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
      if (uiState.isSelecting) {
        item {
          SelectionBar(
            count = uiState.selectedIds.size,
            onCancel = onClearSelection,
            onRemove = onRemoveSelected,
            onMute = { onMuteSelected(true) },
            onUnmute = { onMuteSelected(false) },
          )
        }
      } else {
        item { SyncWidget(uiState, onSync, onOpenSources) }
      }

      if (uiState.needsReview.isNotEmpty()) {
        item { ReviewBanner(count = uiState.needsReview.size, onReview = onReview) }
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
            AssistChip(
              onClick = onCycleSort,
              label = { Text(uiState.watchlistSort.label()) },
              modifier = Modifier.testTag("sort_watchlist"),
            )
            AssistChip(
              onClick = onAddFilm,
              label = { Text("Add") },
              modifier = Modifier.testTag("add_film"),
            )
            if (uiState.seriesCount > 0) {
              Text(
                "${uiState.seriesCount} TV series hidden",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
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

      val showingNow = uiState.watchlist.filter { it.nextScreening != null }
      val rest = uiState.watchlist.filter { it.nextScreening == null }

      if (showingNow.isNotEmpty() && rest.isNotEmpty()) {
        // Visually separate what actually has a showing from the rest of the mirrored list,
        // rather than making the user scan the whole thing to see what is worth acting on.
        item {
          OutlinedCard(
            Modifier.fillMaxWidth().testTag("showing_now_group"),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
          ) {
            Column(
              Modifier.padding(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              for (entry in showingNow) {
                renderCard(entry)
              }
            }
          }
        }
        items(rest, key = { it.item.id }) { entry -> renderCard(entry) }
      } else {
        items(uiState.watchlist, key = { it.item.id }) { entry -> renderCard(entry) }
      }
    }

    VerticalScrollbar(listState, modifier = Modifier.padding(vertical = 16.dp, horizontal = 2.dp))
  }
}

/**
 * Sync status, at the top of the list where it belongs — how many films we are watching, when we
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
            "${uiState.watchlist.size} films watched",
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

/**
 * Replaces the sync widget while films are selected — a Gmail-style contextual bar for bulk
 * actions. Mute/unmute set an explicit state on every selected film rather than toggling each
 * one's own, since a mixed selection has no single "current" state to flip.
 */
@Composable
private fun SelectionBar(
  count: Int,
  onCancel: () -> Unit,
  onRemove: () -> Unit,
  onMute: () -> Unit,
  onUnmute: () -> Unit,
) {
  Card(
    Modifier.fillMaxWidth().testTag("selection_bar"),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
  ) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          "$count selected",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        TextButton(onClick = onCancel, modifier = Modifier.testTag("cancel_selection")) {
          Text("Cancel")
        }
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onMute, modifier = Modifier.testTag("mute_selected")) {
          Text("Mute")
        }
        OutlinedButton(onClick = onUnmute, modifier = Modifier.testTag("unmute_selected")) {
          Text("Unmute")
        }
        OutlinedButton(
          onClick = onRemove,
          colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
          modifier = Modifier.testTag("remove_selected"),
        ) {
          Text("Remove")
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
          "Several films share these names. Pick the right one so we watch for the right film.",
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
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (isSelectionMode) {
        Checkbox(
          checked = isSelected,
          onCheckedChange = { onToggleSelect() },
          modifier = Modifier.padding(start = 8.dp).testTag("select_${entry.item.id}"),
        )
      }
      WatchlistCardContent(entry, onOpenBooking, Modifier.weight(1f))
    }
  }
}

@Composable
private fun WatchlistCardContent(
  entry: WatchlistEntry,
  onOpenBooking: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
    Column(modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.Top) {
        // A small portrait poster once TMDB has resolved one; nothing forces the layout while
        // it hasn't, which is deliberate — posters fill in gradually as the resolver catches up.
        if (entry.item.posterUrl != null) {
          AsyncImage(
            model = entry.item.posterUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
              Modifier.size(width = 48.dp, height = 72.dp).clip(RoundedCornerShape(6.dp)),
          )
          Spacer(Modifier.width(12.dp))
        }

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
          onClick = { onOpenBooking(screening.bookingUrl) },
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
