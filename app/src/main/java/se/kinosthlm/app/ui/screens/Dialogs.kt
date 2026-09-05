package se.kinosthlm.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import se.kinosthlm.app.data.model.TitleCandidate
import se.kinosthlm.app.data.watchlist.TitleLookup
import se.kinosthlm.app.ui.viewmodel.AddSearchState
import se.kinosthlm.app.ui.viewmodel.ReviewEntry

/**
 * Resolve titles that could be several different films.
 *
 * A Google TV export gives a bare name, so "Nosferatu" might mean 1922 or 2024. We show the
 * actual candidates — poster, title and year — and the user taps one, pastes a link if none of
 * them is right, or removes the entry entirely.
 *
 * The queue is derived from ids rather than an index into [entries]. That list is live: choosing
 * a film clears its `needsReview` flag, so the entry disappears from it a moment later. An index
 * that also stepped forward would land two places on, silently skipping the next film — which is
 * exactly what it used to do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewDialog(
  entries: List<ReviewEntry>,
  onChoose: (String, TitleCandidate) -> Unit,
  onResolveByLink: (String, String) -> Unit,
  onRemove: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  // Entries put off with "Later", kept for this sitting only so they stop coming back round.
  val skipped = remember { mutableStateListOf<String>() }
  val queue = entries.filterNot { it.item.id in skipped }
  val entry = queue.firstOrNull()

  if (entry == null) {
    onDismiss()
    return
  }

  var link by remember(entry.item.id) { mutableStateOf("") }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    modifier = Modifier.testTag("review_sheet"),
  ) {
    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Which \"${entry.item.title}\"?", style = MaterialTheme.typography.titleLarge)
          Text(
            if (queue.size > 1) "${queue.size} titles still need a choice" else "Last one",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        // 50 entries is a lot of "Later" taps; one X closes the whole sitting.
        IconButton(onClick = onDismiss, modifier = Modifier.testTag("close_review")) {
          Icon(Icons.Default.Close, contentDescription = "Close")
        }
      }

      Spacer(Modifier.height(8.dp))
      if (entry.candidates.isEmpty()) {
        Text(
          "We could not find a film with this name. Paste a link below, or remove it.",
          style = MaterialTheme.typography.bodyMedium,
        )
      } else {
        LazyColumn(
          Modifier.heightIn(max = 320.dp).testTag("candidates"),
          contentPadding = PaddingValues(vertical = 4.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(entry.candidates.size) { position ->
            val candidate = entry.candidates[position]
            CandidateRow(candidate) { onChoose(entry.item.id, candidate) }
          }
        }
      }

      Spacer(Modifier.height(12.dp))
      OutlinedTextField(
        value = link,
        onValueChange = { link = it },
        label = { Text("…or paste an IMDb/TMDB link") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("review_link_input"),
        trailingIcon = {
          if (link.isNotBlank()) {
            TextButton(
              onClick = { onResolveByLink(entry.item.id, link.trim()) },
              modifier = Modifier.testTag("resolve_link"),
            ) {
              Text("Use")
            }
          }
        },
      )

      Spacer(Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
          onClick = { skipped += entry.item.id },
          modifier = Modifier.testTag("review_later"),
        ) {
          Text("Later")
        }
        TextButton(
          onClick = { onRemove(entry.item.id) },
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
          modifier = Modifier.testTag("review_remove"),
        ) {
          Text("Remove")
        }
      }
    }
  }
}

@Composable
private fun CandidateRow(candidate: TitleCandidate, onClick: () -> Unit) {
  Card(
    Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("candidate_${candidate.tmdbId}"),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
      val posterShape = Modifier.size(width = 40.dp, height = 60.dp).clip(RoundedCornerShape(4.dp))
      if (candidate.posterUrl != null) {
        AsyncImage(
          model = candidate.posterUrl,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = posterShape,
        )
      } else {
        PosterPlaceholder(posterShape)
      }
      Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f)) {
        Text(
          candidate.title,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
        )
        Text(
          listOfNotNull(candidate.year?.toString(), candidate.imdbId).joinToString(" · "),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** Fetch a public IMDb list. Fragile by nature, and the copy says so. */
@Composable
fun ImdbListDialog(onDismiss: () -> Unit, onFetch: (String) -> Unit) {
  var input by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("IMDb public list") },
    text = {
      Column {
        OutlinedTextField(
          value = input,
          onValueChange = { input = it },
          label = { Text("List URL, ls… id, or ur… user id") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("imdb_list_input"),
        )
        Text(
          "The list must be public. IMDb offers no supported API for this, so it can stop " +
            "working without warning — the CSV export is the dependable route.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onFetch(input.trim())
          onDismiss()
        },
        enabled = input.isNotBlank(),
      ) {
        Text("Fetch")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

/**
 * Add a film by hand: an IMDb or TMDB link resolves exactly, a typed title (optionally with a
 * year, e.g. "Amadeus 1984") searches TMDB and offers up to three close matches to pick between —
 * so there is never a guess about which of several same-named films was meant.
 */
@Composable
fun AddFilmDialog(
  searchState: AddSearchState,
  onSearch: (String) -> Unit,
  onAdd: (TitleLookup.Candidate) -> Unit,
  onDismiss: () -> Unit,
) {
  var input by remember { mutableStateOf("") }
  var searched by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add a film") },
    text = {
      Column {
        OutlinedTextField(
          value = input,
          onValueChange = {
            input = it
            searched = false
          },
          label = { Text("IMDb/TMDB link, or a title") },
          placeholder = { Text("e.g. Amadeus 1984") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("add_film_input"),
        )
        Text(
          "Paste a link to identify the film exactly, or type a title — add a year if it's " +
            "shared by more than one film.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        )
        if (searchState.isSearching) {
          Text(
            "Searching…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else if (searchState.error != null) {
          Text(
            searchState.error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        } else if (searched && searchState.results.isEmpty()) {
          Text(
            "No films matched that.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else if (searchState.results.isNotEmpty()) {
          LazyColumn(
            Modifier.heightIn(max = 280.dp).testTag("add_results"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(searchState.results.size) { position ->
              val candidate = searchState.results[position]
              AddCandidateRow(candidate) {
                onAdd(candidate)
                onDismiss()
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          searched = true
          onSearch(input.trim())
        },
        enabled = input.isNotBlank() && !searchState.isSearching,
        modifier = Modifier.testTag("search_add"),
      ) {
        Text("Search")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

@Composable
private fun AddCandidateRow(candidate: TitleLookup.Candidate, onClick: () -> Unit) {
  Card(
    Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("add_candidate_${candidate.tmdbId}"),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(candidate.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        candidate.year?.let {
          Text(
            it.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
