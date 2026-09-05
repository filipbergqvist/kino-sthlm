package se.kinosthlm.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "kinosthlm_settings")

/**
 * User settings that must survive process death.
 *
 * These previously lived in ViewModel state, which meant the sync interval silently reverted
 * whenever Android reclaimed the process — while WorkManager kept running the old schedule.
 */
class SettingsStore(private val context: Context) {

  val autoSyncEnabled: Flow<Boolean> =
    context.dataStore.data.map { it[KEY_AUTO_SYNC] ?: true }

  val syncIntervalHours: Flow<Long> =
    context.dataStore.data.map { it[KEY_INTERVAL_HOURS] ?: DEFAULT_INTERVAL_HOURS }

  /** How far ahead to look for screenings. Wider means earlier warning, more requests. */
  val horizonDays: Flow<Long> = context.dataStore.data.map { it[KEY_HORIZON_DAYS] ?: DEFAULT_HORIZON_DAYS }

  val notificationsEnabled: Flow<Boolean> =
    context.dataStore.data.map { it[KEY_NOTIFICATIONS] ?: true }

  val lastSyncAt: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_SYNC] ?: 0L }

  val lastSyncSummary: Flow<String> = context.dataStore.data.map { it[KEY_LAST_SUMMARY] ?: "" }

  val imdbListUrl: Flow<String> = context.dataStore.data.map { it[KEY_IMDB_LIST] ?: "" }

  /**
   * A TMDB key of the user's own, which takes precedence over whatever the build shipped with.
   *
   * The built-in key is anonymous and shared by every install, so it is one rate limit for
   * everybody. Anyone who would rather spend their own quota — or who installed a build that has
   * no key at all — can paste one here instead of rebuilding the app.
   */
  val tmdbApiKey: Flow<String> = context.dataStore.data.map { it[KEY_TMDB_KEY] ?: "" }

  suspend fun setAutoSyncEnabled(enabled: Boolean) = edit { it[KEY_AUTO_SYNC] = enabled }

  suspend fun setSyncIntervalHours(hours: Long) = edit { it[KEY_INTERVAL_HOURS] = hours }

  suspend fun setHorizonDays(days: Long) = edit { it[KEY_HORIZON_DAYS] = days }

  suspend fun setNotificationsEnabled(enabled: Boolean) = edit { it[KEY_NOTIFICATIONS] = enabled }

  suspend fun setImdbListUrl(url: String) = edit { it[KEY_IMDB_LIST] = url }

  suspend fun setTmdbApiKey(key: String) = edit { it[KEY_TMDB_KEY] = key.trim() }

  suspend fun recordSync(timestamp: Long, summary: String) = edit {
    it[KEY_LAST_SYNC] = timestamp
    it[KEY_LAST_SUMMARY] = summary
  }

  suspend fun currentIntervalHours(): Long = syncIntervalHours.first()

  suspend fun currentHorizonDays(): Long = horizonDays.first()

  suspend fun currentTmdbApiKey(): String = tmdbApiKey.first()

  private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
    context.dataStore.edit(block)
  }

  companion object {
    /** Cinema schedules change on a daily rhythm; polling harder only costs battery. */
    const val DEFAULT_INTERVAL_HOURS = 6L

    /**
     * Two months. Three weeks was the old default, from when every source only published a short
     * window — but the independents post their repertory programme months out (Bio Rio's calendar
     * runs to November), so a 21-day horizon silently dropped exactly the retrospective screenings
     * this app is most useful for. Wider means earlier warning at the cost of a few more requests.
     */
    const val DEFAULT_HORIZON_DAYS = 60L

    private val KEY_AUTO_SYNC = booleanPreferencesKey("auto_sync_enabled")
    private val KEY_INTERVAL_HOURS = longPreferencesKey("sync_interval_hours")
    private val KEY_HORIZON_DAYS = longPreferencesKey("horizon_days")
    private val KEY_NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
    private val KEY_LAST_SYNC = longPreferencesKey("last_sync_at")
    private val KEY_LAST_SUMMARY = stringPreferencesKey("last_sync_summary")
    private val KEY_IMDB_LIST = stringPreferencesKey("imdb_list_url")
    private val KEY_TMDB_KEY = stringPreferencesKey("tmdb_api_key")
  }
}
