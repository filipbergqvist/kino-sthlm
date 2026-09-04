package se.kinosthlm.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.notification.NotificationHelper
import se.kinosthlm.app.ui.viewmodel.UiState
import se.kinosthlm.app.ui.viewmodel.WatchlistEntry

@Composable
fun WatchlistScreen(
  uiState: UiState,
  onToggleShowingSoon: () -> Unit,
  onRemove: (String) -> Unit,
  onOpenBooking: (String) -> Unit,
  onAddFilm: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilterChip(
        selected = uiState.showingSoonOnly,
        onClick = onToggleShowingSoon,
        label = { Text("Showing soon") },
        modifier = Modifier.testTag("filter_showing_soon"),
      )
      AssistChip(onClick = onAddFilm, label = { Text("Add film") })
    }

    if (uiState.watchlist.isEmpty()) {
      EmptyState(
        title = if (uiState.showingSoonOnly) "Nothing scheduled yet" else "Your watchlist is empty",
        body =
          if (uiState.showingSoonOnly) {
            "None of your films have a Stockholm screening in the window yet. " +
              "You will get a notification the moment one is announced."
          } else {
            "Connect Trakt or import a CSV from the Settings tab to get started."
          },
      )
      return@Column
    }

    LazyColumn(
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(uiState.watchlist, key = { it.item.id }) { entry ->
        WatchlistCard(entry = entry, onRemove = onRemove, onOpenBooking = onOpenBooking)
      }
    }
  }
}

@Composable
private fun WatchlistCard(
  entry: WatchlistEntry,
  onRemove: (String) -> Unit,
  onOpenBooking: (String) -> Unit,
) {
  Card(
    Modifier.fillMaxWidth().testTag("watchlist_item_${entry.item.id}"),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
          Text(
            entry.item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            listOfNotNull(entry.item.year?.toString(), sourceLabel(entry.item.source))
              .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(
          onClick = { onRemove(entry.item.id) },
          modifier = Modifier.testTag("remove_${entry.item.id}"),
        ) {
          Icon(Icons.Default.Delete, contentDescription = "Remove ${entry.item.title}")
        }
      }

      if (entry.screenings.isEmpty()) {
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
            listOfNotNull(screening.auditorium, screening.formatTag).joinToString(" · ")
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
}

private fun sourceLabel(source: String): String =
  when (source) {
    WatchlistItem.SOURCE_TRAKT -> "Trakt"
    WatchlistItem.SOURCE_IMDB -> "IMDb"
    WatchlistItem.SOURCE_GOOGLE_TV -> "Google TV"
    else -> "Added by hand"
  }
