package se.kinosthlm.app

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.prefs.SettingsStore
import se.kinosthlm.app.data.source.BioRioSource
import se.kinosthlm.app.data.source.CapitolSource
import se.kinosthlm.app.data.source.CinemaSource
import se.kinosthlm.app.data.source.CinemateketSource
import se.kinosthlm.app.data.source.FagelBlaSource
import se.kinosthlm.app.data.source.KaskadSource
import se.kinosthlm.app.data.source.RawScreening
import se.kinosthlm.app.data.source.TellusSource
import se.kinosthlm.app.data.source.ZitaSource
import se.kinosthlm.app.data.watchlist.TitleLookup

/**
 * Reads every cinema we can read in full, then asks TMDB what each listing actually is, and
 * prints the answer per title.
 *
 * This is the report that makes a broken adapter obvious. A source can return plenty of
 * screenings and still be useless if none of its titles resolves — Bio Capitol did exactly that
 * for weeks, listing films as "Afternoon Tea: Amelie från Montmartre" while every canary stayed
 * green. Counting output cannot see that; naming what each listing resolved to can.
 *
 * Prints, per cinema: how many listings were parsed, what each distinct title resolved to, and
 * the final screening count. Three outcomes, because they need different fixes — resolved,
 * ambiguous (TMDB knows it but the cinema published no year to separate same-named films), and
 * not found (usually branding we have not stripped yet). Asserts nothing: TMDB genuinely does
 * not know every Swedish documentary, and failing the build for that would make the report
 * something to silence rather than read.
 *
 * ```
 * KINO_LIVE_TESTS=1 ./gradlew testDebugUnitTest --tests '*SourceParseReportTest*'
 * ```
 */
class SourceParseReportTest {

  /**
   * Everything printed, kept so it can be written to a file as well as the console.
   *
   * The file is the point of this in CI: Actions logs and artifacts both need authentication to
   * read, so a report that only reaches the console is one nobody can be pointed at afterwards.
   * Committed back to the repository, it can just be read.
   */
  private val lines = mutableListOf<String>()

  private fun report(line: String) {
    lines += line
    println(line)
  }

  @Before
  fun requireOptIn() {
    assumeTrue(
      "Set KINO_LIVE_TESTS=1 to run the source parse report",
      System.getenv("KINO_LIVE_TESTS") == "1",
    )
  }

  @After
  fun writeReport() {
    val path = System.getenv("KINO_REPORT_FILE")?.takeIf { it.isNotBlank() } ?: return
    val file = java.io.File(path)
    file.parentFile?.mkdirs()
    file.writeText(
      buildString {
        appendLine("# Cinema parse report")
        appendLine()
        appendLine("Generated ${Instant.now()}.")
        appendLine()
        appendLine(
          "What each cinema's listings resolve to on TMDB. Only \"not found\" points at a " +
            "parsing problem: \"ambiguous\" means TMDB knows the film but the cinema published " +
            "no year to tell it from its namesakes, and those still match by title against a " +
            "watchlist."
        )
        appendLine()
        appendLine("```")
        lines.forEach { appendLine(it) }
        appendLine("```")
      }
    )
  }

  private val from: Instant = Instant.now()
  private val to: Instant = from.plus(SettingsStore.DEFAULT_HORIZON_DAYS, ChronoUnit.DAYS)
  private val stamp = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(
    se.kinosthlm.app.data.source.SwedishDates.STOCKHOLM
  )

  private fun venue(id: String, name: String, source: CinemaSource, remoteId: String? = null) =
    Cinema(
      id = id,
      name = name,
      district = "Stockholm",
      address = "",
      websiteUrl = "",
      sourceId = source.id,
      remoteId = remoteId,
    )

