package se.kinosthlm.app

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.watchlist.TitleLookup
import se.kinosthlm.app.data.watchlist.TitleResolver

/**
 * The Google TV export gives bare titles, so these decide whether an entry becomes a film, a
 * hidden TV series, or a question for the user. Getting it wrong either buries films the user
 * wants or silently watches for the wrong one.
 */
class TitleResolverTest {

  /** Shape of a TMDB /search/multi response, trimmed to the fields we read. */
  private fun search(vararg entries: Triple<Int, String, Int?>, type: String = "movie") =
    buildString {
      append("""{"page":1,"results":[""")
      append(
        entries.joinToString(",") { (id, title, year) ->
          // TMDB sends an empty string, not a missing field, for anything unreleased.
          val date = if (year != null) "\"$year-01-01\"" else "\"\""
          val nameField = if (type == "movie") "title" else "name"
          val dateField = if (type == "movie") "release_date" else "first_air_date"
          """{"id":$id,"media_type":"$type","$nameField":"$title","$dateField":$date}"""
        }
      )
      append("""]}""")
    }

  /** Response for /movie/{id}/external_ids, which is how a film gains its IMDb id. */
  private fun externalIds(imdbId: String) = """{"imdb_id":"$imdbId"}"""

  private fun lookupAgainst(server: MockWebServer) =
    TitleLookup(apiKey = "test-key", baseUrl = server.url("/3").toString().trimEnd('/'))

