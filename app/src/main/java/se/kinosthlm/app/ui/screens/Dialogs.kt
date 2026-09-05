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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
  onOpenLink: (String) -> Unit,
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
  // Which candidate is being previewed full-size, if any. Cleared whenever the entry changes.
  var previewing by remember(entry.item.id) { mutableStateOf<TitleCandidate?>(null) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    modifier = Modifier.testTag("review_sheet"),
  ) {
    val candidate = previewing
    if (candidate != null) {
      CandidatePreview(
        candidate = candidate,
        onOpenLink = onOpenLink,
        onSelect = {
          onChoose(entry.item.id, candidate)
          previewing = null
        },
        onBack = { previewing = null },
      )
      return@ModalBottomSheet
    }

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
      Text("Which \"${entry.item.title}\"?", style = MaterialTheme.typography.titleLarge)
      Text(
        if (queue.size > 1) "${queue.size} titles still need a choice" else "Last one",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

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
            val option = entry.candidates[position]
            // Tapping previews rather than committing: a title and a year are rarely enough to
            // be sure, and picking the wrong one silently tracks the wrong film.
            CandidateRow(option) { previewing = option }
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

/**
 * One candidate at full size — poster, synopsis and a link out — so the choice is made on more
 * than a title and a year.
 */
@Composable
private fun CandidatePreview(
  candidate: TitleCandidate,
  onOpenLink: (String) -> Unit,
  onSelect: () -> Unit,
  onBack: () -> Unit,
) {
  Column(
    Modifier.padding(horizontal = 20.dp)
      .padding(bottom = 20.dp)
      .verticalScroll(rememberScrollState())
      .testTag("candidate_preview"),
  ) {
    Row {
      val posterShape = Modifier.size(width = 120.dp, height = 180.dp).clip(RoundedCornerShape(8.dp))
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
      Spacer(Modifier.width(16.dp))
      Column(Modifier.weight(1f)) {
        Text(candidate.title, style = MaterialTheme.typography.titleLarge)
        candidate.year?.let {
          Text(
            it.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        val link =
          candidate.imdbId?.let { "https://www.imdb.com/title/$it/" }
            ?: "https://www.themoviedb.org/movie/${candidate.tmdbId}"
        TextButton(
          onClick = { onOpenLink(link) },
          contentPadding = PaddingValues(0.dp),
          modifier = Modifier.testTag("preview_open_link"),
        ) {
          Text(if (candidate.imdbId != null) "View on IMDb" else "View on TMDB")
        }
      }
    }

    Spacer(Modifier.height(12.dp))
    Text(
      candidate.overview ?: "No description available.",
      style = MaterialTheme.typography.bodyMedium,
      color =
        if (candidate.overview != null) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onSelect, modifier = Modifier.testTag("preview_select")) { Text("Select") }
      TextButton(onClick = onBack, modifier = Modifier.testTag("preview_back")) { Text("Back") }
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
  onOpenLink: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var input by remember { mutableStateOf("") }
  var searched by remember { mutableStateOf(false) }
  var previewing by remember { mutableStateOf<TitleLookup.Candidate?>(null) }
  val keyboard = LocalSoftwareKeyboardController.current

  val candidate = previewing
  if (candidate != null) {
    AddCandidatePreview(
      candidate = candidate,
      onOpenLink = onOpenLink,
      onAdd = {
        onAdd(candidate)
        onDismiss()
      },
      onBack = { previewing = null },
    )
    return
  }

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
              val option = searchState.results[position]
              // Preview rather than add outright: same reasoning as the review sheet, a title
              // and a year are not enough to be sure which film this is.
              AddCandidateRow(option) { previewing = option }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          searched = true
          // The results appear right under the field, so leaving the keyboard up would cover
          // most of them.
          keyboard?.hide()
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

/** The same look-before-you-commit card the review sheet uses, for the manual add flow. */
@Composable
private fun AddCandidatePreview(
  candidate: TitleLookup.Candidate,
  onOpenLink: (String) -> Unit,
  onAdd: () -> Unit,
  onBack: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onBack,
    title = { Text(candidate.title) },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState())) {
        Row {
          val posterShape =
            Modifier.size(width = 110.dp, height = 165.dp).clip(RoundedCornerShape(8.dp))
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
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            candidate.year?.let {
              Text(
                it.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            val link =
              candidate.imdbId?.let { "https://www.imdb.com/title/$it/" }
                ?: "https://www.themoviedb.org/movie/${candidate.tmdbId}"
            TextButton(
              onClick = { onOpenLink(link) },
              contentPadding = PaddingValues(0.dp),
              modifier = Modifier.testTag("add_preview_link"),
            ) {
              Text(if (candidate.imdbId != null) "View on IMDb" else "View on TMDB")
            }
          }
        }
        Spacer(Modifier.height(12.dp))
        Text(
          candidate.overview ?: "No description available.",
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onAdd, modifier = Modifier.testTag("add_preview_confirm")) { Text("Add") }
    },
    dismissButton = { TextButton(onClick = onBack) { Text("Back") } },
  )
}
