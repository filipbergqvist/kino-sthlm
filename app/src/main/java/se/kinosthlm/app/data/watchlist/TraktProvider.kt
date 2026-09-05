package se.kinosthlm.app.data.watchlist

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import se.kinosthlm.app.BuildConfig
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http

/**
 * Trakt.tv — the only provider that can keep itself up to date unattended.
 *
 * Uses the OAuth **device** flow: the app shows an eight-character code, the user types it once
 * at trakt.tv/activate, and from then on the tokens refresh themselves. There is no redirect URI
 * to register and no browser round-trip, which suits a phone app with no server.
 *
 * The client id is a public identifier, not a secret — Trakt clients ship it in the binary.
 * Register your own at https://trakt.tv/oauth/applications and put the id and secret in
 * `local.properties`; see the README.
 */
class TraktProvider(
  context: Context,
  private val tokens: TraktTokenStore = TraktTokenStore(context),
) : WatchlistProvider {

  override val id = WatchlistItem.SOURCE_TRAKT
  override val label = "Trakt"
  override val supportsBackgroundSync = true

  val isConfigured: Boolean get() = CLIENT_ID.isNotBlank()

  override suspend fun isConnected(): Boolean = isConfigured && tokens.accessToken != null

  // --- Device flow ---

  /** Step 1: ask Trakt for a code. Show [DeviceCode.userCode] and [DeviceCode.verificationUrl]. */
  suspend fun requestDeviceCode(): DeviceCode = withContext(Dispatchers.IO) {
    require(isConfigured) { "No Trakt client id configured" }
    val response = post("$API/oauth/device/code", JSONObject().put("client_id", CLIENT_ID))
    DeviceCode(
      deviceCode = response.getString("device_code"),
      userCode = response.getString("user_code"),
      verificationUrl = response.optString("verification_url", "https://trakt.tv/activate"),
      intervalSeconds = response.optInt("interval", 5),
      expiresInSeconds = response.optInt("expires_in", 600),
    )
  }

  /**
   * Step 2: poll until the user finishes authorising, then store the tokens.
   *
   * Returns false if the code expired or the user denied it. Trakt requires that we respect the
   * interval it hands back; polling faster earns a 429 and a longer wait.
   */
  suspend fun awaitAuthorization(code: DeviceCode): Boolean = withContext(Dispatchers.IO) {
    val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
    var intervalMillis = code.intervalSeconds * 1000L

    while (System.currentTimeMillis() < deadline) {
      delay(intervalMillis)

      val body = JSONObject()
        .put("code", code.deviceCode)
        .put("client_id", CLIENT_ID)
        .put("client_secret", CLIENT_SECRET)

      val request = Request.Builder()
        .url("$API/oauth/device/token")
        .header("User-Agent", Http.USER_AGENT)
        .post(body.toString().toRequestBody(JSON))
        .build()

      val outcome = Http.client.newCall(request).execute().use { response ->
        when (response.code) {
          200 -> {
            tokens.save(JSONObject(response.body?.string().orEmpty()))
            Outcome.AUTHORIZED
          }
          // 400 means the user has not finished yet; keep waiting.
          400 -> Outcome.PENDING
          // Backing off is the documented remedy for polling too fast.
          429 -> Outcome.SLOW_DOWN
          // 404/410 expired, 409 already used, 418 denied.
          else -> Outcome.FAILED
        }
      }

      when (outcome) {
        Outcome.AUTHORIZED -> return@withContext true
        Outcome.FAILED -> return@withContext false
        Outcome.SLOW_DOWN -> intervalMillis += 1000L
        Outcome.PENDING -> Unit
      }
    }
    false
  }

  fun disconnect() = tokens.clear()

  // --- Sync ---

  override suspend fun sync(): List<WatchlistItem> = withContext(Dispatchers.IO) {
    val token = validAccessToken() ?: error("Trakt is not connected")

    val request = Request.Builder()
      .url("$API/users/me/watchlist/movies")
      .header("User-Agent", Http.USER_AGENT)
      .header("Content-Type", "application/json")
      .header("trakt-api-version", "2")
      .header("trakt-api-key", CLIENT_ID)
      .header("Authorization", "Bearer $token")
      .build()

    val json = Http.client.newCall(request).execute().use { response ->
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) error("Trakt returned HTTP ${response.code}")
      body
    }

    val entries = JSONArray(json)
    (0 until entries.length()).mapNotNull { index ->
      val movie = entries.optJSONObject(index)?.optJSONObject("movie") ?: return@mapNotNull null
      val title = movie.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val year = movie.optInt("year").takeIf { it > 0 }
      val ids = movie.optJSONObject("ids")
      val imdbId = ids?.optString("imdb")?.takeIf { it.startsWith("tt") }

      WatchlistItem(
        id = WatchlistItem.idFor(imdbId, title, year),
        title = title,
        year = year,
        imdbId = imdbId,
        tmdbId = ids?.optInt("tmdb")?.takeIf { it > 0 },
        traktId = ids?.optInt("trakt")?.takeIf { it > 0 },
      )
    }.distinctBy { it.id }
  }

  /** Refresh the access token before it expires, so a background sync never fails on staleness. */
  private fun validAccessToken(): String? {
    val current = tokens.accessToken ?: return null
    if (!tokens.isExpiringSoon) return current
    val refresh = tokens.refreshToken ?: return current
    return runCatching {
      val response = post(
        "$API/oauth/token",
        JSONObject()
          .put("refresh_token", refresh)
          .put("client_id", CLIENT_ID)
          .put("client_secret", CLIENT_SECRET)
          .put("grant_type", "refresh_token"),
      )
      tokens.save(response)
      tokens.accessToken
    }.getOrDefault(current)
  }

  private fun post(url: String, body: JSONObject): JSONObject {
    val request = Request.Builder()
      .url(url)
      .header("User-Agent", Http.USER_AGENT)
      .post(body.toString().toRequestBody(JSON))
      .build()
    Http.client.newCall(request).execute().use { response ->
      val text = response.body?.string().orEmpty()
      if (!response.isSuccessful) error("Trakt returned HTTP ${response.code}")
      return JSONObject(text)
    }
  }

  private enum class Outcome { AUTHORIZED, PENDING, SLOW_DOWN, FAILED }

  data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
  )

  companion object {
    private const val API = "https://api.trakt.tv"
    private val JSON = "application/json".toMediaType()

    val CLIENT_ID: String = BuildConfig.TRAKT_CLIENT_ID
    val CLIENT_SECRET: String = BuildConfig.TRAKT_CLIENT_SECRET
  }
}
