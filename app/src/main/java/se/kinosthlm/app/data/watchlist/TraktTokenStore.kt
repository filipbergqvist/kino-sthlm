package se.kinosthlm.app.data.watchlist

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Trakt tokens, kept in [EncryptedSharedPreferences] so they are not readable from a device
 * backup or an `adb pull`. Falls back to plain preferences only where the keystore is
 * unavailable, which happens on some rooted or heavily modified devices.
 */
class TraktTokenStore(context: Context) {

  private val prefs: SharedPreferences = runCatching {
    val key = MasterKey.Builder(context)
      .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
      .build()
    EncryptedSharedPreferences.create(
      context,
      "trakt_tokens",
      key,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    ) as SharedPreferences
  }.getOrElse {
    context.getSharedPreferences("trakt_tokens_plain", Context.MODE_PRIVATE)
  }

  val accessToken: String? get() = prefs.getString(KEY_ACCESS, null)

  val refreshToken: String? get() = prefs.getString(KEY_REFRESH, null)

  /** Refresh a day early rather than discovering expiry mid-sync. */
  val isExpiringSoon: Boolean
    get() {
      val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
      return expiresAt != 0L && System.currentTimeMillis() > expiresAt - ONE_DAY_MILLIS
    }

  fun save(response: JSONObject) {
    val createdAt = response.optLong("created_at", System.currentTimeMillis() / 1000)
    val expiresIn = response.optLong("expires_in", 0L)
    prefs.edit()
      .putString(KEY_ACCESS, response.optString("access_token"))
      .putString(KEY_REFRESH, response.optString("refresh_token"))
      .putLong(KEY_EXPIRES_AT, (createdAt + expiresIn) * 1000L)
      .apply()
  }

  fun clear() {
    prefs.edit().clear().apply()
  }

  private companion object {
    const val KEY_ACCESS = "access_token"
    const val KEY_REFRESH = "refresh_token"
    const val KEY_EXPIRES_AT = "expires_at"
    const val ONE_DAY_MILLIS = 24 * 60 * 60 * 1000L
  }
}
