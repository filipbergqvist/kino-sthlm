package se.kinosthlm.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.kinosthlm.app.notification.NotificationHelper
import se.kinosthlm.app.ui.viewmodel.SyncCadence
import se.kinosthlm.app.ui.viewmodel.TraktState
import se.kinosthlm.app.ui.viewmodel.UiState

/** Where bug reports and feature requests go. Public, so nothing needs an account to read. */
private const val ISSUES_URL = "https://github.com/filipbergqvist/kino-sthlm/issues"

/** Sources, schedule and alerts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  uiState: UiState,
  onConnectTrakt: () -> Unit,
  onCancelTrakt: () -> Unit,
  onDisconnectTrakt: () -> Unit,
  onImportImdbCsv: () -> Unit,
  onImportGoogleTvCsv: () -> Unit,
  onImportImdbList: () -> Unit,
  onBatchAdd: () -> Unit,
  onImportBackup: () -> Unit,
  onExportCsv: () -> Unit,
  onSetAutoSync: (Boolean) -> Unit,
  onSetInterval: (Long) -> Unit,
  onSetSyncHour: (Int) -> Unit,
  onSetHorizon: (Long) -> Unit,
  onSetTmdbKey: (String) -> Unit,
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
            val clipboard = LocalClipboardManager.current
            Text(
              "Go to ${state.url} and enter this code:",
              style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
              // Selectable as well as copyable: eight characters is exactly long enough to
              // mistype, and the code stays valid now, so it is worth getting onto the clipboard.
              SelectionContainer {
                Text(
                  state.code,
                  style = MaterialTheme.typography.headlineMedium,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(vertical = 8.dp).testTag("trakt_code"),
                )
              }
              IconButton(
                onClick = { clipboard.setText(AnnotatedString(state.code)) },
                modifier = Modifier.testTag("copy_trakt_code"),
              ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy code")
              }
            }
            Text(
              "The code stays valid while you finish — losing signal will not change it.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
              Modifier.padding(top = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
        SettingRow("Paste a list", "For a watchlist kept somewhere with no export") {
          TextButton(onClick = onBatchAdd, modifier = Modifier.testTag("batch_add")) { Text("Paste") }
        }
        SettingRow("KinoSthlm backup", "A CSV this app exported, from any device") {
          TextButton(onClick = onImportBackup, modifier = Modifier.testTag("import_backup")) {
            Text("Import")
          }
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
          // Cinema programmes change once a day at most, so the options start at daily. The old
          // three- and six-hourly choices only re-read the same pages, which is why they came
          // with a note asking people to be considerate — better to make the choices themselves
          // considerate than to ask.
          Text("How often", style = MaterialTheme.typography.bodyMedium)
          val cadence = SyncCadence.of(uiState.syncIntervalHours)
          FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            for (option in SyncCadence.entries) {
              FilterChip(
                selected = cadence == option,
                onClick = { onSetInterval(option.hours) },
                label = { Text(option.label) },
                modifier = Modifier.testTag("cadence_${option.name}"),
              )
            }
          }

          Text("Around what time", style = MaterialTheme.typography.bodyMedium)
          FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            for (hour in listOf(4, 8, 12, 18, 22)) {
              FilterChip(
                selected = uiState.syncHourOfDay == hour,
                onClick = { onSetSyncHour(hour) },
                label = { Text("%02d:00".format(hour)) },
                modifier = Modifier.testTag("sync_hour_$hour"),
              )
            }
          }
          Text(
            "Android decides the exact moment — it will wait for a network and may hold off " +
              "while the phone is asleep.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        // Repertory cinemas post months ahead; too short a window silently hides exactly the
        // one-off screenings worth knowing about early.
        Text("How far ahead to look", style = MaterialTheme.typography.bodyMedium)
        Row(
          Modifier.padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          for (days in listOf(21L, 30L, 60L, 90L)) {
            FilterChip(
              selected = uiState.horizonDays == days,
              onClick = { onSetHorizon(days) },
              label = { Text("${days}d") },
              modifier = Modifier.testTag("horizon_$days"),
            )
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
          // Identify first, sync second — the order the work actually happens in, and the order
          // you would press them: a film has to be identified before a cinema can be asked
          // about it.
          OutlinedButton(
            onClick = onResolveTitles,
            enabled = !uiState.isResolving && uiState.tmdbConfigured,
            modifier = Modifier.testTag("resolve_titles"),
          ) {
            Text(if (uiState.isResolving) "Identifying…" else "Identify titles")
          }
          OutlinedButton(
            onClick = onSyncNow,
            enabled = !uiState.isSyncing,
            modifier = Modifier.testTag("sync_now"),
          ) {
            Text(if (uiState.isSyncing) "Syncing…" else "Sync now")
          }
        }
        // The same bar the watchlist shows, right under the buttons that start the work — you
        // should not have to change tabs to find out whether the thing you just pressed is
        // running.
        syncStatusText(uiState)?.let { status ->
          Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
          )
          SyncProgressBar(uiState, modifier = Modifier.padding(top = 4.dp))
        }
        if (!uiState.tmdbConfigured) {
          Text(
            "No TMDB API key — titles, posters, descriptions and manual add all need one. " +
              "Add yours below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp).testTag("tmdb_not_configured"),
          )
        }
      }
    }

    item { TmdbKeySection(uiState = uiState, onSetKey = onSetTmdbKey, onOpenUrl = onOpenUrl) }

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
        SettingRow("Alerts", "Notify when a tracked film is scheduled") {
          Switch(checked = uiState.notificationsEnabled, onCheckedChange = onSetNotifications)
        }
        OutlinedButton(onClick = onTestNotification, modifier = Modifier.testTag("test_notif")) {
          Text("Send a test notification")
        }
      }
    }

    // Last thing before About: exporting is what you do once, when leaving or backing up, not
    // something to scroll past every time you come here to change a setting.
    item {
      Section("Export") {
        Text(
          "Write your watchlist out as a CSV, ready to import into Trakt — or back into " +
            "KinoSthlm on another device.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
          onClick = onExportCsv,
          modifier = Modifier.padding(top = 8.dp).testTag("export_csv"),
        ) {
          Text("Export CSV")
        }
      }
    }

    // Between Export and About, as asked. A link rather than an in-app form: posting to the
    // tracker needs a GitHub account and a token, and neither belongs in an app with no server
    // and no accounts — the browser already has the login.
    item {
      Section("Feedback") {
        Text(
          "Found a bug, or want something added? The issue tracker is the place — it is public, " +
            "so you can see what is already known.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = { onOpenUrl("$ISSUES_URL/new?labels=bug") },
            modifier = Modifier.testTag("report_bug"),
          ) {
            Text("Report a bug")
          }
          OutlinedButton(
            onClick = { onOpenUrl("$ISSUES_URL/new?labels=enhancement") },
            modifier = Modifier.testTag("request_feature"),
          ) {
            Text("Request a feature")
          }
        }
        TextButton(onClick = { onOpenUrl(ISSUES_URL) }) { Text("Browse open issues") }
      }
    }

    item {
      Section("About") {
        Text(
          "KinoSthlm tracks Stockholm cinema schedules for the films on your list. " +
            "Everything runs on this device — there is no server and no account.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/**
 * Somewhere to put your own TMDB key.
 *
 * The key a build ships with is anonymous and shared by everyone running that build, so it is one
 * rate limit between all of them. Pasting your own spends your quota instead — and it is the only
 * way to use a build that shipped without a key at all.
 */
