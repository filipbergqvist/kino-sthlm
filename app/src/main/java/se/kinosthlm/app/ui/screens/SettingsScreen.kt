package se.kinosthlm.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.kinosthlm.app.notification.NotificationHelper
import se.kinosthlm.app.ui.viewmodel.TraktState
import se.kinosthlm.app.ui.viewmodel.UiState

/** Sources, schedule and alerts. */
@Composable
fun SettingsScreen(
  uiState: UiState,
  onConnectTrakt: () -> Unit,
  onCancelTrakt: () -> Unit,
  onDisconnectTrakt: () -> Unit,
  onImportImdbCsv: () -> Unit,
  onImportGoogleTvCsv: () -> Unit,
  onImportImdbList: () -> Unit,
  onSetAutoSync: (Boolean) -> Unit,
  onSetInterval: (Long) -> Unit,
  onSetNotifications: (Boolean) -> Unit,
  onSyncNow: () -> Unit,
  onResolveTitles: () -> Unit,
  onTestNotification: () -> Unit,
  onOpenUrl: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier.fillMaxWidth(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Section("Watchlist sources") {
        // Trakt is the only provider that can refresh on its own, so it leads.
        when (val state = uiState.traktState) {
          is TraktState.Connected -> {
            SettingRow("Trakt", "Connected — syncs automatically") {
              TextButton(onClick = onDisconnectTrakt) { Text("Disconnect") }
            }
          }
          is TraktState.AwaitingCode -> {
            Text(
              "Go to ${state.url} and enter this code:",
              style = MaterialTheme.typography.bodyMedium,
            )
            Text(
              state.code,
              style = MaterialTheme.typography.headlineMedium,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(vertical = 8.dp).testTag("trakt_code"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick = { onOpenUrl(state.url) }) { Text("Open trakt.tv") }
              TextButton(onClick = onCancelTrakt) { Text("Cancel") }
            }
          }
          TraktState.Disconnected -> {
            if (uiState.traktConfigured) {
              SettingRow("Trakt", "Automatic sync of your watchlist") {
                TextButton(
                  onClick = onConnectTrakt,
                  modifier = Modifier.testTag("connect_trakt"),
                ) {
                  Text("Connect")
                }
              }
            } else {
              Text(
                "Trakt is not configured in this build. Add TRAKT_CLIENT_ID and " +
                  "TRAKT_CLIENT_SECRET to local.properties and rebuild — see the README.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        SettingRow("IMDb", "Import your watchlist CSV export") {
          TextButton(onClick = onImportImdbCsv) { Text("Import") }
        }
        SettingRow("IMDb public list", "Best effort — IMDb may block or change this") {
          TextButton(onClick = onImportImdbList) { Text("Fetch") }
        }
        SettingRow("Google TV", "Import a Google Takeout CSV") {
          TextButton(onClick = onImportGoogleTvCsv) { Text("Import") }
        }
      }
    }

    item {
      Section("Sync") {
        SettingRow("Background sync", "Check cinemas automatically") {
          Switch(
            checked = uiState.autoSyncEnabled,
            onCheckedChange = onSetAutoSync,
            modifier = Modifier.testTag("toggle_auto_sync"),
          )
        }
        if (uiState.autoSyncEnabled) {
          Text("How often", style = MaterialTheme.typography.bodyMedium)
          Row(
            Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            for (hours in listOf(3L, 6L, 12L, 24L)) {
              FilterChip(
                selected = uiState.syncIntervalHours == hours,
                onClick = { onSetInterval(hours) },
                label = { Text("${hours}h") },
              )
            }
          }
        }
        Text(
          if (uiState.lastSyncAt > 0L) {
            "Last sync ${NotificationHelper.formatTime(uiState.lastSyncAt)} — " +
              uiState.lastSyncSummary
          } else {
            "Not synced yet"
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
          Modifier.padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            onClick = onSyncNow,
            enabled = !uiState.isSyncing,
            modifier = Modifier.testTag("sync_now"),
          ) {
            Text(if (uiState.isSyncing) "Syncing…" else "Sync now")
          }
          // Google TV titles arrive bare; this puts IMDb ids and years on them in one pass
          // instead of the sync chipping away at them a hundred at a time.
          OutlinedButton(
            onClick = onResolveTitles,
            enabled = !uiState.isResolving && uiState.tmdbConfigured,
            modifier = Modifier.testTag("resolve_titles"),
          ) {
            Text(if (uiState.isResolving) "Identifying…" else "Identify titles")
          }
        }
        if (!uiState.tmdbConfigured) {
          Text(
            "No TMDB API key in this build — titles, posters, descriptions and manual add all " +
              "need one. See the README's \"API keys\" section.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp).testTag("tmdb_not_configured"),
          )
        }
      }
    }

    // Name the sources that broke rather than hiding the failure behind a total.
    if (uiState.failedSources.isNotEmpty()) {
      item {
        Section("Problems") {
          for (failure in uiState.failedSources) {
            Text(
              "${failure.label}: ${failure.error}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
          Text(
            "A cinema's website may have changed. Screenings already found are kept.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
          )
        }
      }
    }

    item {
      Section("Notifications") {
        SettingRow("Alerts", "Notify when a watchlisted film is scheduled") {
          Switch(checked = uiState.notificationsEnabled, onCheckedChange = onSetNotifications)
        }
        OutlinedButton(onClick = onTestNotification, modifier = Modifier.testTag("test_notif")) {
          Text("Send a test notification")
        }
      }
    }

    item {
      Section("About") {
        Text(
          "KinoSthlm watches Stockholm cinema schedules for films on your watchlist. " +
            "Everything runs on this device — there is no server and no account.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Card(
    Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Column(Modifier.padding(16.dp)) {
      Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
      )
      content()
    }
  }
}

@Composable
private fun SettingRow(title: String, subtitle: String, action: @Composable () -> Unit) {
  Row(
    Modifier.fillMaxWidth().padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(
        subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    action()
  }
}
