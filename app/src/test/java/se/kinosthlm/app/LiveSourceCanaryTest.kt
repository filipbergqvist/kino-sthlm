package se.kinosthlm.app

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http
import se.kinosthlm.app.data.prefs.SettingsStore
import se.kinosthlm.app.data.source.BioRioSource
import se.kinosthlm.app.data.source.CapitolSource
import se.kinosthlm.app.data.source.CinemaSource
import se.kinosthlm.app.data.source.FilmstadenSource
import se.kinosthlm.app.data.source.SkandiaSource
import se.kinosthlm.app.data.source.TellusSource

/**
 * Hits the real cinema sites and asserts each one still returns usable data.
 *
 * **These tests talk to the internet and are skipped unless `KINO_LIVE_TESTS=1`.** CI runs them
 * on a schedule so a site redesign surfaces as a failed run rather than as a user wondering why
 * KinoSthlm has gone quiet. They are deliberately loose: they check that a source is reachable
 * and its shape still parses, not that any particular film is playing.
 *
 * Run locally with:
 * ```
 * KINO_LIVE_TESTS=1 ./gradlew test --tests '*LiveSourceCanaryTest*'
 * ```
 */
class LiveSourceCanaryTest {

  @Before
  fun requireOptIn() {
    assumeTrue(
      "Set KINO_LIVE_TESTS=1 to run the live source canaries",
      System.getenv("KINO_LIVE_TESTS") == "1",
    )
  }

  private val from: Instant = Instant.now()
  private val to: Instant = from.plus(SettingsStore.DEFAULT_HORIZON_DAYS, ChronoUnit.DAYS)

  private fun cinema(id: String, name: String, source: CinemaSource, remoteId: String? = null) =
    Cinema(
      id = id,
      name = name,
      district = "Stockholm",
      address = "",
      websiteUrl = "",
      sourceId = source.id,
      remoteId = remoteId,
    )

  /**
   * A watchlist wide enough that *something* matches whatever is on release. Sources that filter
   * by watchlist (Filmstaden, Skandia) would otherwise legitimately return nothing and we could
   * not tell that apart from a breakage.
   */
  private fun watchlistOf(vararg titles: String) =
    titles.map { WatchlistItem(id = "test:$it", title = it) }

  @Test
  fun `filmstaden api is reachable and returns a catalogue`() = runTest {
    // The catalogue endpoint alone proves the API contract: no watchlist filtering involved.
    val json = Http.getString("https://services.cinema-api.com/movie/scheduled/sv/1/200/false")
    val items = org.json.JSONObject(json).getJSONArray("items")
    assertTrue("Filmstaden catalogue was empty", items.length() > 0)

    val first = items.getJSONObject(0)
    for (field in listOf("ncgId", "title", "slug")) {
      assertTrue("Filmstaden movie is missing '$field'", first.has(field))
    }

    // And a show lookup for that film still returns venues and times.
    val shows =
      Http.getString(
        "https://services.cinema-api.com/show/stripped/sv/1/50/" +
          "?filter.movieNcgId=${first.getString("ncgId")}&Channel=Web"
      )
    val showItems = org.json.JSONObject(shows).getJSONArray("items")
    assertTrue("Filmstaden returned no showings for a scheduled film", showItems.length() > 0)
    for (field in listOf("cId", "utc")) {
      assertTrue("Filmstaden show is missing '$field'", showItems.getJSONObject(0).has(field))
    }
  }

  @Test
  fun `filmstaden source maps stockholm venues`() = runTest {
    val source = FilmstadenSource()
    val catalogue = Http.getString("https://services.cinema-api.com/movie/scheduled/sv/1/200/false")
    val titles =
      org.json.JSONObject(catalogue).getJSONArray("items").let { array ->
        (0 until minOf(array.length(), 10)).map { array.getJSONObject(it).getString("title") }
      }

    val screenings =
      source.fetchScreenings(
        cinemas =
          listOf(
            cinema("filmstaden_sergel", "Filmstaden Sergel", source, "NCG27927"),
            cinema("filmstaden_rigoletto", "Filmstaden Rigoletto", source, "NCG76480"),
            cinema("filmstaden_scandinavia", "Filmstaden Scandinavia", source, "NCG41487"),
          ),
        watchlist = watchlistOf(*titles.toTypedArray()),
        from = from,
        to = to,
      )

    assertTrue("No Filmstaden screenings for the ten films currently on release", screenings.isNotEmpty())
    assertTrue(screenings.all { it.startTime.isAfter(from.minusSeconds(60)) })
    assertTrue(screenings.all { it.bookingUrl.startsWith("https://www.filmstaden.se/film/") })
  }

