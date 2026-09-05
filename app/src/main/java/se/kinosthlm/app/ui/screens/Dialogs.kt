package se.kinosthlm.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import se.kinosthlm.app.data.model.TitleCandidate
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.watchlist.TitleLookup
import se.kinosthlm.app.ui.viewmodel.AddSearchState
import se.kinosthlm.app.ui.viewmodel.ReviewEntry
import se.kinosthlm.app.ui.viewmodel.UiState

/**
 * Resolve titles that could be several different films — or that might not be films at all.
 *
 * A Google TV export gives a bare name, so "Nosferatu" might mean 1922 or 2024. We show the
 * actual candidates — poster, title and year — and the user taps one, pastes a link if none of
 * them is right, or removes the entry entirely.
 *
 * TV series come through here too. They used to be marked and silently hidden behind a "3 TV
 * series hidden" line, which is both uninteresting (series never play in cinemas, so the count
 * tells you nothing) and occasionally wrong — a film sharing its name with a series resolves the
 * same way. So they are put to the user as well, with the two answers that actually apply.
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
  onKeepAsFilm: (String) -> Unit,
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
  val isSeries = entry.item.titleType == WatchlistItem.TYPE_SERIES

  ModalBottomSheet(
    // Dismissing a preview means "back to the list", not "abandon the review". Swiping the sheet
    // down out of a preview used to close the whole queue, losing your place in it.
    onDismissRequest = { if (previewing != null) previewing = null else onDismiss() },
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    modifier = Modifier.testTag("review_sheet"),
  ) {
    val candidate = previewing
    if (candidate != null) {
      CandidatePreview(
        title = candidate.title,
        year = candidate.year,
        posterUrl = candidate.posterUrl,
        overview = candidate.overview,
        imdbId = candidate.imdbId,
        tmdbId = candidate.tmdbId,
        onOpenLink = onOpenLink,
        // Nothing to select on a series candidate: picking it would just re-assert the thing
        // the user is being asked to overrule.
        onSelect =
          if (isSeries) null
          else {
            {
              onChoose(entry.item.id, candidate)
              previewing = null
            }
          },
        onBack = { previewing = null },
      )
      return@ModalBottomSheet
    }

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
      Text(
        if (isSeries) "\"${entry.item.title}\" looks like a TV series"
        else "Which \"${entry.item.title}\"?",
        style = MaterialTheme.typography.titleLarge,
      )
      Text(
        if (isSeries) "TV series never play in cinemas — unless we got this one wrong."
        else if (queue.size > 1) "${queue.size} titles still need a choice"
        else "Last one",
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
            CandidateRow(
              candidate = option,
              // Tapping the row is the answer; the info button is for when a title and a year
              // are not enough to be sure. Previously every tap detoured through the preview.
              onSelect = if (isSeries) null else ({ onChoose(entry.item.id, option) }),
              onInfo = { previewing = option },
            )
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
        if (isSeries) {
          TextButton(
            onClick = { onKeepAsFilm(entry.item.id) },
            modifier = Modifier.testTag("review_keep_as_film"),
          ) {
            Text("Keep as film")
          }
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
 * than a title and a year. Shared by the review queue and the manual add flow, which the board
 * asked to look and behave the same.
 *
 * Back sits on the left and the commit on the right, the way a two-step flow reads.
 */