@Composable
private fun TmdbKeySection(uiState: UiState, onSetKey: (String) -> Unit, onOpenUrl: (String) -> Unit) {
  Section("TMDB") {
    var draft by remember(uiState.tmdbKey) { mutableStateOf(uiState.tmdbKey) }

    Text(
      if (uiState.tmdbKey.isNotBlank()) {
        "Using your own key."
      } else if (uiState.tmdbConfigured) {
        "Using this build's shared key. Paste your own to spend your own quota instead."
      } else {
        "This build has no key of its own. Paste one to enable posters, descriptions, title " +
          "identification and manual add."
      },
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
      value = draft,
      onValueChange = { draft = it },
      label = { Text("TMDB API key") },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("tmdb_key_input"),
    )

    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(
        onClick = { onSetKey(draft.trim()) },
        enabled = draft.trim() != uiState.tmdbKey,
        modifier = Modifier.testTag("save_tmdb_key"),
      ) {
        Text("Save")
      }
      if (uiState.tmdbKey.isNotBlank()) {
        TextButton(
          onClick = {
            draft = ""
            onSetKey("")
          },
          modifier = Modifier.testTag("clear_tmdb_key"),
        ) {
          Text("Clear")
        }
      }
      TextButton(onClick = { onOpenUrl("https://www.themoviedb.org/settings/api") }) {
        Text("Get a key")
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