  private fun serverReturning(body: (String) -> String): MockWebServer =
    MockWebServer().apply {
      dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            // Identification makes a second call for the IMDb id; answer it generically.
            if (path.contains("external_ids")) {
              return MockResponse().setBody(externalIds("tt0000001"))
            }
            return MockResponse().setBody(body(path))
          }
        }
      start()
    }

  private fun item(title: String, year: Int? = null) =
    WatchlistItem(id = "test:$title", title = title, year = year)

  @Test
  fun `identifies a single film and attaches its imdb id`() = runTest {
    val server = serverReturning { search(Triple(653, "Nosferatu", 1922)) }
    try {
      val outcome =
        TitleResolver(lookupAgainst(server))
          .resolve(listOf(item("Nosferatu")))

      assertEquals(1, outcome.identified)
      val resolved = outcome.resolutions.single().item
      assertEquals("tt0000001", resolved.imdbId)
      assertEquals(1922, resolved.year)
      assertEquals(WatchlistItem.TYPE_MOVIE, resolved.titleType)
      assertFalse(resolved.needsReview)
      assertTrue(resolved.isMatchable)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `marks a tv series so it stops cluttering the watchlist`() = runTest {
    val server = serverReturning {
      search(Triple(1398, "The Sopranos", 1999), type = "tv")
    }
    try {
      val outcome =
        TitleResolver(lookupAgainst(server))
          .resolve(listOf(item("The Sopranos")))

      assertEquals(1, outcome.series)
      val resolved = outcome.resolutions.single().item
      assertEquals(WatchlistItem.TYPE_SERIES, resolved.titleType)
      assertFalse(resolved.isFilm)
      assertFalse(resolved.isMatchable)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `asks the user when several films share the title`() = runTest {
    val server = serverReturning {
      search(
        Triple(653, "Nosferatu", 1922),
        Triple(426063, "Nosferatu", 2024),
      )
    }
    try {
      val outcome =
        TitleResolver(lookupAgainst(server))
          .resolve(listOf(item("Nosferatu")))

      assertEquals(1, outcome.ambiguous)
      val resolution = outcome.resolutions.single()
      assertTrue(resolution.item.needsReview)
      // Ambiguous entries must not be matched against cinema listings.
      assertFalse(resolution.item.isMatchable)
      assertEquals(2, resolution.candidates.size)
      assertEquals(setOf(1922, 2024), resolution.candidates.mapNotNull { it.year }.toSet())
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `offers one option per year and never a yearless near-duplicate`() = runTest {
    // What TMDB really returns for a title like "Past Lives": the film, plus documentaries,
    // shorts and unreleased stubs sharing the name. In a picker showing only a title and a year
    // several of those are literally indistinguishable, so the choice has to be narrowed first.
    val server = serverReturning {
      search(
        Triple(1, "Past Lives", 2023),
        Triple(2, "Past Lives", 2023),
        Triple(3, "Past Lives", 2022),
        Triple(4, "Past Lives", null),
        Triple(5, "Past Lives", null),
        Triple(6, "Past Lives", 2019),
        Triple(7, "Past Lives", 2015),
        Triple(8, "Past Lives", 2011),
      )
    }
    try {
      val outcome = TitleResolver(lookupAgainst(server)).resolve(listOf(item("Past Lives")))

      assertEquals(1, outcome.ambiguous)
      val candidates = outcome.resolutions.single().candidates
      assertEquals(4, candidates.size)
      assertTrue("a yearless stub is never the film someone listed", candidates.all { it.year != null })
      assertEquals(candidates.size, candidates.map { it.year }.distinct().size)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `a year in the export settles an otherwise ambiguous title`() = runTest {
    val server = serverReturning {
      search(
        Triple(653, "Nosferatu", 1922),
        Triple(426063, "Nosferatu", 2024),
      )
    }
    try {
      val outcome =
        TitleResolver(lookupAgainst(server))
          .resolve(listOf(item("Nosferatu", year = 1922)))

      assertEquals(1, outcome.identified)
      assertEquals(0, outcome.ambiguous)
      assertEquals("tt0000001", outcome.resolutions.single().item.imdbId)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `ignores results that are not the same title`() = runTest {
    // TMDB ranks partial matches too: without this filter "Nosferatu" would be offered
    // "Nosferatu in Venice" as an equally likely answer and become ambiguous.
    val server = serverReturning {
      search(
        Triple(653, "Nosferatu", 1922),
        Triple(2998, "Nosferatu in Venice", 1988),
      )
    }
    try {
      val outcome =
        TitleResolver(lookupAgainst(server))
          .resolve(listOf(item("Nosferatu")))

      assertEquals(1, outcome.identified)
      assertEquals("tt0000001", outcome.resolutions.single().item.imdbId)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `leaves a title alone when the lookup finds nothing`() = runTest {
    val server = serverReturning { """{"page":1,"results":[]}""" }
    try {
      val outcome =
        TitleResolver(lookupAgainst(server))
          .resolve(listOf(item("Ett verk som inte finns")))

      assertEquals(1, outcome.failed)
      // Nothing invented, nothing deleted: it stays unresolved and is retried later.
      assertTrue(outcome.resolutions.isEmpty())
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `survives a lookup failure without losing the rest`() = runTest {
    val server =
      MockWebServer().apply {
        dispatcher =
          object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
              if (request.path.orEmpty().contains("broken")) {
                MockResponse().setResponseCode(503)
              } else if (request.path.orEmpty().contains("external_ids")) {
                MockResponse().setBody("""{"imdb_id":"tt0017136"}""")
              } else {
                MockResponse()
                  .setBody(
                    """{"page":1,"results":[{"id":19,"media_type":"movie","title":"Metropolis","release_date":"1927-01-10"}]}"""
                  )
              }
          }
        start()
      }
    try {
      val outcome =
        TitleResolver(lookupAgainst(server))
          .resolve(listOf(item("broken"), item("Metropolis")))

      assertEquals(1, outcome.failed)
      assertEquals(1, outcome.identified)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `skips entries that already carry an imdb id`() = runTest {
    val server = serverReturning { search(Triple(1, "Whatever", 1900)) }
    try {
      val outcome =
        TitleResolver(lookupAgainst(server))
          .resolve(
            listOf(
              WatchlistItem(
                id = "imdb:tt0017136",
                title = "Metropolis",
                imdbId = "tt0017136",
                titleType = WatchlistItem.TYPE_MOVIE,
              )
            )
          )

      // Trakt and IMDb imports arrive identified; they must cost no requests at all.
      assertTrue(outcome.resolutions.isEmpty())
      assertEquals(0, server.requestCount)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `respects the per-run limit`() = runTest {
    val server = serverReturning { search(Triple(19, "Metropolis", 1927)) }
    try {
      val outcome =
        TitleResolver(lookupAgainst(server))
          .resolve(List(5) { item("Metropolis") }, limit = 2)

      // Two searches plus their external_ids follow-ups.
      assertEquals(4, server.requestCount)
      assertEquals(2, outcome.resolutions.size)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `reports progress across the run`() = runTest {
    val server = serverReturning { search(Triple(19, "Metropolis", 1927)) }
    try {
      val seen = mutableListOf<Pair<Int, Int>>()
      TitleResolver(lookupAgainst(server))
        .resolve(List(3) { item("Metropolis") }) { done, total -> seen += done to total }

      assertEquals(0 to 3, seen.first())
      assertEquals(3 to 3, seen.last())
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `fetches candidates for rows an import already flagged`() = runTest {
    // A Google TV file listing "Nosferatu" twice flags both before anything is looked up. If
    // that flag blocked resolution, the review sheet would offer nothing to choose from.
    val server = serverReturning {
      search(Triple(653, "Nosferatu", 1922), Triple(426063, "Nosferatu", 2024))
    }
    try {
      val flagged = item("Nosferatu").copy(needsReview = true)
      val outcome = TitleResolver(lookupAgainst(server)).resolve(listOf(flagged))

      assertEquals(1, outcome.ambiguous)
      assertEquals(2, outcome.resolutions.single().candidates.size)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `does nothing at all without a tmdb key`() = runTest {
    // No key means no guessing: titles stay unidentified and the UI says why.
    val outcome =
      TitleResolver(TitleLookup(apiKey = "", baseUrl = "https://unused.test"))
        .resolve(listOf(item("Nosferatu")))

    assertTrue(outcome.unavailable)
    assertTrue(outcome.resolutions.isEmpty())
  }

  // --- backfillTmdbIds: giving a TMDB id to IMDb CSV / public-list imports ---

  /** Shape of a TMDB /find/{imdb_id} response, trimmed to the fields we read. */
  private fun findByImdbId(tmdbId: Int, title: String, year: Int?) =
    """{"movie_results":[{"id":$tmdbId,"media_type":"movie","title":"$title","release_date":"${year ?: 1900}-01-01"}],"tv_results":[]}"""

  private fun serverFinding(body: () -> String): MockWebServer =
    MockWebServer().apply {
      dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest) = MockResponse().setBody(body())
        }
      start()
    }

  @Test
  fun `gives a tmdb id to an entry that only has an imdb id`() = runTest {
    val server = serverFinding { findByImdbId(19, "Metropolis", 1927) }
    try {
      val imdbOnly =
        WatchlistItem(id = "imdb:tt0017136", title = "Metropolis", imdbId = "tt0017136", year = 1927)

      val resolutions = TitleResolver(lookupAgainst(server)).backfillTmdbIds(listOf(imdbOnly))

      assertEquals(1, resolutions.size)
      val resolved = resolutions.single()
      assertEquals("imdb:tt0017136", resolved.oldId)
      assertEquals(19, resolved.item.tmdbId)
      // Standardizing the key means adopting the TMDB-based id, not just filling the field.
      assertEquals("tmdb:19", resolved.item.id)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `does not touch entries that already have a tmdb id`() = runTest {
    val server = serverFinding { findByImdbId(19, "Metropolis", 1927) }
    try {
      val alreadyIdentified =
        WatchlistItem(id = "tmdb:19", title = "Metropolis", imdbId = "tt0017136", tmdbId = 19)

      val resolutions = TitleResolver(lookupAgainst(server)).backfillTmdbIds(listOf(alreadyIdentified))

      assertTrue(resolutions.isEmpty())
      assertEquals(0, server.requestCount)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `does not touch entries with no imdb id to look up from`() = runTest {
    val server = serverFinding { findByImdbId(19, "Metropolis", 1927) }
    try {
      val bareTitle = WatchlistItem(id = "title:metropolis", title = "Metropolis")

      val resolutions = TitleResolver(lookupAgainst(server)).backfillTmdbIds(listOf(bareTitle))

      assertTrue(resolutions.isEmpty())
      assertEquals(0, server.requestCount)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `does nothing for backfill without a tmdb key`() = runTest {
    val imdbOnly = WatchlistItem(id = "imdb:tt0017136", title = "Metropolis", imdbId = "tt0017136")

    val resolutions =
      TitleResolver(TitleLookup(apiKey = "", baseUrl = "https://unused.test"))
        .backfillTmdbIds(listOf(imdbOnly))

    assertTrue(resolutions.isEmpty())
  }

  @Test
  fun `leaves an entry alone when the imdb id does not resolve`() = runTest {
    val server = serverFinding { """{"movie_results":[],"tv_results":[]}""" }
    try {
      val imdbOnly = WatchlistItem(id = "imdb:tt9999999", title = "Nothing Here", imdbId = "tt9999999")

      val resolutions = TitleResolver(lookupAgainst(server)).backfillTmdbIds(listOf(imdbOnly))

      assertTrue(resolutions.isEmpty())
    } finally {
      server.shutdown()
    }
  }

  // --- Poster/overview details, fetched per film once its card is on screen ---

  /** Shape of a TMDB /movie/{id} response, trimmed to the fields we read. */
  private fun movieDetails(title: String, year: Int?, posterPath: String?, overview: String?) =
    """{"id":19,"title":"$title","release_date":"${year ?: 1900}-01-01",""" +
      """"poster_path":${posterPath?.let { "\"$it\"" } ?: "null"},"overview":"${overview.orEmpty()}"}"""

  @Test
  fun `fetches a poster and overview by tmdb id`() = runTest {
    val server = serverFinding { movieDetails("Metropolis", 1927, "/metropolis.jpg", "A city of the future.") }
    try {
      // What a Trakt import looks like: a TMDB id from the moment it lands, but no poster or
      // overview, since Trakt's watchlist endpoint never returns either.
      val details = lookupAgainst(server).fetchMovieDetails(19)

      assertNotNull(details)
      assertTrue(details!!.posterUrl?.contains("metropolis.jpg") == true)
      assertEquals("A city of the future.", details.overview)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `fetching details without a tmdb key asks nothing and returns nothing`() = runTest {
    val details =
      TitleLookup(apiKey = "", baseUrl = "https://unused.test").fetchMovieDetails(19)

    assertNull(details)
  }

  @Test
  fun `an unresolved entry is never matched against cinema listings`() {
    val pending = item("Nosferatu")
    assertEquals(WatchlistItem.TYPE_UNKNOWN, pending.titleType)
    // Unknown is not a series, so it stays visible and matchable until proven otherwise.
    assertTrue(pending.isMatchable)
    assertNull(pending.imdbId)
  }
}
