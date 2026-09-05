package se.kinosthlm.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import se.kinosthlm.app.data.model.Screening
import se.kinosthlm.app.data.source.SwedishDates
import se.kinosthlm.app.ui.viewmodel.UiState

private val DAY_HEADER = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/** Every upcoming screening we matched, grouped by day and filterable by cinema. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleScreen(
  uiState: UiState,
  onSelectCinemaFilter: (String?) -> Unit,
  onOpenBooking: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth()) {
    FlowRow(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilterChip(
        selected = uiState.cinemaFilter == null,
        onClick = { onSelectCinemaFilter(null) },
        label = { Text("All") },
      )
      // Offer only cinemas that actually have something, so the row stays short — but read that
      // from the unfiltered list, or picking one filter hides every other chip.
      val withScreenings = uiState.allScreenings.map { it.cinemaId }.toSet()
      for (cinema in uiState.cinemas.filter { it.id in withScreenings }) {
        FilterChip(
          selected = uiState.cinemaFilter == cinema.id,
          onClick = { onSelectCinemaFilter(cinema.id) },
          label = { Text(cinema.name) },
          modifier = Modifier.testTag("filter_${cinema.id}"),
        )
      }
    }

    if (uiState.screenings.isEmpty()) {
      EmptyState(
        title = "No screenings found",
        body =
          "Nothing from your watchlist is scheduled at the cinemas you follow. " +
            "KinoSthlm keeps checking in the background and will notify you.",
      )
      return@Column
    }

    val byDay = uiState.screenings.sortedBy { it.screeningTime }.groupBy { it.localDate() }

    LazyColumn(
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      for ((day, screenings) in byDay) {
        item(key = "header-$day") {
          Text(
            DAY_HEADER.format(day),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
          )
        }
        items(screenings, key = { it.id }) { screening ->
          ScheduleCard(screening, onOpenBooking)
        }
      }
    }
  }
}

@Composable
private fun ScheduleCard(screening: Screening, onOpenBooking: (String) -> Unit) {
  Card(
    Modifier.fillMaxWidth().testTag("screening_${screening.id}"),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Column(Modifier.padding(16.dp)) {
      Text(
        screening.movieTitle,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      ScreeningRow(
        when_ = TIME.format(screening.zoned()),
        where = screening.cinemaName,
        detail =
          listOfNotNull(
              screening.auditorium,
              screening.formatTag,
              screening.priceSek?.let { "from $it kr" },
            )
            .joinToString(" · ")
            .ifBlank { null },
        onClick = { onOpenBooking(screening.bookingUrl) },
      )
    }
  }
}

private fun Screening.zoned(): ZonedDateTime =
  ZonedDateTime.ofInstant(Instant.ofEpochMilli(screeningTime), SwedishDates.STOCKHOLM)

private fun Screening.localDate(): LocalDate = zoned().toLocalDate()
