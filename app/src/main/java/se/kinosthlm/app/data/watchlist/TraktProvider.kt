package se.kinosthlm.app.data.watchlist

import android.content.Context
import android.util.Log
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
  /** Overridden only by tests, which point it at a local server. */
  private val apiBase: String = API,
) : WatchlistProvider {

  override val id = WatchlistItem.SOURCE_TRAKT
  override val label = "Trakt"
  override val supportsBackgroundSync = true

  val isConfigured: Boolean get() = CLIENT_ID.isNotBlank()

  override suspend fun isConnected(): Boolean = isConfigured && tokens.accessToken != null

  // --- Device flow ---

  /**
   * Step 1: ask Trakt for a code. Show [DeviceCode.userCode] and [DeviceCode.verificationUrl].
   *
   * A code already issued and still inside its window is reused rather than replaced. Otherwise
   * every hiccup — or simply reopening Settings — would put a different code on screen than the
   * one the user is halfway through typing at trakt.tv/activate.
   */
  suspend fun requestDeviceCode(): DeviceCode = withContext(Dispatchers.IO) {
    require(isConfigured) { "No Trakt client id configured" }
    val existing = tokens.pendingCode()
    if (existing != null) return@withContext existing

    val response = post("$apiBase/oauth/device/code", JSONObject().put("client_id", CLIENT_ID))
    DeviceCode(
      deviceCode = response.getString("device_code"),
      userCode = response.getString("user_code"),
      verificationUrl = response.optString("verification_url", "https://trakt.tv/activate"),
      intervalSeconds = response.optInt("interval", 5),
      expiresInSeconds = response.optInt("expires_in", 600),
    )
      .also { tokens.savePendingCode(it) }
  }

  /**
   * Step 2: poll until the user finishes authorising, then store the tokens.
   *
   * Returns false if the code expired or the user denied it. Trakt requires that we respect the
   * interval it hands back; polling faster earns a 429 and a longer wait.
   *
   * Network failures during this window are *expected*, not fatal. The whole point of the device
   * flow is that the user leaves to authorise somewhere else, and a backgrounded app on Android
   * routinely loses DNS for a moment — Doze, Data Saver, a Wi-Fi handover. Treating one
   * `UnknownHostException` as a failed connection is what threw the code away mid-authorisation
   * and made the next attempt hand back a different one. So a failed poll is just another
   * "not yet": back off a little and keep asking until the code genuinely expires.
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
        .url("$apiBase/oauth/device/token")
        .header("User-Agent", Http.USER_AGENT)
        .post(body.toString().toRequestBody(JSON))
        .build()

      val outcome =
        runCatching {
          Http.client.newCall(request).execute().use { response ->
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
        }
          .getOrElse { error ->
            Log.d(TAG, "Trakt poll failed, will retry: ${error.message}")
            Outcome.OFFLINE
          }

      when (outcome) {
        Outcome.AUTHORIZED -> {
          tokens.clearPendingCode()
          return@withContext true
        }
        Outcome.FAILED -> {
          tokens.clearPendingCode()
          return@withContext false
        }
        Outcome.SLOW_DOWN -> intervalMillis += 1000L
        // Ease off while there is nothing to talk to, but never past a ten-second cadence: the
        // user may well be authorising right now and expects the screen to notice promptly.
        Outcome.OFFLINE -> intervalMillis = (intervalMillis + 2000L).coerceAtMost(10_000L)
        Outcome.PENDING -> Unit
      }
    }
    // Out of time rather than refused; the code is no longer usable either way.
    tokens.clearPendingCode()
    false
  }

  /** Forget a half-finished authorisation, so the next Connect starts cleanly. */
  fun cancelPendingAuthorization() = tokens.clearPendingCode()

  fun disconnect() = tokens.clear()

  // --- Sync ---

  /**
   * Fetch the whole movie watchlist, a page at a time.
   *
   * The endpoint is documented as "optional pagination", which is easy to read as "returns
   * everything if you do not ask" — it does not. Left unpaged it caps the response, and a
   * watchlist longer than that silently arrives truncated: the app looked like it had imported
   * fine and simply never mentioned the rest. So ask explicitly and keep asking until a page
   * comes back short, which is the only reliable end-of-list signal across Trakt's variants.
   */
  override suspend fun sync(): List<WatchlistItem> = withContext(Dispatchers.IO) {
    val token = validAccessToken() ?: error("Trakt is not connected")

    val items = mutableListOf<WatchlistItem>()
    var page = 1
    while (page <= MAX_PAGES) {
      val entries = fetchWatchlistPage(token, page)
      items += entries
      if (entries.size < PAGE_SIZE) break
      page++
    }
    items.distinctBy { it.id }
  }

  private fun fetchWatchlistPage(token: String, page: Int): List<WatchlistItem> {
    val request = Request.Builder()
      .url("$apiBase/users/me/watchlist/movies?page=$page&limit=$PAGE_SIZE")
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
    return (0 until entries.length()).mapNotNull { index ->
      val movie = entries.optJSONObject(index)?.optJSONObject("movie") ?: return@mapNotNull null
      val title = movie.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
      val year = movie.optInt("year").takeIf { it > 0 }
      val ids = movie.optJSONObject("ids")
      val imdbId = ids?.optString("imdb")?.takeIf { it.startsWith("tt") }
      val tmdbId = ids?.optInt("tmdb")?.takeIf { it > 0 }

      WatchlistItem(
        // Trakt hands back the TMDB id directly, so entries arrive already on the standardized
        // key — no separate resolution pass needed, unlike CSV imports.
        id = WatchlistItem.idFor(tmdbId, imdbId, title, year),
        title = title,
        year = year,
        imdbId = imdbId,
        tmdbId = tmdbId,
        traktId = ids?.optInt("trakt")?.takeIf { it > 0 },
      )
    }
  }

  /** Refresh the access token before it expires, so a background sync never fails on staleness. */
  private fun validAccessToken(): String? {
    val current = tokens.accessToken ?: return null
    if (!tokens.isExpiringSoon) return current
    val refresh = tokens.refreshToken ?: return current
    return runCatching {
      val response = post(
        "$apiBase/oauth/token",
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

  private enum class Outcome { AUTHORIZED, PENDING, SLOW_DOWN, FAILED, OFFLINE }

  data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
  )

  companion object {
    private const val TAG = "TraktProvider"
    private const val API = "https://api.trakt.tv"
    private val JSON = "application/json".toMediaType()

    /** Trakt's documented maximum page size; anything larger is clamped to this anyway. */
    private const val PAGE_SIZE = 100

    /** 10,000 films is well past any real watchlist, and stops a paging bug looping forever. */
    private const val MAX_PAGES = 100

    val CLIENT_ID: String = BuildConfig.TRAKT_CLIENT_ID
    val CLIENT_SECRET: String = BuildConfig.TRAKT_CLIENT_SECRET
  }
}
