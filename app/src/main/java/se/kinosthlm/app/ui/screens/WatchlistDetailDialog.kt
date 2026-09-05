package se.kinosthlm.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.ui.viewmodel.WatchlistEntry

/**
 * Detail view for one tracked film: poster, synopsis, a link to IMDb, and which lists it came
 * from. This is also the one place removal lives — tapping a card in the list opens this rather
 * than exposing an inline delete icon, so removing a film is a deliberate second step.
 *
 * A bottom sheet rather than a centre dialog: full width leaves room for the synopsis and the
 * venue-tag chips, and rising from the bottom keeps the controls under your thumb instead of
 * re-centring (and so moving) every time the content changes height.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WatchlistDetailDialog(
  entry: WatchlistEntry,
  notificationsEnabled: Boolean,
  onOpenImdb: (String) -> Unit,
  onRemove: (String) -> Unit,
  onTogglePin: (String, Boolean) -> Unit,
  onToggleMute: (String, Boolean) -> Unit,
  onSetRequiredVenueTag: (String, String?) -> Unit,
  onDismiss: () -> Unit,
) {
  val item = entry.item

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = Modifier.testTag("detail_${item.id}"),
  ) {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(20.dp)) {
          // Posters are portrait; cropping one into a full-width banner wasted most of the sheet
          // and looked odd. Beside the text it stays the shape it was made in.
          Row {
            val posterShape =
              Modifier.size(width = 120.dp, height = 180.dp).clip(RoundedCornerShape(8.dp))
            if (item.posterUrl != null) {
              AsyncImage(
                model = item.posterUrl,
                contentDescription = "${item.title} poster",
                contentScale = ContentScale.Crop,
                modifier = posterShape,
              )
            } else {
              PosterPlaceholder(posterShape, loading = !item.hasNoPoster)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
              Text(
                item.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
              )
              watchlistDescriptor(item, entry.sources)?.let { descriptor ->
                Text(
                  descriptor,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              SourceTags(entry.sources, modifier = Modifier.padding(top = 6.dp))
            }
          }

          Spacer(Modifier.height(12.dp))
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            IconButton(
              onClick = { onToggleMute(item.id, !entry.isMuted) },
              modifier = Modifier.size(36.dp).testTag("toggle_mute_${item.id}"),
            ) {
              Icon(
                if (entry.isMuted) Icons.Default.NotificationsOff else Icons.Outlined.Notifications,
                contentDescription = if (entry.isMuted) "Unmute notifications" else "Mute notifications for this film",
                modifier = Modifier.size(20.dp),
                tint =
                  if (entry.isMuted) MaterialTheme.colorScheme.error
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconButton(
              onClick = { onTogglePin(item.id, !entry.isPinned) },
              modifier = Modifier.size(36.dp).testTag("toggle_pin_${item.id}"),
            ) {
              Icon(
                if (entry.isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                contentDescription = if (entry.isPinned) "Unpin" else "Pin so it stays even if removed upstream",
                modifier = Modifier.size(20.dp),
                tint =
                  if (entry.isPinned) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          // Pointless while nothing can notify at all, so it does not appear then.
          if (notificationsEnabled) {
            Spacer(Modifier.height(16.dp))
            Text(
              "Notify for",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Wraps: with a fourth venue tag there are five chips here, which is more than fits
            // on one line of a phone.
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
              modifier = Modifier.padding(top = 4.dp).testTag("venue_tag_row_${item.id}"),
            ) {
              FilterChip(
                selected = item.requiredVenueTag == null,
                onClick = { onSetRequiredVenueTag(item.id, null) },
                label = { Text("Any cinema") },
                modifier = Modifier.testTag("venue_tag_any_${item.id}"),
              )
              for (tag in Cinema.ALL_TAGS) {
                FilterChip(
                  selected = item.requiredVenueTag == tag,
                  onClick = { onSetRequiredVenueTag(item.id, tag) },
                  label = { Text(tag) },
                  modifier = Modifier.testTag("venue_tag_${tag}_${item.id}"),
                )
              }
            }
          }

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
            ExternalLinkButton(
              imdbId = item.imdbId,
              tmdbId = item.tmdbId,
              onOpenLink = onOpenImdb,
              modifier = Modifier.testTag("open_imdb"),
            )
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
