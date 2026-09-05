package se.kinosthlm.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import se.kinosthlm.app.ui.viewmodel.WatchlistEntry

/**
 * Detail view for one watchlisted film: poster, synopsis, a link to IMDb, and which lists it
 * came from. This is also the one place removal lives — tapping a card in the list opens this
 * rather than exposing an inline delete icon, so removing a film is a deliberate second step.
 */
@Composable
fun WatchlistDetailDialog(
  entry: WatchlistEntry,
  onOpenImdb: (String) -> Unit,
  onRemove: (String) -> Unit,
  onTogglePin: (String, Boolean) -> Unit,
  onToggleMute: (String, Boolean) -> Unit,
  onDismiss: () -> Unit,
) {
  val item = entry.item

  Dialog(onDismissRequest = onDismiss) {
    Card(
      Modifier.fillMaxWidth().testTag("detail_${item.id}"),
      shape = RoundedCornerShape(20.dp),
      colors =
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
      Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        if (item.posterUrl != null) {
          AsyncImage(
            model = item.posterUrl,
            contentDescription = "${item.title} poster",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
          )
        }

        Column(Modifier.padding(20.dp)) {
          Row(verticalAlignment = Alignment.Top) {
            Text(
              item.title,
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
              onClick = { onToggleMute(item.id, !entry.isMuted) },
              modifier = Modifier.testTag("toggle_mute_${item.id}"),
            ) {
              Icon(
                if (entry.isMuted) Icons.Default.NotificationsOff else Icons.Outlined.Notifications,
                contentDescription = if (entry.isMuted) "Unmute notifications" else "Mute notifications for this film",
                tint =
                  if (entry.isMuted) MaterialTheme.colorScheme.error
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconButton(
              onClick = { onTogglePin(item.id, !entry.isPinned) },
              modifier = Modifier.testTag("toggle_pin_${item.id}"),
            ) {
              Icon(
                if (entry.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                contentDescription = if (entry.isPinned) "Unpin" else "Pin so it stays even if removed upstream",
                tint =
                  if (entry.isPinned) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          watchlistDescriptor(item, entry.sources)?.let { descriptor ->
            Text(
              descriptor,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          SourceTags(entry.sources, modifier = Modifier.padding(top = 6.dp))

          Spacer(Modifier.height(12.dp))
          Text(
            item.overview ?: "No description available yet.",
            style = MaterialTheme.typography.bodyMedium,
            color =
              if (item.overview != null) MaterialTheme.colorScheme.onSurface
              else MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Spacer(Modifier.height(20.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.imdbId != null) {
              OutlinedButton(
                onClick = { onOpenImdb("https://www.imdb.com/title/${item.imdbId}/") },
                modifier = Modifier.testTag("open_imdb"),
              ) {
                Icon(
                  Icons.AutoMirrored.Default.OpenInNew,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text("IMDb")
              }
            }
            OutlinedButton(
              onClick = {
                onRemove(item.id)
                onDismiss()
              },
              colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
              modifier = Modifier.testTag("remove_${item.id}"),
            ) {
              Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.size(6.dp))
              Text("Remove")
            }
          }

          Spacer(Modifier.height(8.dp))
          TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text("Close")
          }
        }
      }
    }
  }
}
