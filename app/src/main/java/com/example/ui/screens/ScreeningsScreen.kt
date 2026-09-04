package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Screening
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

@Composable
fun ScreeningsScreen(
    uiState: UiState,
    onSelectCinemaFilter: (String?) -> Unit,
    onScanNow: () -> Unit
) {
    val context = LocalContext.current
    val cinemaFilters = listOf(
        null to "All Stockholm",
        "bio_capitol" to "Bio Capitol",
        "bio_rio" to "Bio Rio",
        "bio_zita" to "Bio Zita",
        "bio_skandia" to "Bio Skandia",
        "filmstaden" to "Filmstaden",
        "bio_aspen" to "Bio Aspen",
        "klarabiografen" to "Klarabiografen",
        "bio_tellus" to "Bio Tellus"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MinimalDarkBg)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Stockholm Screenings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MinimalTextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Scheduled screenings across Stockholm cinemas for your watchlist",
                color = MinimalTextSecondary,
                fontSize = 13.sp
            )
        }

        // Horizontal Cinema Filter Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for ((cinemaId, name) in cinemaFilters) {
                val isSelected = uiState.selectedCinemaFilter == cinemaId
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectCinemaFilter(cinemaId) },
                    label = { Text(name, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MinimalPrimary,
                        selectedLabelColor = MinimalOnPrimary,
                        containerColor = MinimalSurface,
                        labelColor = MinimalTextSecondary
                    ),
                    border = BorderStroke(1.dp, if (isSelected) MinimalPrimary else MinimalBorder)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.screenings.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MinimalSurface),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MinimalBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MinimalPrimaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.EventSeat,
                                contentDescription = null,
                                tint = MinimalPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "No Screenings Found",
                            fontWeight = FontWeight.Bold,
                            color = MinimalTextPrimary,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "No screenings match this filter currently. Add more titles to your watchlist or trigger a cinema scan.",
                            color = MinimalTextSecondary,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onScanNow,
                            colors = ButtonDefaults.buttonColors(containerColor = MinimalPrimary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Text("Scan Stockholm Cinemas", color = MinimalOnPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                items(uiState.screenings, key = { it.id }) { screening ->
                    ScreeningScheduleCard(
                        screening = screening,
                        onBookClick = { url ->
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                item {
                    Spacer(modifier = Modifier.height(84.dp))
                }
            }
        }
    }
}

@Composable
fun ScreeningScheduleCard(
    screening: Screening,
    onBookClick: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MinimalSurface),
        border = BorderStroke(1.dp, MinimalBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("screening_item_${screening.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top Venue Badge & Date
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MinimalBadgeContainer,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = screening.cinemaName.uppercase(),
                        color = MinimalBadgeText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                if (screening.priceSek != null) {
                    Surface(
                        color = MinimalSurfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${screening.priceSek} SEK",
                            color = MinimalTextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Movie Title
            Text(
                text = screening.movieTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MinimalTextPrimary,
                fontSize = 16.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Date, Time & Auditorium
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MinimalPrimary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = screening.formattedDateTime,
                    color = MinimalTextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )

                if (!screening.auditorium.isNullOrBlank()) {
                    Text(
                        text = "•  ${screening.auditorium}",
                        color = MinimalTextMuted,
                        fontSize = 12.sp
                    )
                }
            }

            if (!screening.formatTag.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = MinimalSurfaceVariant,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = screening.formatTag,
                        color = MinimalPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Booking Button with Direct Booking Link
            Button(
                onClick = { onBookClick(screening.bookingUrl) },
                colors = ButtonDefaults.buttonColors(containerColor = MinimalPrimary),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .testTag("book_ticket_btn_${screening.id}")
            ) {
                Icon(
                    Icons.Default.ConfirmationNumber,
                    contentDescription = null,
                    tint = MinimalOnPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Book Tickets at ${screening.cinemaName}",
                    color = MinimalOnPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MinimalOnPrimary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

