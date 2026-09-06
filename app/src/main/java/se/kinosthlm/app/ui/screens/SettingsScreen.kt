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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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

/** The public repository. Everything user-facing that points at GitHub starts here. */
private const val REPO_URL = "https://github.com/filipbergqvist/kino-sthlm"

/** Where bug reports and feature requests go. Public, so nothing needs an account to read. */
private const val ISSUES_URL = "$REPO_URL/issues"

/** Sources, schedule and alerts. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
  var showTimePicker by remember { mutableStateOf(false) }

  if (showTimePicker) {
    SyncTimePickerDialog(
      initialHour = uiState.syncHourOfDay,
      onDismiss = { showTimePicker = false },
      onConfirm = {
        onSetSyncHour(it)
        showTimePicker = false
      },
    )
  }

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
      }
    }

    item {
      Section("Sync") {
        // Ordered the way you read it: what happened, what we look for, how to do it now, and
        // only then the automatic schedule. "How far ahead to look" applies to every sync, manual
        // included, which is why it sat oddly above a toggle it does not belong to.
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

        // Repertory cinemas post months ahead; too short a window silently hides exactly the
        // one-off screenings worth knowing about early.
        Text(
          "How far ahead to look",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(top = 12.dp),
        )
        FlowRow(
          Modifier.fillMaxWidth().padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
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

        HorizontalDivider(Modifier.padding(top = 16.dp, bottom = 8.dp))

        SettingRow("Background sync", "Check cinemas automatically") {
          Switch(
            checked = uiState.autoSyncEnabled,
            onCheckedChange = onSetAutoSync,
            modifier = Modifier.testTag("toggle_auto_sync"),
          )
        }
        if (uiState.autoSyncEnabled) {
          // Indented, so it reads as belonging to the switch above rather than as two more
          // settings of its own — which is what made "How far ahead to look" look like part of
          // the schedule when it applies to every sync.
          Column(Modifier.padding(start = 16.dp)) {
            // Cinema programmes change once a day at most, so the options start at daily. The
            // old three- and six-hourly choices only re-read the same pages, which is why they
            // came with a note asking people to be considerate — better to make the choices
            // themselves considerate than to ask.
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

            SettingRow(
              "Around what time",
              "%02d:00 — Android may hold off until the phone is awake and online"
                .format(uiState.syncHourOfDay),
            ) {
              TextButton(
                onClick = { showTimePicker = true },
                modifier = Modifier.testTag("pick_sync_hour"),
              ) {
                Text("%02d:00".format(uiState.syncHourOfDay))
              }
            }
          }
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
      // Backups, not sources. Restoring your own file is not connecting a watchlist provider —
      // nothing keeps it up to date afterwards — so it belongs beside the export that produced
      // it rather than among Trakt and IMDb.
      Section("Backups") {
        Text(
          "Write your watchlist out as a CSV, ready to import into Trakt — or back into " +
            "KinoSthlm on another device.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
          Modifier.padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(onClick = onExportCsv, modifier = Modifier.testTag("export_csv")) {
            Text("Export CSV")
          }
          OutlinedButton(
            onClick = onImportBackup,
            modifier = Modifier.testTag("import_backup"),
          ) {
            Text("Restore a backup")
          }
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
            "so you can see what is already known. Each button opens a template with the " +
            "questions worth answering.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
          Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          OutlinedButton(
            onClick = { onOpenUrl("$ISSUES_URL/new?template=bug.yml") },
            modifier = Modifier.testTag("report_bug"),
          ) {
            Text("Report a bug")
          }
          OutlinedButton(
            onClick = { onOpenUrl("$ISSUES_URL/new?template=feature.yml") },
            modifier = Modifier.testTag("request_feature"),
          ) {
            Text("Suggest a feature")
          }
          OutlinedButton(
            onClick = { onOpenUrl("$ISSUES_URL/new?template=add-cinema.yml") },
            modifier = Modifier.testTag("add_cinema_request"),
          ) {
            Text("Add a cinema")
          }
          OutlinedButton(
            onClick = { onOpenUrl("$ISSUES_URL/new?template=add-provider.yml") },
            modifier = Modifier.testTag("add_provider_request"),
          ) {
            Text("Add a watchlist")
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

        Text(
          "Open source under the MIT licence.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )

        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = { onOpenUrl(REPO_URL) },
            modifier = Modifier.testTag("open_github"),
          ) {
            Text("View on GitHub")
          }
          OutlinedButton(onClick = { onOpenUrl("$REPO_URL/blob/master/LICENSE") }) {
            Text("MIT licence")
          }
        }

        // Attribution the licences actually ask for. TMDB's terms require the acknowledgement
        // in so many words; the rest are Apache 2.0 or MIT and ask only that they are named.
        Text(
          "Film data and posters from TMDB. This product uses the TMDB API but is not endorsed " +
            "or certified by TMDB. Watchlist syncing uses the Trakt API. Screenings come from " +
            "each cinema's own website or booking system.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 12.dp),
        )
        Text(
          "Built with Jetpack Compose, Room and WorkManager (Apache 2.0), OkHttp and Moshi " +
            "(Apache 2.0), Jsoup (MIT) and Coil (Apache 2.0).",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { onOpenUrl("https://www.themoviedb.org/") }) { Text("TMDB") }
          TextButton(onClick = { onOpenUrl("https://trakt.tv/") }) { Text("Trakt") }
        }
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

    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        if (uiState.tmdbKey.isNotBlank()) {
          "Using your own key."
        } else if (uiState.tmdbConfigured) {
          // Named for what it costs rather than what it is: everyone on this build shares one
          // rate limit, so the reason to paste your own is that it stops you throttling each
          // other, not that a key is missing.
          "You are currently using a shared TMDB access key. This can lead to throttling. For " +
            "better performance for you and your peers, consider providing your own API key here."
        } else {
          "This build has no key of its own. Paste one to enable posters, descriptions, title " +
            "identification and manual add."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
      )
      IconButton(
        onClick = { onOpenUrl("https://developer.themoviedb.org/docs/getting-started") },
        modifier = Modifier.testTag("tmdb_key_help"),
      ) {
        Icon(Icons.Outlined.Info, contentDescription = "How to get a TMDB API key")
      }
    }

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

/**
 * Android's own clock picker for the sync hour.
 *
 * A row of five preset chips was quicker to build and worse to use: the whole point of choosing
 * a time is that yours is not one of five. Minutes are deliberately discarded — WorkManager
 * treats the hour as a target it may miss by a long way, so offering minute precision would
 * promise something the platform cannot keep.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncTimePickerDialog(
  initialHour: Int,
  onDismiss: () -> Unit,
  onConfirm: (Int) -> Unit,
) {
  val state = rememberTimePickerState(initialHour = initialHour, initialMinute = 0, is24Hour = true)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Sync around") },
    text = { TimePicker(state = state, modifier = Modifier.testTag("sync_time_picker")) },
    confirmButton = {
      TextButton(onClick = { onConfirm(state.hour) }, modifier = Modifier.testTag("confirm_time")) {
        Text("Set")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
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
