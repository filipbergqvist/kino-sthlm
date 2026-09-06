package se.kinosthlm.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

  /**
   * What time of day the scheduled sync should aim for, in local hours.
   *
   * Cinema programmes move once a day at most, so what matters is having looked *today*, not
   * having looked recently. A fixed hour puts the work overnight, out of the way.
   */
  val syncHourOfDay: Flow<Int> =
    context.dataStore.data.map { it[KEY_SYNC_HOUR] ?: DEFAULT_SYNC_HOUR }

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

  suspend fun setSyncHourOfDay(hour: Int) = edit { it[KEY_SYNC_HOUR] = hour.coerceIn(0, 23) }

  /**
   * Whether we have already put the notification permission dialog in front of the user.
   *
   * Android stops showing that dialog after two refusals, so an app that asks on every launch is
   * showing nothing at all. Ask once unprompted; after that only when the user turns Alerts on.
   */
  suspend fun hasAskedForNotificationPermission(): Boolean =
    context.dataStore.data.map { it[KEY_ASKED_NOTIFICATIONS] ?: false }.first()

  suspend fun markNotificationPermissionAsked() = edit { it[KEY_ASKED_NOTIFICATIONS] = true }

  suspend fun setHorizonDays(days: Long) = edit { it[KEY_HORIZON_DAYS] = days }

  suspend fun setNotificationsEnabled(enabled: Boolean) = edit { it[KEY_NOTIFICATIONS] = enabled }

  suspend fun setImdbListUrl(url: String) = edit { it[KEY_IMDB_LIST] = url }

  suspend fun setTmdbApiKey(key: String) = edit { it[KEY_TMDB_KEY] = key.trim() }

  suspend fun recordSync(timestamp: Long, summary: String) = edit {
    it[KEY_LAST_SYNC] = timestamp
    it[KEY_LAST_SUMMARY] = summary
  }

  suspend fun currentIntervalHours(): Long = syncIntervalHours.first()

  suspend fun currentSyncHour(): Int = syncHourOfDay.first()

  suspend fun currentHorizonDays(): Long = horizonDays.first()

  suspend fun currentTmdbApiKey(): String = tmdbApiKey.first()

  private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
    context.dataStore.edit(block)
  }

  companion object {
    /**
     * Once a day.
     *
     * Cinema programmes are updated daily at most, so anything more often spends battery and
     * other people's bandwidth re-reading the same pages. The old six-hourly default came with a
     * note asking users not to sync too often for the cinemas' sake; making the default kind is
     * a better answer than asking.
     */
    const val DEFAULT_INTERVAL_HOURS = 24L

    /** 20:00 — evening, when a phone is usually on wi-fi and awake enough for WorkManager. */
    const val DEFAULT_SYNC_HOUR = 20

    /**
     * Two months. Three weeks was the old default, from when every source only published a short
     * window — but the independents post their repertory programme months out (Bio Rio's calendar
     * runs to November), so a 21-day horizon silently dropped exactly the retrospective screenings
     * this app is most useful for. Wider means earlier warning at the cost of a few more requests.
     */
    const val DEFAULT_HORIZON_DAYS = 60L

    private val KEY_AUTO_SYNC = booleanPreferencesKey("auto_sync_enabled")
    private val KEY_INTERVAL_HOURS = longPreferencesKey("sync_interval_hours")
    private val KEY_SYNC_HOUR = intPreferencesKey("sync_hour_of_day")
    private val KEY_ASKED_NOTIFICATIONS = booleanPreferencesKey("asked_notification_permission")
    private val KEY_HORIZON_DAYS = longPreferencesKey("horizon_days")
    private val KEY_NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
    private val KEY_LAST_SYNC = longPreferencesKey("last_sync_at")
    private val KEY_LAST_SUMMARY = stringPreferencesKey("last_sync_summary")
    private val KEY_IMDB_LIST = stringPreferencesKey("imdb_list_url")
    private val KEY_TMDB_KEY = stringPreferencesKey("tmdb_api_key")
  }
}
