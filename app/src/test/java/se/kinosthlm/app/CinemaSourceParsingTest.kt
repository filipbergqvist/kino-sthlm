package se.kinosthlm.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.source.BioRioSource
import se.kinosthlm.app.data.source.CapitolSource
import se.kinosthlm.app.data.source.FilmstadenSource
import se.kinosthlm.app.data.source.SkandiaSource
import se.kinosthlm.app.data.source.SwedishDates
import se.kinosthlm.app.data.source.TellusSource

/**
 * Parses the fixtures in `src/test/resources/fixtures`, each captured verbatim from the real
 * site or API.
 *
 * These are the canary for a site redesign: when a cinema changes its markup, the corresponding
 * test here goes red rather than the app silently reporting "no screenings".
 */
class CinemaSourceParsingTest {

  private fun fixture(name: String): String =
    checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "Missing fixture $name" }
      .bufferedReader()
      .readText()

  private fun cinema(id: String, name: String, remoteId: String? = null) =
    Cinema(
      id = id,
      name = name,
      district = "Stockholm",
      address = "",
      websiteUrl = "https://example.test",
      sourceId = id,
      remoteId = remoteId,
    )

  // --- Filmstaden: JSON API, served through MockWebServer ---

  @Test
  fun `filmstaden maps movies and shows onto followed venues`() = runTest {
    val server = MockWebServer()
    server.enqueue(MockResponse().setBody(fixture("filmstaden_movies.json")))
    server.enqueue(MockResponse().setBody(fixture("filmstaden_shows.json")))
    server.start()

    try {
      val movies = org.json.JSONObject(fixture("filmstaden_movies.json")).getJSONArray("items")
      val title = movies.getJSONObject(0).getString("title")
      val year = movies.getJSONObject(0).optInt("productionYear").takeIf { it > 0 }

      val watchlist =
        listOf(
          WatchlistItem(
            id = WatchlistItem.idFor(null, null, title, year),
            title = title,
            year = year,
          )
        )
      val venues =
        listOf(
          cinema("filmstaden_rigoletto", "Filmstaden Rigoletto", "NCG76480"),
          cinema("filmstaden_sergel", "Filmstaden Sergel", "NCG27927"),
        )

      val screenings =
        FilmstadenSource(server.url("/").toString().trimEnd('/'))
          .fetchScreenings(
            cinemas = venues,
            watchlist = watchlist,
            from = Instant.parse("2020-01-01T00:00:00Z"),
            to = Instant.parse("2099-01-01T00:00:00Z"),
          )

      assertTrue("expected screenings for $title", screenings.isNotEmpty())
      // Only venues we follow may appear; the feed is nationwide.
      assertTrue(screenings.all { it.cinemaId in venues.map(Cinema::id) })
      assertTrue(screenings.all { it.title == title })
      assertTrue(screenings.all { it.bookingUrl.startsWith("https://www.filmstaden.se/film/") })
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun `filmstaden asks for no shows when nothing on the watchlist is playing`() = runTest {
    val server = MockWebServer()
    server.enqueue(MockResponse().setBody(fixture("filmstaden_movies.json")))
    server.start()

    try {
      val screenings =
        FilmstadenSource(server.url("/").toString().trimEnd('/'))
          .fetchScreenings(
            cinemas = listOf(cinema("filmstaden_sergel", "Filmstaden Sergel", "NCG27927")),
            watchlist = listOf(WatchlistItem(id = "x", title = "A Film Nobody Is Showing", year = 1901)),
            from = Instant.parse("2020-01-01T00:00:00Z"),
            to = Instant.parse("2099-01-01T00:00:00Z"),
          )

      assertTrue(screenings.isEmpty())
      // The catalogue request only — no per-film show lookups were made.
      assertEquals(1, server.requestCount)
    } finally {
      server.shutdown()
    }
  }

  // --- Bio Rio: schema.org JSON-LD ---

  @Test
  fun `bio rio reads screening events from json-ld`() {
    val venue = cinema("bio_rio", "Bio Rio")
    val screenings = BioRioSource().parse(fixture("biorio_home.html"), venue)

    assertTrue("expected ScreeningEvents", screenings.isNotEmpty())
    assertTrue(screenings.all { it.cinemaId == "bio_rio" })
    assertTrue(screenings.all { it.title.isNotBlank() })
    assertTrue(screenings.all { it.bookingUrl.startsWith("http") })
  }

  // --- Biocafé Tellus: The Events Calendar REST API ---

  @Test
  fun `tellus maps events and decodes wordpress entities`() {
    val venue = cinema("bio_tellus", "Biocafé Tellus")
    val screenings = TellusSource().parse(fixture("tellus_events.json"), venue)

    assertTrue(screenings.isNotEmpty())
    assertTrue(screenings.all { it.title.isNotBlank() })
    // WordPress escapes ampersands; a leaked entity means the decoder regressed.
    assertTrue(screenings.none { it.title.contains("&#") })
    assertTrue(screenings.all { it.startTime.isAfter(Instant.EPOCH) })
  }

  // --- Bio Skandia: film index + per-film Tickster rows ---

  @Test
  fun `skandia reads the film index`() {
    val films = SkandiaSource().parseIndex(fixture("skandia_index.html"), "https://bioskandia.se/filmer/")

    assertTrue(films.isNotEmpty())
    assertTrue(films.all { it.title.isNotBlank() })
    assertTrue(films.all { it.url.contains("/filmer/") })
    // The index links each film once.
    assertEquals(films.size, films.map { it.url }.distinct().size)
  }

  @Test
  fun `skandia reads showtimes from a film page`() {
    val venue = cinema("bio_skandia", "Bio Skandia")
    val screenings =
      SkandiaSource()
        .parseFilmPage(
          html = fixture("skandia_film.html"),
          baseUrl = "https://bioskandia.se/filmer/nosferatu/",
          cinema = venue,
          title = "Nosferatu",
          // Pinned so the year inference is deterministic; the fixture says "12 Sep".
          today = LocalDate.of(2026, 9, 5),
        )

    assertTrue("expected showtimes", screenings.isNotEmpty())
    val first = screenings.minByOrNull { it.startTime }
    assertNotNull(first)
    val local = first!!.startTime.atZone(SwedishDates.STOCKHOLM)
    assertEquals(2026, local.year)
    assertEquals(9, local.monthValue)
    assertTrue(screenings.all { it.bookingUrl.contains("tickster.com") })
  }

  @Test
  fun `skandia resolves a december listing seen in january to the coming year`() {
    // Guards the year inference: "5 jan" seen on 28 December is next year, not eleven months ago.
    val resolved = SwedishDates.resolveYear(5, 1, LocalDate.of(2026, 12, 28))
    assertEquals(LocalDate.of(2027, 1, 5), resolved)
  }

  // --- Bio Capitol: server-rendered programme ---

  @Test
  fun `capitol reads title time and auditorium from the programme`() {
    val venue = cinema("bio_capitol", "Bio Capitol")
    val day = LocalDate.of(2026, 9, 5)
    val screenings =
      CapitolSource()
        .parseDay(
          html = fixture("capitol_day.html"),
          baseUrl = "https://www.capitolbio.se/filmer",
          cinema = venue,
          day = day,
        )

    assertTrue("expected showings", screenings.isNotEmpty())
    assertTrue(screenings.all { it.title.isNotBlank() })
    assertTrue(screenings.all { it.bookingUrl.contains("/boka/") })
    // Every showing lands on the requested day, in Stockholm time.
    assertTrue(screenings.all { it.startTime.atZone(SwedishDates.STOCKHOLM).toLocalDate() == day })
    // Most rows name a screen; if none do, the auditorium selector has regressed.
    assertTrue(screenings.any { it.auditorium?.startsWith("Salong") == true })
  }

  @Test
  fun `capitol drops showings outside the requested window`() {
    val venue = cinema("bio_capitol", "Bio Capitol")
    val all =
      CapitolSource()
        .parseDay(
          fixture("capitol_day.html"),
          "https://www.capitolbio.se/filmer",
          venue,
          LocalDate.of(2026, 9, 5),
        )
    val afterNoon =
      all.filter { it.startTime.isAfter(LocalDate.of(2026, 9, 5).atTime(12, 0).toInstant(ZoneOffset.UTC)) }
    assertTrue(afterNoon.size < all.size)
  }
}
