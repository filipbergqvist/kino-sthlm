package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Cinema
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
fun CinemasScreen(
    uiState: UiState,
    onToggleCinema: (cinemaId: String, isEnabled: Boolean) -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MinimalDarkBg)
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Stockholm Cinemas Monitored",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MinimalTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Automated polling monitors these Stockholm picture houses for titles on your watchlist.",
                color = MinimalTextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        items(uiState.cinemas, key = { it.id }) { cinema ->
            CinemaVenueCard(
                cinema = cinema,
                onToggle = { enabled -> onToggleCinema(cinema.id, enabled) },
                onOpenWebsite = { url ->
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

@Composable
fun CinemaVenueCard(
    cinema: Cinema,
    onToggle: (Boolean) -> Unit,
    onOpenWebsite: (String) -> Unit
) {
    val borderColor = if (cinema.isEnabled) MinimalBorder else MinimalBorderSubtle

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MinimalSurface),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cinema_venue_${cinema.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(MinimalPrimaryContainer, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Business,
                            contentDescription = null,
                            tint = MinimalPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = cinema.name,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            color = MinimalTextPrimary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = MinimalTextMuted,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = cinema.district,
                                color = MinimalTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Switch(
                    checked = cinema.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MinimalPrimary,
                        checkedTrackColor = MinimalPrimaryContainer,
                        uncheckedThumbColor = MinimalTextMuted,
                        uncheckedTrackColor = MinimalSurfaceVariant
                    ),
                    modifier = Modifier.testTag("toggle_cinema_${cinema.id}")
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = cinema.specialty,
                color = MinimalTextSecondary,
                fontSize = 13.sp,
                lineHeight = 17.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "📍 ${cinema.address}",
                color = MinimalTextMuted,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = if (cinema.activeScreeningsCount > 0) MinimalBadgeContainer else MinimalSurfaceVariant,
                    shape = RoundedCornerShape(50)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (cinema.activeScreeningsCount > 0) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MinimalBadgeText,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${cinema.activeScreeningsCount} screenings matched",
                                color = MinimalBadgeText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                text = if (cinema.isEnabled) "Actively polled" else "Polling paused",
                                color = MinimalTextMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = { onOpenWebsite(cinema.websiteUrl) },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MinimalBorder),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("open_site_${cinema.id}")
                ) {
                    Text("Official Site", fontSize = 11.sp, color = MinimalPrimary, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MinimalPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