  @Test
  fun `bio rio still publishes screening events`() = runTest {
    val source = BioRioSource()
    val screenings =
      source.fetchScreenings(
        cinemas = listOf(cinema("bio_rio", "Bio Rio", source)),
        watchlist = watchlistOf("anything"),
        from = from,
        to = to,
      )
    assertTrue("Bio Rio returned no screenings — the calendar page may have changed", screenings.isNotEmpty())
    assertTrue(screenings.all { it.title.isNotBlank() })

    // Bio Rio's repertory programme runs months out, and a horizon shorter than that silently
    // dropped exactly those screenings (Barry Lyndon, 22 days out, against a 21-day window).
    val beyondThreeWeeks = from.plus(21, ChronoUnit.DAYS)
    assertTrue(
      "Bio Rio returned nothing beyond three weeks — the search horizon is too narrow again",
      screenings.any { it.startTime.isAfter(beyondThreeWeeks) },
    )
  }

  @Test
  fun `tellus events api still responds`() = runTest {
    val source = TellusSource()
    val screenings =
      source.fetchScreenings(
        cinemas = listOf(cinema("bio_tellus", "Biocafé Tellus", source)),
        watchlist = watchlistOf("anything"),
        from = from,
        to = to,
      )
    assertTrue("Tellus returned no events — check the Events Calendar API", screenings.isNotEmpty())
  }

  @Test
  fun `capitol programme still parses`() = runTest {
    val source = CapitolSource()
    val screenings =
      source.fetchScreenings(
        cinemas = listOf(cinema("bio_capitol", "Bio Capitol", source)),
        watchlist = watchlistOf("anything"),
        from = from,
        // A week is plenty to find showings without hammering the site.
        to = from.plus(7, ChronoUnit.DAYS),
      )
    assertTrue("Capitol returned no showings — the programme markup may have changed", screenings.isNotEmpty())
    assertTrue(screenings.all { it.bookingUrl.contains("/boka/") })
  }

  @Test
  fun `tmdb still identifies films, series and ambiguous titles`() = runTest {
    val lookup = se.kinosthlm.app.data.watchlist.TitleLookup()
    assumeTrue("Set TMDB_API_KEY to run this canary", lookup.isConfigured)

    // A Swedish title TMDB indexes under its English release name: the alias path.
    val swedish = lookup.lookup("Körkarlen")
    assertTrue("No candidates for Körkarlen", swedish.films.isNotEmpty())

    // A title shared by several films: the reason the review flow exists.
    val ambiguous = lookup.lookup("Nosferatu")
    assertTrue("Nosferatu should offer more than one film", ambiguous.films.size > 1)

    // Something that is unambiguously not a film, so series filtering keeps working.
    val series = lookup.lookup("The Sopranos")
    assertTrue("The Sopranos should not be classified as a film", series.isSeries)

    // And an IMDb link must still resolve to exactly one film, for adding by hand.
    val byId = lookup.lookupByImdbId("https://www.imdb.com/title/tt0013442/")
    assertTrue("IMDb id lookup returned nothing", byId != null && byId.isFilm)

    // Fetching a film straight by TMDB id — how a Trakt import backfills its poster — must
    // still return one with a poster and a synopsis attached.
    val details = lookup.fetchMovieDetails(byId!!.tmdbId)
    assertTrue("Movie details lookup returned nothing", details != null)
    assertTrue("Movie details had no poster", details?.posterUrl != null)
  }

  @Test
  fun `skandia index still lists films`() = runTest {
    // Index-only: whether any given film is on the watchlist varies week to week.
    val films =
      SkandiaSource().parseIndex(
        Http.getString("https://bioskandia.se/filmer/", accept = "text/html"),
        "https://bioskandia.se/filmer/",
      )
    assertTrue("Bio Skandia listed no films — the index markup may have changed", films.isNotEmpty())
    assertTrue(films.all { it.url.contains("/filmer/") })
  }

  @Test
  fun `skandia film pages still carry showtimes`() = runTest {
    val source = SkandiaSource()
    val films =
      source.parseIndex(
        Http.getString("https://bioskandia.se/filmer/", accept = "text/html"),
        "https://bioskandia.se/filmer/",
      )
    assumeTrue("No films listed to check", films.isNotEmpty())

    val venue = cinema("bio_skandia", "Bio Skandia", source)
    // At least one currently-listed film must expose a parseable showtime row.
    val anyShowtimes =
      films.take(4).any { film ->
        source
          .parseFilmPage(
            html = Http.getString(film.url, accept = "text/html"),
            baseUrl = film.url,
            cinema = venue,
            title = film.title,
          )
          .isNotEmpty()
      }
    assertTrue("No Bio Skandia film page yielded showtimes", anyShowtimes)
  }
}
