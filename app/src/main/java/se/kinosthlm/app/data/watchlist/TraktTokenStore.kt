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

  // --- The device code being authorised right now ---

  /**
   * Remember the code we are waiting on, so a dropped connection — or leaving the app to type the
   * code in a browser — does not throw it away and hand back a different one on the next tap.
   * Trakt's codes stay valid for their full window regardless of what our process is doing.
   */
  fun savePendingCode(code: TraktProvider.DeviceCode) {
    prefs.edit()
      .putString(KEY_DEVICE_CODE, code.deviceCode)
      .putString(KEY_USER_CODE, code.userCode)
      .putString(KEY_VERIFY_URL, code.verificationUrl)
      .putInt(KEY_CODE_INTERVAL, code.intervalSeconds)
      .putLong(KEY_CODE_EXPIRES_AT, System.currentTimeMillis() + code.expiresInSeconds * 1000L)
      .apply()
  }

  /** The pending code, or null once it has expired or been used. */
  fun pendingCode(): TraktProvider.DeviceCode? {
    val expiresAt = prefs.getLong(KEY_CODE_EXPIRES_AT, 0L)
    val remainingMillis = expiresAt - System.currentTimeMillis()
    if (remainingMillis <= 0) return null

    val deviceCode = prefs.getString(KEY_DEVICE_CODE, null) ?: return null
    val userCode = prefs.getString(KEY_USER_CODE, null) ?: return null
    return TraktProvider.DeviceCode(
      deviceCode = deviceCode,
      userCode = userCode,
      verificationUrl = prefs.getString(KEY_VERIFY_URL, null) ?: "https://trakt.tv/activate",
      intervalSeconds = prefs.getInt(KEY_CODE_INTERVAL, 5),
      expiresInSeconds = (remainingMillis / 1000).toInt(),
    )
  }

  fun clearPendingCode() {
    prefs.edit()
      .remove(KEY_DEVICE_CODE)
      .remove(KEY_USER_CODE)
      .remove(KEY_VERIFY_URL)
      .remove(KEY_CODE_INTERVAL)
      .remove(KEY_CODE_EXPIRES_AT)
      .apply()
  }

  private companion object {
    const val KEY_ACCESS = "access_token"
    const val KEY_REFRESH = "refresh_token"
    const val KEY_EXPIRES_AT = "expires_at"
    const val KEY_DEVICE_CODE = "device_code"
    const val KEY_USER_CODE = "user_code"
    const val KEY_VERIFY_URL = "verification_url"
    const val KEY_CODE_INTERVAL = "code_interval"
    const val KEY_CODE_EXPIRES_AT = "code_expires_at"
    const val ONE_DAY_MILLIS = 24 * 60 * 60 * 1000L
  }
}
