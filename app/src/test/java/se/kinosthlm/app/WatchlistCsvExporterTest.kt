package se.kinosthlm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.watchlist.WatchlistCsvExporter

/**
 * The export has to satisfy a parser we do not control, so the shape matters more than usual:
 * Trakt's importer wants a specific header, a prefixed id, and ISO 8601 timestamps.
 */
class WatchlistCsvExporterTest {

  private fun film(
    id: String,
    title: String,
    imdbId: String? = null,
    tmdbId: Int? = null,
    addedAt: Long = 1_700_000_000_000,
  ) =
    WatchlistItem(
      id = id,
      title = title,
      imdbId = imdbId,
      tmdbId = tmdbId,
      titleType = WatchlistItem.TYPE_MOVIE,
      addedAt = addedAt,
    )

  @Test
  fun `writes trakt's header`() {
    val csv = WatchlistCsvExporter.toCsv(emptyList())

    assertEquals("id,type,watched_at,watchlisted_at,rating,rated_at", csv.trim())
  }

  @Test
  fun `prefixes an imdb id the way trakt expects`() {
    val csv = WatchlistCsvExporter.toCsv(listOf(film("imdb:tt0017136", "Metropolis", imdbId = "tt0017136")))

    val row = csv.lines()[1]
    assertTrue(row, row.startsWith("imdb_id:tt0017136,movie,"))
  }

  @Test
  fun `falls back to the tmdb id when there is no imdb one`() {
    val csv = WatchlistCsvExporter.toCsv(listOf(film("tmdb:19", "Metropolis", tmdbId = 19)))

    assertTrue(csv.lines()[1].startsWith("tmdb_id:19,movie,"))
  }

  @Test
  fun `records when a film was added, and never claims it was watched`() {
    val csv = WatchlistCsvExporter.toCsv(listOf(film("imdb:tt1", "A Film", imdbId = "tt1")))

    // id, type, watched_at, watchlisted_at, rating, rated_at
    val fields = csv.lines()[1].split(",")
    assertEquals(6, fields.size)
    assertEquals("", fields[2])
    assertTrue("watchlisted_at should be ISO 8601: ${fields[3]}", fields[3].endsWith("Z"))
    // This app tracks intent to see a film, so a rating would be invented data.
    assertEquals("", fields[4])
    assertEquals("", fields[5])
  }

  @Test
  fun `skips films Trakt would have no way to match`() {
    val noIds = film("title:something", "Something")

    val csv = WatchlistCsvExporter.toCsv(listOf(noIds))

    assertEquals(1, csv.lines().count { it.isNotBlank() })
  }

  @Test
  fun `leaves out series and films the user removed`() {
    val items =
      listOf(
        film("imdb:tt1", "A Film", imdbId = "tt1"),
        film("imdb:tt2", "A Series", imdbId = "tt2").copy(titleType = WatchlistItem.TYPE_SERIES),
        film("imdb:tt3", "Removed", imdbId = "tt3").copy(suppressed = true),
      )

    val csv = WatchlistCsvExporter.toCsv(items)

    assertEquals(2, csv.lines().count { it.isNotBlank() })
    assertTrue(csv.contains("imdb_id:tt1"))
  }
}