  @Test
  fun `report what every open-programme source parses and resolves`() = runBlocking {
    val lookup = TitleLookup()
    assumeTrue("Set TMDB_API_KEY to resolve titles in this report", lookup.isConfigured)

    // Not the watchlist-narrowing sources: they only ever return films already on your list, so
    // there is nothing to diagnose about their resolution rate.
    val sources: List<Triple<String, CinemaSource, List<Cinema>>> =
      listOf(
        Triple("Bio Capitol", CapitolSource(), listOf(venue("bio_capitol", "Bio Capitol", CapitolSource()))),
        Triple("Bio Rio", BioRioSource(), listOf(venue("bio_rio", "Bio Rio", BioRioSource()))),
        Triple("Biocafé Tellus", TellusSource(), listOf(venue("bio_tellus", "Biocafé Tellus", TellusSource()))),
        Triple("Bio Fågel Blå", FagelBlaSource(), listOf(venue("bio_fagel_bla", "Bio Fågel Blå", FagelBlaSource()))),
        Triple("Bio Kaskad", KaskadSource(), listOf(venue("bio_kaskad", "Bio Kaskad", KaskadSource()))),
        Triple("Zita Folkets Bio", ZitaSource(), listOf(venue("zita", "Zita Folkets Bio", ZitaSource()))),
        Triple(
          "Cinemateket",
          CinemateketSource(),
          listOf(
            venue("cinemateket_victor", "Cinemateket — Bio Victor", CinemateketSource(), "Victor"),
            venue("cinemateket_mauritz", "Cinemateket — Bio Mauritz", CinemateketSource(), "Mauritz"),
          ),
        ),
      )

    val everything = listOf(WatchlistItem(id = "test:any", title = "anything"))
    var totalResolved = 0
    var totalAmbiguous = 0
    var totalFailed = 0

    for ((name, source, venues) in sources) {
      val screenings =
        runCatching { source.fetchScreenings(venues, everything, from, to) }
          .getOrElse {
            report("")
            report("===== $name =====")
            report("  COULD NOT READ: ${it::class.simpleName}: ${it.message}")
            continue
          }

      report("")
      report("===== $name =====")
      report("  Listings found: ${screenings.size}")

      val byTitle = screenings.groupBy { it.title }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
      var resolved = 0
      var ambiguous = 0
      var unresolved = 0
      for ((title, shows) in byTitle) {
        val first = shows.first()
        val match = resolve(lookup, first)
        when {
          match == null -> { unresolved++; totalFailed++ }
          match.startsWith("AMBIGUOUS") -> ambiguous++
          else -> resolved++
        }
        report("    ${describe(title, first)}  ->  ${match ?: "NOT FOUND — TMDB has nothing by this name"}")
        report("        ${shows.size} showing(s): ${shows.sortedBy { s -> s.startTime }.take(4).joinToString(", ") { s -> stamp.format(s.startTime) }}")
      }
      totalResolved += resolved
      totalAmbiguous += ambiguous

      report(
        "  Distinct titles: ${byTitle.size} — resolved $resolved, ambiguous $ambiguous, " +
          "not found $unresolved"
      )
      report("  Final screenings: ${screenings.size}")
    }

    report("")
    report(
      "===== TOTAL: $totalResolved resolved, $totalAmbiguous ambiguous, $totalFailed not found ====="
    )
    report(
      "Only \"not found\" points at a parsing problem — ambiguous means TMDB knows the film but " +
        "the cinema published no year to tell it from its namesakes."
    )
  }

  /** What the source gave us to work with, beyond the title alone. */
  private fun describe(title: String, screening: RawScreening): String =
    buildString {
      append(title)
      screening.year?.let { append(" ($it)") }
      screening.originalTitle?.let { append(" [orig: $it]") }
      screening.imdbId?.let { append(" [$it]") }
    }

  /**
   * The same route the app takes: IMDb id if the cinema gave one, otherwise a TMDB lookup.
   *
   * Three outcomes, not two, because they need different fixes. An outright miss usually means
   * the title is still carrying branding we have not stripped. "Several candidates" means TMDB
   * knows the film perfectly well but will not choose between same-named ones without a year —
   * so the cinema not publishing a year is the gap, and the app still matches these by title
   * against the watchlist. Only the first is a parsing problem.
   */
  private suspend fun resolve(lookup: TitleLookup, screening: RawScreening): String? {
    screening.imdbId?.let { imdb ->
      runCatching { lookup.lookupByImdbId(imdb) }.getOrNull()?.let { return format(it) }
    }
    runCatching { lookup.resolveBestMatch(screening.title, screening.year) }
      .getOrNull()
      ?.let { return format(it) }
    screening.originalTitle?.let { original ->
      runCatching { lookup.resolveBestMatch(original, screening.year) }
        .getOrNull()
        ?.let { return format(it) + " (via original title)" }
    }

    // Nothing settled it. Say which kind of unsettled.
    val candidates =
      runCatching { lookup.lookup(screening.title).films }.getOrDefault(emptyList())
    return when {
      candidates.isEmpty() -> null
      else ->
        "AMBIGUOUS — ${candidates.size} films share this name and the cinema gave no year: " +
          candidates.take(3).joinToString("; ") { format(it) }
    }
  }

  private fun format(candidate: TitleLookup.Candidate): String =
    "${candidate.title}${candidate.year?.let { " ($it)" } ?: ""} — tmdb:${candidate.tmdbId}"
}
