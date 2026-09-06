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
import se.kinosthlm.app.data.source.KulturhusetSource
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

  // --- Bio Rio: server-rendered calendar page ---

  @Test
  fun `bio rio reads showtimes from the calendar page`() {
    val venue = cinema("bio_rio", "Bio Rio")
    val screenings = BioRioSource().parse(fixture("biorio_home.html"), venue)

    assertTrue("expected showtimes", screenings.isNotEmpty())
    assertTrue(screenings.all { it.cinemaId == "bio_rio" })
    assertTrue(screenings.all { it.title.isNotBlank() })
    assertTrue(screenings.all { it.bookingUrl.startsWith("http") })
    // The calendar spans months, unlike a short rolling window.
    val months = screenings.map { it.startTime.atZone(SwedishDates.STOCKHOLM).monthValue }.toSet()
    assertTrue("expected screenings across multiple months", months.size > 1)

    // Rio welds the year and the projector onto the title. Both are worth knowing and neither is
    // the film's name — left in, they were what reached TMDB, which is why a film it knows
    // perfectly well came back as not found.
    val lotr = screenings.single { it.title == "Sagan om ringen" }
    assertEquals(1978, lotr.year)
    val odyssey = screenings.single { it.title == "The Odyssey" }
    assertTrue(odyssey.formatTags.any { it.equals("35mm", ignoreCase = true) })
  }

  // --- Kulturhuset Stadsteatern: three cinemas out of one calendar index ---

  @Test
  fun `kulturhuset routes showings to the right auditorium`() {
    val venues =
      listOf(
        cinema("kulturhuset_klara", "Klarabiografen", "Klarabiografen"),
        cinema("kulturhuset_skaris", "Skärisbiografen", "Skärisbiografen"),
        cinema("kulturhuset_husby", "Bio Husby", "Bio Husby"),
      )
    val page = KulturhusetSource().parse(fixture("kulturhuset_events.json"), venues)

    // Five film rows across three rooms. The sixth is a play on the main stage: a room nobody
    // follows, in an index that carries the whole house, so it must not become a screening.
    assertEquals(5, page.screenings.size)
    assertTrue(page.screenings.none { it.title == "Hamlet" })
    assertEquals(3, page.screenings.count { it.cinemaId == "kulturhuset_klara" })
    assertEquals(1, page.screenings.count { it.cinemaId == "kulturhuset_skaris" })
    assertEquals(1, page.screenings.count { it.cinemaId == "kulturhuset_husby" })

    // The index carries a real offset, so nothing is inferred about the time zone.
    val laban = page.screenings.first { it.title.contains("Laban") }
    val local = laban.startTime.atZone(SwedishDates.STOCKHOLM)
    assertEquals(12, local.hour)
    assertEquals(30, local.minute)
    // The Drupal title wraps this one in its strand ("Knattebio: …"); the ticketing name does
    // not, which is why that is the one we read. The price is worth keeping either way.
    assertEquals("Lilla Spöket Laban - spökdags", laban.title)
    assertEquals(50, laban.priceSek)
    assertTrue(laban.bookingUrl.contains("tix.kulturhusetstadsteatern.se"))

    // A Korean original title survives, as everywhere else.
    assertEquals("기생충", page.screenings.first { it.title == "Parasite" }.originalTitle)
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

  // --- Bio Skandia: its Tickster storefront ---

  @Test
  fun `skandia reads its tickster calendar`() {
    val screenings = SkandiaSource().parse(fixture("skandia_tickster.html"), cinema("bio_skandia", "Bio Skandia"))

    // Six tiles in, four films out: the stand-up night and the guided tour are not screenings.
    assertEquals(4, screenings.size)
    assertTrue(screenings.none { it.title.contains("Masood") })
    assertTrue(screenings.none { it.title.contains("Guidad") })

    // Tickster publishes an explicit date, so nothing here is inferred from a month name.
    val odyssey = screenings.single { it.title == "The Odyssey" }
    val local = odyssey.startTime.atZone(SwedishDates.STOCKHOLM)
    assertEquals(2026, local.year)
    assertEquals(9, local.monthValue)
    assertEquals(6, local.dayOfMonth)
    assertEquals(17, local.hour)
    // "(70MM)" is how it is projected, not what it is called.
    assertTrue(odyssey.formatTags.any { it.equals("70MM", ignoreCase = true) })

    // A Korean original title is kept — it is what TMDB indexes the film under.
    assertEquals("기생충", screenings.single { it.title == "Parasite" }.originalTitle)
    // And a festival note comes off without taking the original title with it.
    assertEquals("호프", screenings.single { it.title == "Hope" }.originalTitle)

    // The buy button is a postback, so the link is rebuilt from the tile's own event code.
    assertEquals("https://secure.tickster.com/sv/7zu35wv8mhr86gj", odyssey.bookingUrl)
  }

  @Test
  fun `a december listing seen in january resolves to the coming year`() {
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
