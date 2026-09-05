package se.kinosthlm.app

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.kinosthlm.app.data.watchlist.TraktProvider
import se.kinosthlm.app.data.watchlist.TraktTokenStore

/**
 * Trakt's watchlist endpoint is documented as having "optional pagination", which reads like
 * "returns everything unless you ask for a page". It does not — it caps the response, and a
 * watchlist longer than the cap arrives quietly truncated. A 400-film list importing as exactly
 * 100 films looked, from inside the app, like a successful import.
 *
 * So these pin the paging: keep asking until a page comes back short, and stop there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraktWatchlistPagingTest {

  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun provider(): TraktProvider {
    val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    val tokens = TraktTokenStore(context)
    tokens.save(
      JSONObject()
        .put("access_token", "test-token")
        .put("refresh_token", "test-refresh")
        // Far enough out that nothing tries to refresh mid-test.
        .put("created_at", System.currentTimeMillis() / 1000)
        .put("expires_in", 90L * 24 * 60 * 60)
    )
    return TraktProvider(
      context,
      tokens,
      apiBase = server.url("/").toString().trimEnd('/'),
    )
  }

  /** One movie entry in the shape Trakt returns, keyed on a unique TMDB id. */
  private fun entry(index: Int): String =
    """{"movie":{"title":"Film $index","year":2000,""" +
      """"ids":{"trakt":$index,"imdb":"tt${1000000 + index}","tmdb":$index}}}"""

  private fun page(count: Int, startingAt: Int): MockResponse =
    MockResponse()
      .setResponseCode(200)
      .setHeader("Content-Type", "application/json")
      .setBody((startingAt until startingAt + count).joinToString(",", "[", "]") { entry(it) })

  @Test
  fun `keeps asking until a short page ends the list`() = runTest {
    // Two full pages then a partial one: 250 films, which the old single request capped at 100.
    server.enqueue(page(100, 1))
    server.enqueue(page(100, 101))
    server.enqueue(page(50, 201))

    val items = provider().sync()

    assertEquals(250, items.size)
    assertEquals(3, server.requestCount)
  }

  @Test
  fun `asks for each page explicitly rather than trusting the default`() = runTest {
    server.enqueue(page(100, 1))
    server.enqueue(page(20, 101))

    provider().sync()

    val first = server.takeRequest().path.orEmpty()
    val second = server.takeRequest().path.orEmpty()
    assertTrue("First request did not ask for page 1: $first", first.contains("page=1"))
    assertTrue("First request set no limit: $first", first.contains("limit=100"))
    assertTrue("Second request did not ask for page 2: $second", second.contains("page=2"))
  }

  @Test
  fun `a single short page needs only one request`() = runTest {
    server.enqueue(page(7, 1))

    val items = provider().sync()

    assertEquals(7, items.size)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun `an exactly full final page stops at the empty one after it`() = runTest {
    // The awkward case: 100 films is both "a full page" and "the whole list".
    server.enqueue(page(100, 1))
    server.enqueue(page(0, 101))

    val items = provider().sync()

    assertEquals(100, items.size)
    assertEquals(2, server.requestCount)
  }

  @Test
  fun `the same film on two pages is only imported once`() = runTest {
    // Trakt pages a live list; an edit between requests can shift an entry across the boundary.
    server.enqueue(page(100, 1))
    server.enqueue(page(30, 100))

    val items = provider().sync()

    assertEquals(129, items.size)
    assertEquals(items.size, items.map { it.id }.distinct().size)
  }
}
