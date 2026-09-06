package se.kinosthlm.app

import java.time.Instant
import java.time.format.DateTimeFormatter
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
import se.kinosthlm.app.data.source.SwedishDates
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

  /**
   * Prints every screening each open-programme source can see, so the listings can be checked
   * against the cinema's own site by eye.
   *
   * This exists because "the canary is green" turned out not to mean "we are reading the
   * programme correctly". Capitol's adapter was returning showings the whole time — just under
   * titles like "Afternoon Tea: Amelie från Montmartre", which nothing could ever match. An
   * assertion that something came back could not see that; a printed list can.
   *
   * Asserts nothing beyond the sources being readable: it is a report, and the eye reading it is
   * the point.
   */
  @Test
  fun `print every screening found, for manual checking`() = runTest {
    val everything = watchlistOf("anything")
    val sources =
      listOf(
        Triple("Bio Capitol", CapitolSource() as CinemaSource, "bio_capitol"),
        Triple("Bio Rio", BioRioSource(), "bio_rio"),
        Triple("Biocafé Tellus", TellusSource(), "bio_tellus"),
        Triple("Bio Skandia", SkandiaSource(), "bio_skandia"),
      )

    val stamp = DateTimeFormatter.ofPattern("EEE d MMM HH:mm").withZone(SwedishDates.STOCKHOLM)

    for ((name, source, id) in sources) {
      val screenings =
        source.fetchScreenings(
          cinemas = listOf(cinema(id, name, source)),
          watchlist = everything,
          from = from,
          to = to,
        )

      println("")
      println("===== $name: ${screenings.size} screenings over the next ${SettingsStore.DEFAULT_HORIZON_DAYS} days =====")
      val byTitle = screenings.groupBy { it.title }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
      println("--- ${byTitle.size} distinct films ---")
      for ((title, shows) in byTitle) {
        val dates = shows.sortedBy { it.startTime }.joinToString(", ") { stamp.format(it.startTime) }
        val identity =
          listOfNotNull(shows.first().year?.toString(), shows.first().imdbId)
            .joinToString(" ")
            .ifBlank { "no id" }
        println("  $title [$identity]  ->  $dates")
      }
    }

  }

  /**
   * Every bookable showing Capitol advertises has to come out the other side as a screening.
   *
   * Counting the output alone cannot tell "we read the page correctly" from "we read half of
   * it" — a parser that silently drops a row it does not recognise still returns a healthy-
   * looking list. So this counts the booking links on the page itself and insists the parse
   * accounts for all of them.
   *
   * Each showing card carries one `/boka/{id}` link, plus a second "Köp biljetter" link to the
   * same id; distinct ids are therefore the true number of showings.
   */
  @Test
  fun `capitol parses every bookable showing on a day page`() = runTest {
    val source = CapitolSource()
    val day = java.time.LocalDate.now(SwedishDates.STOCKHOLM).plusDays(7)
    val html =
      Http.getString("https://www.capitolbio.se/filmer?datum=$day", accept = "text/html")

    val advertised =
      Regex("""href="/boka/(\d+)"""").findAll(html).map { it.groupValues[1] }.toSet()
    assumeTrue("Nothing is showing at Capitol on $day", advertised.isNotEmpty())

    val parsed =
      source.parseDay(
        html = html,
        baseUrl = "https://www.capitolbio.se/filmer",
        cinema = cinema("bio_capitol", "Bio Capitol", source),
        day = day,
      )

    val parsedIds = parsed.mapNotNull { it.bookingUrl.substringAfterLast('/').takeIf(String::isNotBlank) }.toSet()
    val missed = advertised - parsedIds
    assertTrue(
      "Capitol advertised ${advertised.size} showings on $day but we parsed ${parsedIds.size}; " +
        "missed booking ids: $missed",
      missed.isEmpty(),
    )
    // And no showing may be counted twice, which the duplicate "Köp biljetter" link invites.
    assertTrue(
      "Parsed ${parsed.size} rows for ${parsedIds.size} distinct showings — something is doubled",
      parsed.size == parsedIds.size,
    )
  }

  /**
   * The check that answers "is this cinema broken, or is nothing I want playing there?"
   *
   * Every venue in Stockholm has *something* on in the next fortnight. So a source returning
   * nothing over that window is a broken adapter, not a quiet fortnight — which is precisely the
   * question that could not be answered about Bio Capitol from inside the app, where an empty
   * result and a failed parse look identical.
   *
   * Deliberately unfiltered by watchlist: this asks whether we can read the programme at all.
   */
  @Test
  fun `every cinema has screenings within the next two weeks`() = runTest {
    val fortnight = from.plus(14, ChronoUnit.DAYS)
    val everything = watchlistOf("anything")

    // Sources that publish a whole programme and let us filter afterwards.
    val openProgramme =
      listOf(
        Triple("Bio Rio", BioRioSource() as CinemaSource, "bio_rio"),
        Triple("Bio Capitol", CapitolSource(), "bio_capitol"),
        Triple("Biocafé Tellus", TellusSource(), "bio_tellus"),
        Triple("Bio Skandia", SkandiaSource(), "bio_skandia"),
      )

    val empty = mutableListOf<String>()
    for ((name, source, id) in openProgramme) {
      val screenings =
        runCatching {
            source.fetchScreenings(
              cinemas = listOf(cinema(id, name, source)),
              watchlist = everything,
              from = from,
              to = fortnight,
            )
          }
          .getOrElse {
            empty += "$name threw ${it::class.simpleName}: ${it.message}"
            continue
          }
      if (screenings.none { it.startTime.isBefore(fortnight) }) empty += "$name returned nothing"
    }

    // Filmstaden narrows by watchlist *before* fetching, so handing it a watchlist of nothing in
    // particular proves nothing. Feed it what it is itself advertising, so an empty result really
    // does mean the adapter has stopped working.
    val catalogue = Http.getString("https://services.cinema-api.com/movie/scheduled/sv/1/200/false")
    val onRelease =
      org.json.JSONObject(catalogue).getJSONArray("items").let { array ->
        (0 until minOf(array.length(), 10)).map { array.getJSONObject(it).getString("title") }
      }
    val filmstaden = FilmstadenSource()
    val filmstadenShows =
      filmstaden.fetchScreenings(
        cinemas = listOf(cinema("filmstaden_sergel", "Filmstaden Sergel", filmstaden, "NCG27927")),
        watchlist = watchlistOf(*onRelease.toTypedArray()),
        from = from,
        to = fortnight,
      )
    if (filmstadenShows.isEmpty()) empty += "Filmstaden Sergel returned nothing"

    assertTrue(
      "No screenings in the next two weeks from: ${empty.joinToString("; ")}",
      empty.isEmpty(),
    )
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
  fun `skandia still serves its tickster calendar`() = runTest {
    val source = SkandiaSource()
    val screenings =
      source.fetchScreenings(
        cinemas = listOf(cinema("bio_skandia", "Bio Skandia", source)),
        watchlist = watchlistOf("anything"),
        from = from,
        to = to,
      )

    // The failure this guards is specific and quiet: Tickster answers a cookie-less request with
    // a "session timed out" page and a 200, so a broken session looks exactly like a cinema with
    // nothing on. Skandia always has something on.
    assertTrue(
      "Bio Skandia returned nothing — the session handshake or the tile markup has changed",
      screenings.isNotEmpty(),
    )
    assertTrue(screenings.all { it.title.isNotBlank() })
    assertTrue(screenings.all { it.bookingUrl.contains("tickster.com") })
  }
}
