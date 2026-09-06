package se.kinosthlm.app.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import se.kinosthlm.app.data.model.WatchlistItem

/** Shown wherever a list has nothing in it, so the screen explains itself rather than sitting blank. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
  Column(
    modifier.fillMaxWidth().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    Text(
      body,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

/** One showing: time, venue, and whatever extra detail the cinema actually gave us. */
@Composable
fun ScreeningRow(when_: String, where: String, detail: String?, onClick: (() -> Unit)?) {
  Row(
    Modifier.fillMaxWidth().padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(when_, style = MaterialTheme.typography.bodyMedium)
      Text(
        listOfNotNull(where, detail).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    // No onClick means the caller wants the row inert (multi-selection), so the button goes
    // rather than sitting there looking tappable.
    if (onClick != null) {
      TextButton(onClick = onClick) { Text("Tickets") }
    }
  }
}

/**
 * A thin thumb on the trailing edge showing roughly where a [LazyListState] is in its list.
 *
 * The Compose Foundation version this project pins predates the built-in scrollbar modifier, so
 * this is a small self-contained one: track height and thumb position both come straight from
 * [LazyListState.layoutInfo], no extra state to keep in sync. Call it as the last child of a
 * [androidx.compose.foundation.layout.Box] wrapping the list, so it overlays on top.
 */
@Composable
fun BoxScope.VerticalScrollbar(listState: LazyListState, modifier: Modifier = Modifier) {
  val layoutInfo = listState.layoutInfo
  val totalItems = layoutInfo.totalItemsCount
  val visibleCount = layoutInfo.visibleItemsInfo.size
  // Nothing to scroll: everything already fits on screen.
  if (totalItems == 0 || visibleCount >= totalItems) return

  BoxWithConstraints(modifier.align(Alignment.CenterEnd).fillMaxHeight().width(4.dp)) {
    val trackHeight = maxHeight
    val thumbHeight = (trackHeight * visibleCount / totalItems).coerceAtLeast(24.dp)
    val scrollableRange = (totalItems - visibleCount).coerceAtLeast(1)
    val progress = (listState.firstVisibleItemIndex.toFloat() / scrollableRange).coerceIn(0f, 1f)
    val offset = (trackHeight - thumbHeight) * progress

    Box(
      Modifier.offset(y = offset)
        .height(thumbHeight)
        .width(4.dp)
        .clip(RoundedCornerShape(2.dp))
        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
    )
  }
}

/**
 * A poster-shaped grey box: pulsing while TMDB is being asked for the real one, and a static
 * "no image" square once we know there is nothing coming.
 *
 * The distinction matters. A film TMDB has no artwork for used to pulse forever, which reads as
 * a request stuck rather than an answer received. Reserving the space either way also stops the
 * row reflowing the moment a poster arrives, which is what made the list jump around during a
 * large import.
 */
@Composable
fun PosterPlaceholder(modifier: Modifier = Modifier, loading: Boolean = true) {
  if (!loading) {
    Box(
      modifier.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        Icons.Outlined.ImageNotSupported,
        contentDescription = "No poster available",
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxSize(0.4f),
      )
    }
    return
  }

  val transition = rememberInfiniteTransition(label = "poster-placeholder")
  val alpha by
    transition.animateFloat(
      initialValue = 0.10f,
      targetValue = 0.28f,
      animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
      label = "poster-placeholder-alpha",
    )
  Box(modifier.background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)))
}

/**
 * The one way this app offers a link out to a film.
 *
 * Always IMDb where we have the id — that is the page a person means when they say "look it up" —
 * and TMDB only as the fallback for a film we have yet to attach an IMDb id to. TMDB is how the
 * app identifies films internally; it is not what gets put in front of the user by choice.
 */
@Composable
fun ExternalLinkButton(
  imdbId: String?,
  tmdbId: Int?,
  onOpenLink: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val url =
    when {
      imdbId != null -> "https://www.imdb.com/title/$imdbId/"
      tmdbId != null -> "https://www.themoviedb.org/movie/$tmdbId"
      else -> return
    }
  OutlinedButton(onClick = { onOpenLink(url) }, modifier = modifier) {
    Icon(
      Icons.AutoMirrored.Outlined.OpenInNew,
      contentDescription = null,
      modifier = Modifier.size(18.dp),
    )
    Spacer(Modifier.width(6.dp))
    Text(if (imdbId != null) "IMDb" else "TMDB")
  }
}

/** Human-readable label for a [WatchlistItem] provenance source id. */
fun sourceLabel(source: String): String =
  when (source) {
    WatchlistItem.SOURCE_TRAKT -> "Trakt"
    WatchlistItem.SOURCE_IMDB -> "IMDb"
    WatchlistItem.SOURCE_GOOGLE_TV -> "Google TV"
    WatchlistItem.SOURCE_LETTERBOXD -> "Letterboxd"
    WatchlistItem.SOURCE_MANUAL -> "Manual Add"
    else -> source
  }

/**
 * "1927 · Added by hand" style caption. Null when there is nothing worth a whole line — a
 * plainly-sourced film with no year prints its source as a [SourceTags] chip instead.
 */
fun watchlistDescriptor(item: WatchlistItem, sources: List<String>): String? {
  val fallback = if (sources.isEmpty()) "Manual Add" else null
  return listOfNotNull(item.year?.toString(), fallback).joinToString(" · ").ifBlank { null }
}

/**
 * A film's provenance as small bordered tags — "the sources as small bordered text tags on each
 * watchlist entry" the board asked for, rather than plain comma-joined text.
 */
@Composable
fun SourceTags(sources: List<String>, modifier: Modifier = Modifier) {
  if (sources.isEmpty()) return
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    for (source in sources) {
      Text(
        sourceLabel(source),
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
