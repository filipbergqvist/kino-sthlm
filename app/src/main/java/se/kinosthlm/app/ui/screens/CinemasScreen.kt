package se.kinosthlm.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.ui.viewmodel.UiState

/** Which venues to follow. Everything here is one toggle; disabled cinemas are never polled. */
@Composable
fun CinemasScreen(
  uiState: UiState,
  onToggle: (String, Boolean) -> Unit,
  onOpenWebsite: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier.fillMaxWidth(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    item {
      Text(
        "${uiState.cinemas.count { it.isEnabled }} of ${uiState.cinemas.size} cinemas followed",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    items(uiState.cinemas, key = { it.id }) { cinema ->
      CinemaCard(
        cinema = cinema,
        error = uiState.failedSources.firstOrNull { it.sourceId == cinema.sourceId }?.error,
        onToggle = { enabled -> onToggle(cinema.id, enabled) },
        onOpenWebsite = { onOpenWebsite(cinema.websiteUrl) },
      )
    }
  }
}

@Composable
private fun CinemaCard(
  cinema: Cinema,
  error: String?,
  onToggle: (Boolean) -> Unit,
  onOpenWebsite: () -> Unit,
) {
  Card(
    Modifier.fillMaxWidth().testTag("cinema_${cinema.id}"),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(
            cinema.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            "${cinema.district} · ${cinema.address}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = cinema.isEnabled,
          onCheckedChange = onToggle,
          modifier = Modifier.testTag("toggle_${cinema.id}"),
        )
      }

      if (cinema.specialty.isNotBlank()) {
        Text(
          cinema.specialty,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
      }

      if (cinema.tagList.isNotEmpty()) {
        Row(
          Modifier.padding(top = 6.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          for (tag in cinema.tagList) {
            Text(
              tag,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier =
                Modifier
                  .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                  .padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }
      }

      Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // State the truth about this venue rather than a generic "connected" badge.
        val status =
          when {
            !cinema.isEnabled -> "Not followed"
            error != null -> "Last sync failed: $error"
            cinema.lastPolledAt == 0L -> "Not synced yet"
            cinema.upcomingScreeningsCount > 0 ->
              "${cinema.upcomingScreeningsCount} matching screening(s)"
            else -> "No matches right now"
          }
        Text(
          status,
          style = MaterialTheme.typography.bodySmall,
          color =
            if (error != null && cinema.isEnabled) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenWebsite) { Text("Website") }
      }
    }
  }
}