@Composable
private fun CandidatePreview(
  title: String,
  year: Int?,
  posterUrl: String?,
  overview: String?,
  imdbId: String?,
  tmdbId: Int?,
  onOpenLink: (String) -> Unit,
  /** Null renders the preview read-only — nothing here for the user to commit to. */
  onSelect: (() -> Unit)?,
  onBack: () -> Unit,
  selectLabel: String = "Select",
) {
  Column(
    Modifier.padding(horizontal = 20.dp)
      .padding(bottom = 20.dp)
      .verticalScroll(rememberScrollState())
      .testTag("candidate_preview"),
  ) {
    Row {
      val posterShape = Modifier.size(width = 120.dp, height = 180.dp).clip(RoundedCornerShape(8.dp))
      if (posterUrl != null) {
        AsyncImage(
          model = posterUrl,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = posterShape,
        )
      } else {
        PosterPlaceholder(posterShape, loading = false)
      }
      Spacer(Modifier.width(16.dp))
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        year?.let {
          Text(
            it.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.height(8.dp))
        ExternalLinkButton(
          imdbId = imdbId,
          tmdbId = tmdbId,
          onOpenLink = onOpenLink,
          modifier = Modifier.testTag("preview_open_link"),
        )
      }
    }

    Spacer(Modifier.height(12.dp))
    Text(
      overview ?: "No description available.",
      style = MaterialTheme.typography.bodyMedium,
      color =
        if (overview != null) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      TextButton(onClick = onBack, modifier = Modifier.testTag("preview_back")) { Text("Back") }
      Spacer(Modifier.weight(1f))
      if (onSelect != null) {
        Button(onClick = onSelect, modifier = Modifier.testTag("preview_select")) {
          Text(selectLabel)
        }
      }
    }
  }
}

/**
 * One option in a picker: poster, title, year, and an info button.
 *
 * Tapping the row commits; the info button opens the full preview. Making the whole row open a
 * preview instead turned every choice into two taps even when the year alone settled it.
 */
