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
      Column {
        Text(
          "${uiState.cinemas.count { it.isEnabled }} of ${uiState.cinemas.size} cinemas followed",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The toggle is not a notification switch — it decides whether the venue is looked at
        // at all, so switching one off also drops the showings already found there.
        Text(
          "Switching a cinema off stops it being checked, and clears the screenings we found " +
            "there. Mute a single film from its own card instead.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
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
        //
        // Both numbers, because they answer different questions. "No matches right now" is an
        // ordinary week and says nothing about whether we can still read the programme; "0 found"
        // at a cinema that plainly has one is a broken adapter. Only showing the match count hid
        // exactly that difference — which is how Bio Capitol looked fine while returning titles
        // nothing could ever match.
        val status =
          when {
            !cinema.isEnabled -> "Not followed"
            error != null -> "Last sync failed: $error"
            cinema.lastPolledAt == 0L -> "Not synced yet"
            cinema.lastSeenScreeningsCount == 0 -> "0 screenings found — check this cinema"
            cinema.upcomingScreeningsCount > 0 ->
              "${cinema.upcomingScreeningsCount} of ${cinema.lastSeenScreeningsCount} " +
                "screenings match your list"
            else -> "${cinema.lastSeenScreeningsCount} screenings found, none on your list"
          }
        Text(
          status,
          style = MaterialTheme.typography.bodySmall,
          color =
            if (cinema.isEnabled &&
              (error != null || (cinema.lastPolledAt > 0L && cinema.lastSeenScreeningsCount == 0))
            ) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onOpenWebsite) { Text("Website") }
      }
    }
  }
}
