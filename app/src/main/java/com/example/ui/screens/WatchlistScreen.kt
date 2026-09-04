package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocalActivity
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.Screening
import com.example.ui.theme.MinimalAlert
import com.example.ui.theme.MinimalBadgeContainer
import com.example.ui.theme.MinimalBadgeText
import com.example.ui.theme.MinimalBorder
import com.example.ui.theme.MinimalBorderSubtle
import com.example.ui.theme.MinimalDarkBg
import com.example.ui.theme.MinimalOnPrimary
import com.example.ui.theme.MinimalPrimary
import com.example.ui.theme.MinimalPrimaryContainer
import com.example.ui.theme.MinimalSurface
import com.example.ui.theme.MinimalSurfaceElevated
import com.example.ui.theme.MinimalSurfaceVariant
import com.example.ui.theme.MinimalTextMuted
import com.example.ui.theme.MinimalTextPrimary
import com.example.ui.theme.MinimalTextSecondary
import com.example.ui.viewmodel.UiState
import com.example.ui.viewmodel.WatchlistItemWithScreening

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    uiState: UiState,
    onScanNow: () -> Unit,
    onOpenAddDialog: () -> Unit,
    onOpenImdbDialog: () -> Unit,
    onOpenGoogleTvDialog: () -> Unit,
    onToggleShowingSoonFilter: () -> Unit,
    onRemoveMovie: (String) -> Unit
) {
    val context = LocalContext.current
    val showingCount = uiState.watchlistWithScreenings.count { it.nextScreening != null }
    val totalCount = uiState.watchlistWithScreenings.size

    Box(modifier = Modifier.fillMaxSize().background(MinimalDarkBg)) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(12.dp))

                // Clean Minimalism Watchlist Sync Status Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hero_banner_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MinimalSurfaceElevated),
                    border = BorderStroke(1.dp, MinimalBorderSubtle)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "WATCHLIST SYNC",
                                    fontSize = 11.sp,
                                    letterSpacing = 1.2.sp,
                                    color = MinimalPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "$totalCount Movies from IMDb & Google TV",
                                    fontSize = 14.sp,
                                    color = MinimalTextPrimary,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            // Active / Polling status indicator pill
                            Box(
                                modifier = Modifier
                                    .background(MinimalPrimaryContainer, RoundedCornerShape(50))
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                if (uiState.isScanning) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            color = MinimalPrimary,
                                            strokeWidth = 1.5.dp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Scanning",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MinimalPrimary
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "Active",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MinimalPrimary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "$showingCount titles currently scheduled across Stockholm picture houses.",
                            fontSize = 13.sp,
                            color = MinimalTextSecondary
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Quick action buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onOpenImdbDialog,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MinimalSurface),
                                border = BorderStroke(1.dp, MinimalBorder),
                                modifier = Modifier.weight(1f).testTag("sync_imdb_hero_button"),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                            ) {
                                Text("IMDb Sync", fontSize = 12.sp, color = MinimalPrimary, fontWeight = FontWeight.SemiBold)
                            }

                            Button(
                                onClick = onOpenGoogleTvDialog,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MinimalSurface),
                                border = BorderStroke(1.dp, MinimalBorder),
                                modifier = Modifier.weight(1f).testTag("import_gtv_hero_button"),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                            ) {
                                Text("Google TV", fontSize = 12.sp, color = MinimalTextPrimary, fontWeight = FontWeight.SemiBold)
                            }

                            OutlinedButton(
                                onClick = onScanNow,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MinimalPrimary.copy(alpha = 0.6f)),
                                modifier = Modifier.weight(1f).testTag("scan_now_hero_button"),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = MinimalPrimary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Scan", fontSize = 12.sp, color = MinimalPrimary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Section Header & Filters
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "UPCOMING SHOWINGS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = MinimalTextSecondary
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = !uiState.filterShowingSoonOnly,
                            onClick = { if (uiState.filterShowingSoonOnly) onToggleShowingSoonFilter() },
                            label = { Text("All ($totalCount)", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MinimalPrimary,
                                selectedLabelColor = MinimalOnPrimary,
                                containerColor = MinimalSurface,
                                labelColor = MinimalTextSecondary
                            ),
                            border = BorderStroke(1.dp, if (!uiState.filterShowingSoonOnly) MinimalPrimary else MinimalBorder),
                            modifier = Modifier.testTag("filter_all_titles_chip")
                        )

                        FilterChip(
                            selected = uiState.filterShowingSoonOnly,
                            onClick = { if (!uiState.filterShowingSoonOnly) onToggleShowingSoonFilter() },
                            label = { Text("In Sthlm ($showingCount)", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MinimalPrimary,
                                selectedLabelColor = MinimalOnPrimary,
                                containerColor = MinimalSurface,
                                labelColor = MinimalTextSecondary
                            ),
                            border = BorderStroke(1.dp, if (uiState.filterShowingSoonOnly) MinimalPrimary else MinimalBorder),
                            modifier = Modifier.testTag("filter_showing_soon_chip")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            if (uiState.watchlistWithScreenings.isEmpty()) {
                item {
                    EmptyWatchlistView(
                        onAddClick = onOpenAddDialog,
                        onImdbClick = onOpenImdbDialog
                    )
                }
            } else {
                items(
                    items = uiState.watchlistWithScreenings,
                    key = { it.item.id }
                ) { itemWithScreening ->
                    WatchlistItemCard(
                        itemWithScreening = itemWithScreening,
                        onBookClick = { url ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        },
                        onDeleteClick = { onRemoveMovie(itemWithScreening.item.id) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            item {
                Spacer(modifier = Modifier.height(84.dp))
            }
        }

        // Clean Minimal Floating Action Button
        FloatingActionButton(
            onClick = onOpenAddDialog,
            containerColor = MinimalPrimary,
            contentColor = MinimalOnPrimary,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("add_movie_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Movie to Watchlist")
        }
    }
}

@Composable
fun WatchlistItemCard(
    itemWithScreening: WatchlistItemWithScreening,
    onBookClick: (String) -> Unit,
    onDeleteClick: () -> Unit
) {
    val item = itemWithScreening.item
    val nextScreening = itemWithScreening.nextScreening
    var isExpanded by remember { mutableStateOf(false) }

    val hasScreening = nextScreening != null

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MinimalSurface),
        border = BorderStroke(1.dp, MinimalBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("movie_card_${item.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // Poster with clean container
                Box(
                    modifier = Modifier
                        .size(width = 64.dp, height = 96.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MinimalSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (!item.posterUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = item.posterUrl,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.Movie,
                                contentDescription = null,
                                tint = MinimalTextMuted,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = item.source,
                                fontSize = 9.sp,
                                color = MinimalTextMuted,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Movie Info & Badges
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            lineHeight = 20.sp,
                            color = MinimalTextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        // Cinema Badge pill when screening exists
                        if (hasScreening) {
                            val cinemaTag = when {
                                nextScreening!!.cinemaName.contains("Capitol", ignoreCase = true) -> "Capitol"
                                nextScreening.cinemaName.contains("Zita", ignoreCase = true) -> "Bio Zita"
                                nextScreening.cinemaName.contains("Rio", ignoreCase = true) -> "Bio Rio"
                                nextScreening.cinemaName.contains("Skandia", ignoreCase = true) -> "Bio Skandia"
                                nextScreening.cinemaName.contains("Filmstaden", ignoreCase = true) -> "Filmstaden"
                                else -> nextScreening.cinemaName
                            }
                            Surface(
                                color = MinimalBadgeContainer,
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.padding(start = 6.dp)
                            ) {
                                Text(
                                    text = cinemaTag.uppercase(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MinimalBadgeText,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = onDeleteClick,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = "Remove",
                                tint = MinimalTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    if (hasScreening) {
                        Text(
                            text = "${nextScreening!!.formattedDateTime}${if (!nextScreening.auditorium.isNullOrBlank()) " • ${nextScreening.auditorium}" else ""}",
                            fontSize = 13.sp,
                            color = MinimalTextSecondary
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (item.year != null) {
                                Text(text = item.year.toString(), color = MinimalTextSecondary, fontSize = 12.sp)
                            }
                            if (item.director != null) {
                                Text(text = "• ${item.director}", color = MinimalTextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    if (item.imdbRating != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = MinimalPrimary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${item.imdbRating}",
                                color = MinimalPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = item.source,
                                fontSize = 11.sp,
                                color = MinimalTextMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Minimal Action Button
                    if (hasScreening) {
                        Button(
                            onClick = { onBookClick(nextScreening!!.bookingUrl) },
                            colors = ButtonDefaults.buttonColors(containerColor = MinimalPrimary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .testTag("book_button_${nextScreening!!.id}")
                        ) {
                            Text(
                                text = "Book Now",
                                color = MinimalOnPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MinimalBorderSubtle),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp)
                        ) {
                            Text(
                                text = "Radar Scanning Stockholm",
                                color = MinimalTextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Additional screenings toggle if multiple
            if (hasScreening && itemWithScreening.allUpcomingScreenings.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isExpanded = !isExpanded }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "+${itemWithScreening.allUpcomingScreenings.size - 1} more showtimes in Stockholm",
                        fontSize = 11.sp,
                        color = MinimalPrimary,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MinimalPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        HorizontalDivider(
                            color = MinimalBorder,
                            thickness = 0.8.dp,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                        for (s in itemWithScreening.allUpcomingScreenings.drop(1)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "${s.cinemaName} • ${s.formattedDateTime}",
                                        fontSize = 12.sp,
                                        color = MinimalTextPrimary
                                    )
                                    if (!s.formatTag.isNullOrBlank()) {
                                        Text(s.formatTag, fontSize = 10.sp, color = MinimalPrimary)
                                    }
                                }
                                OutlinedButton(
                                    onClick = { onBookClick(s.bookingUrl) },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, MinimalPrimary),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Book", fontSize = 11.sp, color = MinimalPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyWatchlistView(
    onAddClick: () -> Unit,
    onImdbClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        colors = CardDefaults.cardColors(containerColor = MinimalSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MinimalBorder)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MinimalPrimaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Movie,
                    contentDescription = null,
                    tint = MinimalPrimary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Stockholm Cinema Watchlist",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MinimalTextPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Connect your IMDb or Google TV watchlist. Whenever a film is scheduled at Bio Capitol, Bio Rio, Bio Zita, Bio Skandia or Filmstaden, you'll get notified immediately with booking links.",
                color = MinimalTextSecondary,
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onImdbClick,
                colors = ButtonDefaults.buttonColors(containerColor = MinimalPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(42.dp).testTag("empty_state_sync_imdb_btn")
            ) {
                Text("Connect IMDb Watchlist", color = MinimalOnPrimary, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onAddClick,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MinimalBorder),
                modifier = Modifier.fillMaxWidth().height(42.dp).testTag("empty_state_pick_movies_btn")
            ) {
                Text("Add Movie Manually", color = MinimalPrimary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
