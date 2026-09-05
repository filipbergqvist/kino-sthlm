package se.kinosthlm.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.kinosthlm.app.data.model.TitleCandidate
import se.kinosthlm.app.ui.viewmodel.ReviewEntry

/**
 * Resolve titles that could be several different films.
 *
 * A Google TV export gives a bare name, so "Nosferatu" might mean 1922 or 2024. Rather than
 * asking the user to go and find an IMDb link, we show the actual candidates — title, year and
 * IMDb id — and they tap one. Anything that is really a TV series can be dismissed here too.
 */
@Composable
fun ReviewDialog(
  entries: List<ReviewEntry>,
  onChoose: (String, TitleCandidate) -> Unit,
  onMarkSeries: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  if (entries.isEmpty()) {
    onDismiss()
    return
  }

  var index by remember { mutableStateOf(0) }
  val entry = entries.getOrNull(index) ?: entries.first()

  fun advance() {
    if (index >= entries.size - 1) onDismiss() else index++
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Column {
        Text("Which \"${entry.item.title}\"?")
        if (entries.size > 1) {
          Text(
            "${index + 1} of ${entries.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    text = {
      Column {
        if (entry.candidates.isEmpty()) {
          Text(
            "We could not find a film with this name. It may be a TV series, or spelled " +
              "differently on IMDb.",
            style = MaterialTheme.typography.bodyMedium,
          )
        } else {
          Text(
            "Your watchlist does not say which one. Pick the film you meant.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          LazyColumn(
            Modifier.heightIn(max = 320.dp).testTag("candidates"),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(entry.candidates.size) { position ->
              val candidate = entry.candidates[position]
              CandidateRow(candidate) {
                onChoose(entry.item.id, candidate)
                advance()
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onMarkSeries(entry.item.id)
          advance()
        },
        modifier = Modifier.testTag("not_a_film"),
      ) {
        Text("Not a film")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
  )
}

@Composable
private fun CandidateRow(candidate: TitleCandidate, onClick: () -> Unit) {
  Card(
    Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("candidate_${candidate.imdbId}"),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
 * Add a film by hand from an IMDb link.
 *
 * A link rather than a typed title and year, because the id identifies the film exactly: no
 * guessing between two films sharing a name, no year to remember, and nothing to review
 * afterwards. Paste from the IMDb app's share sheet or the address bar.
 */
@Composable
fun AddByImdbLinkDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
  var input by remember { mutableStateOf("") }
  val looksValid = Regex("""tt\d{5,}""").containsMatchIn(input)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add a film") },
    text = {
      Column {
        OutlinedTextField(
          value = input,
          onValueChange = { input = it },
          label = { Text("IMDb link") },
          placeholder = { Text("https://www.imdb.com/title/tt0013442/") },
          singleLine = true,
          isError = input.isNotBlank() && !looksValid,
          modifier = Modifier.fillMaxWidth().testTag("imdb_link_input"),
        )
        Text(
          if (input.isNotBlank() && !looksValid) {
            "That link has no title id in it. It should contain something like tt0013442."
          } else {
            "Find the film on IMDb and paste its link. We read the title and year from it, so " +
              "there is nothing to type and no chance of picking the wrong film."
          },
          style = MaterialTheme.typography.bodySmall,
          color =
            if (input.isNotBlank() && !looksValid) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onAdd(input.trim())
          onDismiss()
        },
        enabled = looksValid,
        modifier = Modifier.testTag("confirm_add"),
      ) {
        Text("Add")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}
