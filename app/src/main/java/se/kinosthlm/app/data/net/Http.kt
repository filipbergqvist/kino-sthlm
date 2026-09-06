package se.kinosthlm.app.data.net

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
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

  /**
   * The same client, but it keeps cookies for the life of the process.
   *
   * Tickster hands out a session cookie and then bounces anyone who arrives without one to a
   * "session timed out" page — which answers **200**, so a cookie-less fetch does not fail, it
   * succeeds and parses to nothing. That is the silent-empty failure this codebase exists to
   * avoid, and no amount of parsing care fixes it.
   *
   * Kept separate from [client] rather than made the default so cookies only ever go to the one
   * source that needs them. In memory only: nothing is written to disk and it is gone when the
   * app is.
   */
  val sessionClient: OkHttpClient by lazy {
    client.newBuilder().cookieJar(InMemoryCookieJar()).build()
  }

  private class InMemoryCookieJar : CookieJar {
    private val byHost = java.util.concurrent.ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
      byHost[url.host] = cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
      byHost[url.host]?.filter { it.expiresAt > System.currentTimeMillis() }.orEmpty()
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
  /** Carries the status code, so callers can tell "rate limited" from "broken" and act on it. */
  class HttpStatusException(val code: Int, url: String) :
    java.io.IOException("HTTP $code for $url")

  fun getString(
    url: String,
    accept: String = "application/json",
    /** Use [sessionClient], for a site that will not serve anyone without a session cookie. */
    withCookies: Boolean = false,
  ): String {
    val http = if (withCookies) sessionClient else client
    http.newCall(request(url, accept)).execute().use { response ->
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) {
        throw HttpStatusException(response.code, url)
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
