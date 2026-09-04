package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    uiState: UiState,
    onToggleAutoPolling: (Boolean) -> Unit,
    onSetPollingInterval: (Long) -> Unit,
    onScanNow: () -> Unit,
    onSendTestNotification: () -> Unit,
    onOpenImdbDialog: () -> Unit,
    onOpenGoogleTvDialog: () -> Unit
) {
    val context = LocalContext.current
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    val intervals = listOf(1L to "1 hour", 2L to "2 hours", 6L to "6 hours", 12L to "12 hours", 24L to "Daily")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MinimalDarkBg)
            .padding(horizontal = 16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Automation & Alerts",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MinimalTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Configure automated background polling and push notification alerts for Stockholm cinema releases.",
                color = MinimalTextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 1. Automated Polling Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MinimalSurface),
                border = BorderStroke(1.dp, MinimalBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("automated_polling_card")
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
                                    Icons.Default.Autorenew,
                                    contentDescription = null,
                                    tint = MinimalPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Automated Background Poller",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = MinimalTextPrimary
                                )
                                Text(
                                    text = if (uiState.isAutoPollingEnabled) "Active in background" else "Paused",
                                    fontSize = 12.sp,
                                    color = if (uiState.isAutoPollingEnabled) MinimalPrimary else MinimalTextMuted
                                )
                            }
                        }

                        Switch(
                            checked = uiState.isAutoPollingEnabled,
                            onCheckedChange = onToggleAutoPolling,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MinimalPrimary,
                                checkedTrackColor = MinimalPrimaryContainer,
                                uncheckedThumbColor = MinimalTextMuted,
                                uncheckedTrackColor = MinimalSurfaceVariant
                            ),
                            modifier = Modifier.testTag("auto_polling_toggle")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "The app uses Android WorkManager to periodically poll cinema schedules even when closed. If a movie on your watchlist has a screening, it immediately triggers a push notification with direct booking links.",
                        fontSize = 12.sp,
                        color = MinimalTextSecondary,
                        lineHeight = 17.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Polling Interval:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MinimalTextPrimary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for ((hours, label) in intervals) {
                            val isSelected = uiState.pollingIntervalHours == hours
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSetPollingInterval(hours) },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MinimalPrimary,
                                    selectedLabelColor = MinimalOnPrimary,
                                    containerColor = MinimalSurfaceVariant,
                                    labelColor = MinimalTextSecondary
                                ),
                                border = BorderStroke(1.dp, if (isSelected) MinimalPrimary else MinimalBorder)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Manual Scan Button
                    Button(
                        onClick = onScanNow,
                        enabled = !uiState.isScanning,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MinimalPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .testTag("manual_scan_cinemas_btn")
                    ) {
                        if (uiState.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MinimalOnPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Polling Stockholm Cinemas...", color = MinimalOnPrimary, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                tint = MinimalOnPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan Stockholm Cinemas Now", color = MinimalOnPrimary, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (uiState.lastScanReport != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            color = MinimalSurfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(uiState.lastScanReport.timestamp))
                                Text(
                                    text = "Last scan ($timeStr):",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = MinimalPrimary
                                )
                                Text(
                                    text = uiState.lastScanReport.statusMessage,
                                    fontSize = 12.sp,
                                    color = MinimalTextPrimary
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 2. Push Notifications & Booking Links Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MinimalSurface),
                border = BorderStroke(1.dp, MinimalBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("push_notifications_card")
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
                                    Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = MinimalPrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Screening Push Notifications",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = MinimalTextPrimary
                                )
                                Text(
                                    text = if (hasNotificationPermission) "Active with booking links" else "Permission needed",
                                    fontSize = 12.sp,
                                    color = if (hasNotificationPermission) MinimalPrimary else MinimalTextMuted
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Notifications include movie title, cinema name, screening date/time, and a direct booking link to the cinema's booking portal.",
                        fontSize = 12.sp,
                        color = MinimalTextSecondary,
                        lineHeight = 17.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Button(
                                onClick = {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MinimalPrimary),
                                modifier = Modifier.weight(1f).height(40.dp).testTag("grant_notification_permission_btn")
                            ) {
                                Text("Grant Permission", color = MinimalOnPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = onSendTestNotification,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MinimalPrimary.copy(alpha = 0.6f)),
                            modifier = Modifier.weight(1f).height(40.dp).testTag("test_push_notification_btn")
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = null, tint = MinimalPrimary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test Notification", color = MinimalPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 3. Watchlist Sources Sync
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MinimalSurface),
                border = BorderStroke(1.dp, MinimalBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Connected Watchlists",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = MinimalTextPrimary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // IMDb Card
                    Surface(
                        color = MinimalSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MinimalBorderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Link, contentDescription = null, tint = MinimalPrimary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("IMDb Watchlist", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MinimalTextPrimary)
                                    Text("Public list URL or user ID sync", fontSize = 11.sp, color = MinimalTextMuted)
                                }
                            }
                            OutlinedButton(
                                onClick = onOpenImdbDialog,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MinimalPrimary.copy(alpha = 0.5f))
                            ) {
                                Text("Sync", fontSize = 11.sp, color = MinimalPrimary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Google TV Card
                    Surface(
                        color = MinimalSurfaceElevated,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MinimalBorderSubtle),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tv, contentDescription = null, tint = MinimalPrimary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Google TV Watchlist", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MinimalTextPrimary)
                                    Text("Import saved titles & shared lists", fontSize = 11.sp, color = MinimalTextMuted)
                                }
                            }
                            OutlinedButton(
                                onClick = onOpenGoogleTvDialog,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MinimalPrimary.copy(alpha = 0.5f))
                            ) {
                                Text("Import", fontSize = 11.sp, color = MinimalPrimary)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 4. Dispatched Notification Log
        if (uiState.notificationLogs.isNotEmpty()) {
            item {
                Text(
                    text = "Recent Push Notification Alerts",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = MinimalTextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(uiState.notificationLogs.take(5)) { log ->
                val timeStr = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(log.notifiedAt))
                Surface(
                    color = MinimalSurface,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MinimalBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(log.movieTitle, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MinimalTextPrimary)
                            Text("${log.cinemaName} • Dispatched $timeStr", fontSize = 11.sp, color = MinimalTextSecondary)
                        }

                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(log.bookingUrl))
                                context.startActivity(intent)
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MinimalPrimary.copy(alpha = 0.6f)),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text("Open Link", fontSize = 11.sp, color = MinimalPrimary)
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(84.dp))
        }
    }
}

