package se.kinosthlm.app.data.net

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Shared HTTP plumbing. Every cinema source and watchlist provider uses the same client so
 * connection pooling, timeouts and the user agent stay consistent.
 */
object Http {

  /** Honest, identifiable UA: we are a hobby client, not pretending to be a browser. */
  const val USER_AGENT = "KinoSthlm/1.0 (+https://github.com/kinosthlm/kinosthlm)"

  val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .callTimeout(45, TimeUnit.SECONDS)
      .followRedirects(true)
      .retryOnConnectionFailure(true)
      .build()
  }

  val moshi: Moshi by lazy { Moshi.Builder().add(KotlinJsonAdapterFactory()).build() }

  fun request(url: String, accept: String = "application/json"): Request =
    Request.Builder()
      .url(url)
      .header("User-Agent", USER_AGENT)
      .header("Accept", accept)
      .header("Accept-Language", "sv-SE,sv;q=0.9,en;q=0.8")
      .build()

  /**
   * GET [url] and return the body as text.
   *
   * Throws on any non-2xx response. Sources must let this propagate rather than swallowing it —
   * a failed fetch has to surface as a visible error, never as silently missing screenings.
   */
  fun getString(url: String, accept: String = "application/json"): String {
    client.newCall(request(url, accept)).execute().use { response ->
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        error("HTTP ${response.code} for $url")
      }
      return body
    }
  }

  inline fun <reified T> getJson(url: String): T {
    val json = getString(url)
    val adapter = moshi.adapter(T::class.java)
    return adapter.fromJson(json) ?: error("Empty JSON body for $url")
  }
}
