package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.model.WatchlistItem
import com.example.data.service.StockholmCinemaPoller
import com.example.data.service.WatchlistService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Sthlm Cinema", appName)
  }

  @Test
  fun `imdb id extraction handles various url formats`() {
    val service = WatchlistService()
    assertEquals("ur12345678", service.extractImdbId("ur12345678"))
    assertEquals("ur12345678", service.extractImdbId("https://www.imdb.com/user/ur12345678/watchlist"))
    assertEquals("ls987654321", service.extractImdbId("https://www.imdb.com/list/ls987654321/"))
  }

  @Test
  fun `google tv parser parses multi-line title lists`() {
    val service = WatchlistService()
    val input = """
      The Substance (2024)
      Dune: Part Two
      • Past Lives (2023)
      - Anora
    """.trimIndent()

    val parsed = service.parseGoogleTvWatchlist(input)
    assertEquals(4, parsed.size)
    assertEquals("The Substance", parsed[0].title)
    assertEquals(2024, parsed[0].year)
    assertEquals("Dune: Part Two", parsed[1].title)
    assertEquals("Past Lives", parsed[2].title)
    assertEquals("Anora", parsed[3].title)
  }

  @Test
  fun `cinema poller matches watchlist movies to stockholm venues`() = runBlocking {
    val poller = StockholmCinemaPoller()
    val cinemas = AppDatabase.defaultStockholmCinemas
    val watchlist = listOf(
      WatchlistItem(
        id = "test_substance",
        title = "The Substance",
        year = 2024
      ),
      WatchlistItem(
        id = "test_dune",
        title = "Dune: Part Two",
        year = 2024
      )
    )

    val matches = poller.pollScreeningsForWatchlist(cinemas, watchlist)
    assertTrue("Should find matching screenings for watchlist movies", matches.isNotEmpty())
    val substanceMatch = matches.find { it.watchlistMovieId == "test_substance" }
    assertNotNull("Should find screening for The Substance", substanceMatch)
    assertTrue("Booking URL should be valid", substanceMatch?.bookingUrl?.startsWith("http") == true)
    assertTrue("Should match one of Stockholm cinemas", cinemas.any { it.id == substanceMatch?.cinemaId })
  }
}