@Composable
private fun PickerRow(
  title: String,
  subtitle: String?,
  posterUrl: String?,
  onSelect: (() -> Unit)?,
  onInfo: () -> Unit,
  testTag: String,
  infoTestTag: String,
) {
  Card(
    Modifier.fillMaxWidth()
      .then(if (onSelect != null) Modifier.clickable(onClick = onSelect) else Modifier)
      .testTag(testTag),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
      val posterShape = Modifier.size(width = 40.dp, height = 60.dp).clip(RoundedCornerShape(4.dp))
      if (posterUrl != null) {
        AsyncImage(
          model = posterUrl,
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = posterShape,
        )
      } else {
        PosterPlaceholder(posterShape, loading = false)
      }
      Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        if (!subtitle.isNullOrBlank()) {
          Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      IconButton(onClick = onInfo, modifier = Modifier.testTag(infoTestTag)) {
        Icon(
          Icons.Outlined.Info,
          contentDescription = "Details for $title",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun CandidateRow(
  candidate: TitleCandidate,
  onSelect: (() -> Unit)?,
  onInfo: () -> Unit,
) {
  PickerRow(
    title = candidate.title,
    subtitle = listOfNotNull(candidate.year?.toString(), candidate.imdbId).joinToString(" · "),
    posterUrl = candidate.posterUrl,
    onSelect = onSelect,
    onInfo = onInfo,
    testTag = "candidate_${candidate.tmdbId}",
    infoTestTag = "candidate_info_${candidate.tmdbId}",
  )
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
 *
 * A bottom sheet, matching the review queue rather than the centre-floating dialog this used to
 * be: the two flows do the same job — look at some candidates, pick one — and there was no reason
 * for them to look like different features.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

  ModalBottomSheet(
    onDismissRequest = { if (previewing != null) previewing = null else onDismiss() },
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    modifier = Modifier.testTag("add_sheet"),
  ) {
    val candidate = previewing
    if (candidate != null) {
      CandidatePreview(
        title = candidate.title,
        year = candidate.year,
        posterUrl = candidate.posterUrl,
        overview = candidate.overview,
        imdbId = candidate.imdbId,
        tmdbId = candidate.tmdbId,
        onOpenLink = onOpenLink,
        onSelect = {
          onAdd(candidate)
          onDismiss()
        },
        onBack = { previewing = null },
        selectLabel = "Add",
      )
      return@ModalBottomSheet
    }

    Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
      Text("Add a film", style = MaterialTheme.typography.titleLarge)
      Text(
        "Paste a link to identify the film exactly, or type a title — add a year if it's " +
          "shared by more than one film.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )

      OutlinedTextField(
        value = input,
        onValueChange = {
          input = it
          searched = false
        },
        label = { Text("IMDb/TMDB link, or a title") },
        placeholder = { Text("e.g. Amadeus 1984") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("add_film_input"),
      )

      Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(onClick = onDismiss) { Text("Cancel") }
        Spacer(Modifier.weight(1f))
        Button(
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
      }

      Spacer(Modifier.height(8.dp))
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
          Modifier.heightIn(max = 320.dp).testTag("add_results"),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(searchState.results.size) { position ->
            val option = searchState.results[position]
            PickerRow(
              title = option.title,
              subtitle = option.year?.toString(),
              posterUrl = option.posterUrl,
              onSelect = {
                onAdd(option)
                onDismiss()
              },
              onInfo = { previewing = option },
              testTag = "add_candidate_${option.tmdbId}",
              infoTestTag = "add_candidate_info_${option.tmdbId}",
            )
          }
        }
      }
    }
  }
}

/**
 * Source and genre, tucked behind one chip.
 *
 * They could each have been another chip in the filter row, but that row already carries
 * "Showing soon", the sort and the search field above it, and two more pickers would push it into
 * wrapping — the exact problem the sort chip was collapsed to solve.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistFiltersSheet(
  uiState: UiState,
  onSetSource: (String?) -> Unit,
  onSetGenre: (String?) -> Unit,
  onClear: () -> Unit,
  onDismiss: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    modifier = Modifier.testTag("filters_sheet"),
  ) {
    Column(
      Modifier.padding(horizontal = 20.dp)
        .padding(bottom = 24.dp)
        .verticalScroll(rememberScrollState()),
    ) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Filters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (uiState.activeFilterCount > 0) {
          TextButton(onClick = onClear, modifier = Modifier.testTag("clear_filters")) {
            Text("Clear all")
          }
        }
      }

      Spacer(Modifier.height(8.dp))
      Text("Source", style = MaterialTheme.typography.labelMedium)
      FilterRow(
        options = uiState.availableSources,
        selected = uiState.sourceFilter,
        labelOf = ::sourceLabel,
        onSelect = onSetSource,
        tagPrefix = "filter_source",
      )

      Spacer(Modifier.height(16.dp))
      Text("Genre", style = MaterialTheme.typography.labelMedium)
      if (uiState.availableGenres.isEmpty()) {
        Text(
          "Genres arrive with each film's poster, so this fills in as the list loads.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp),
        )
      } else {
        FilterRow(
          options = uiState.availableGenres,
          selected = uiState.genreFilter,
          labelOf = { it },
          onSelect = onSetGenre,
          tagPrefix = "filter_genre",
        )
      }
    }
  }
}

/** "All" plus one chip per option, wrapping onto as many lines as it needs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(
  options: List<String>,
  selected: String?,
  labelOf: (String) -> String,
  onSelect: (String?) -> Unit,
  tagPrefix: String,
) {
  FlowRow(
    Modifier.fillMaxWidth().padding(top = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    FilterChip(
      selected = selected == null,
      onClick = { onSelect(null) },
      label = { Text("All") },
      modifier = Modifier.testTag("${tagPrefix}_all"),
    )
    for (option in options) {
      FilterChip(
        selected = selected == option,
        onClick = { onSelect(if (selected == option) null else option) },
        label = { Text(labelOf(option)) },
        modifier = Modifier.testTag("${tagPrefix}_$option"),
      )
    }
  }
}

/**
 * Paste a list of film names, one per line.
 *
 * For a watchlist kept somewhere with no export at all — a notes app, a text file, an email to
 * yourself. Nothing is looked up here; the names go in as bare titles and the identification pass
 * that follows decides what each one is, asking where a name is shared.
 */
@Composable
fun BatchAddDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
  var input by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Paste a list of films") },
    text = {
      Column {
        OutlinedTextField(
          value = input,
          onValueChange = { input = it },
          label = { Text("One film per line") },
          placeholder = { Text("Amadeus 1984\nStalker\nThe Third Man") },
          modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).testTag("batch_add_input"),
        )
        Text(
          "A year helps where several films share a name. Anything we cannot place on its own " +
            "will come back as a choice to make.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onAdd(input)
          onDismiss()
        },
        enabled = input.isNotBlank(),
        modifier = Modifier.testTag("batch_add_confirm"),
      ) {
        Text("Add")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
