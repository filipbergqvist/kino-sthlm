package se.kinosthlm.app.data.source

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http

/**
 * Bio Capitol, Sankt Eriksplan.
 *
 * Its ticketing backend (MyCloudCinema) is behind a login, but the programme page is fully
 * server-rendered: one `<a href="/boka/{id}">` per showing, carrying the film title, the time in
 * a screen-reader span, and the auditorium. One request per day in range.
 */
class CapitolSource(private val programmeUrl: String = PROGRAMME) : CinemaSource {

  override val id = SOURCE_ID
  override val label = "Bio Capitol"

  /**
   * Film-page answers, remembered for the life of the process. A film's IMDb id and release year
   * do not change, so re-asking on every sync would be pure waste.
   */
  private val filmDetails = java.util.concurrent.ConcurrentHashMap<String, FilmDetails>()

  override suspend fun fetchScreenings(
    cinemas: List<Cinema>,
    watchlist: List<WatchlistItem>,
    from: Instant,
    to: Instant,
  ): List<RawScreening> = withContext(Dispatchers.IO) {
    val cinema = cinemas.firstOrNull() ?: return@withContext emptyList()

    val firstDay = LocalDateTime.ofInstant(from, SwedishDates.STOCKHOLM).toLocalDate()
    val lastDay = LocalDateTime.ofInstant(to, SwedishDates.STOCKHOLM).toLocalDate()
    val days = Duration.between(from, to).toDays().toInt().coerceIn(0, MAX_DAYS)

    val screenings = mutableListOf<RawScreening>()
    var day: LocalDate = firstDay
    var fetched = 0
    while (!day.isAfter(lastDay) && fetched <= days) {
      screenings += fetchDay(cinema, day, from, to)
      day = day.plusDays(1)
      fetched++
    }
    attachFilmIds(screenings)
  }

  /**
   * Replace each screening's guessed identity with the one Capitol already knows.
   *
   * Every showing card links to a "Läs mer" page, and that page names the film's IMDb id and
   * release year outright. An IMDb id is an exact answer where a title is a guess — it is the
   * first thing the matcher tries — so it is worth one request per *film* to stop relying on
   * TMDB recognising a Swedish release title at all.
   *
   * One request per film, not per showing, and the answers are remembered for the life of the
   * process: a film's IMDb id does not change, so repeat syncs pay nothing. Bounded per run so
   * a first sync over a long horizon does not turn into eighty round trips before it reports
   * anything; whatever is left is picked up next time.
   */
  private fun attachFilmIds(screenings: List<RawScreening>): List<RawScreening> {
    var budget = MAX_FILM_LOOKUPS_PER_RUN

    return screenings.map { screening ->
      val page = screening.filmPageUrl ?: return@map screening
      val known =
        filmDetails[page]
          ?: run {
            if (budget <= 0) return@map screening
            budget--
            // A film page that will not load is not worth failing the whole cinema over: the
            // screening still stands on its cleaned title.
            val details = runCatching { fetchFilmDetails(page) }.getOrNull() ?: FilmDetails()
            filmDetails[page] = details
            details
          }

      screening.copy(
        imdbId = known.imdbId ?: screening.imdbId,
        year = known.year ?: screening.year,
      )
    }
  }

  private fun fetchFilmDetails(url: String): FilmDetails {
    val html = Http.getString(url, accept = "text/html")
    return FilmDetails(
      imdbId = IMDB_ID.find(html)?.value,
      year = COPYRIGHT_YEAR.find(html)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1880..2100 },
    )
  }

  /** What a Capitol film page tells us about the film, beyond its branded title. */
  private data class FilmDetails(val imdbId: String? = null, val year: Int? = null)

  private fun fetchDay(
    cinema: Cinema,
    day: LocalDate,
    from: Instant,
    to: Instant,
  ): List<RawScreening> =
    parseDay(
        html = Http.getString("$programmeUrl?datum=${DATE.format(day)}", accept = "text/html"),
        baseUrl = programmeUrl,
        cinema = cinema,
        day = day,
      )
      .filter { !it.startTime.isBefore(from) && !it.startTime.isAfter(to) }

  /** One day's programme page. Split out from the fetch so the selectors are testable. */
  internal fun parseDay(
    html: String,
    baseUrl: String,
    cinema: Cinema,
    day: LocalDate,
  ): List<RawScreening> {
    val document = Jsoup.parse(html, baseUrl)

    return document.select("a[href^=/boka/]").mapNotNull { link ->
      // The visible label is the film title; the time rides along in a screen-reader span as
      // " (18:00)". Rows whose label is just the ticket CTA are duplicates of a row above.
      val srOnly = link.selectFirst("span.sr-only") ?: return@mapNotNull null
      val time = SwedishDates.parseTime(srOnly.text()) ?: return@mapNotNull null

      // Capitol brands most of its programme — "Afternoon Tea: Amelie från Montmartre",
      // "Frukostbio: Breakfast at Tiffany's", "La Grazia - Premiär 11 sep". None of that is the
      // film's name, and leaving it on meant TMDB could not identify a single one of them, so
      // films genuinely on the schedule never reached the watchlist.
      val listed = link.ownText().trim().ifBlank { return@mapNotNull null }
      val cleaned = ProgrammeStrands.clean(listed)

      val start = LocalDateTime.of(day, time).atZone(SwedishDates.STOCKHOLM).toInstant()

      // "Salong" label followed by the number, both inside aria-hidden spans.
      val auditorium = link.select("span[aria-hidden=true] span")
        .firstOrNull { it.text().trim().toIntOrNull() != null }
        ?.let { "Salong ${it.text().trim()}" }

      RawScreening(
        cinemaId = cinema.id,
        cinemaName = cinema.name,
        title = cleaned.title,
        originalTitle = cleaned.originalTitle,
        year = cleaned.year,
        startTime = start,
        auditorium = auditorium,
        bookingUrl = link.absUrl("href").ifBlank { baseUrl },
        filmPageUrl = filmPageFor(link),
      )
    }
  }

  /**
   * The "Läs mer" link belonging to this showing.
   *
   * Each card holds the booking link and the film-page link as siblings under a shared ancestor,
   * so walk up a few levels and take the first `/filmer/{slug}/{id}` found. Bounded, because
   * climbing to the document root would happily return some other film's card.
   */
  private fun filmPageFor(bookingLink: Element): String? {
    var node: Element? = bookingLink.parent()
    var depth = 0
    while (node != null && depth < FILM_LINK_SEARCH_DEPTH) {
      node.selectFirst("a[href~=^/filmer/[^/]+/\\d+$]")?.let { return it.absUrl("href").ifBlank { null } }
      node = node.parent()
      depth++
    }
    return null
  }

  companion object {
    const val SOURCE_ID = "bio_capitol"
    private const val PROGRAMME = "https://www.capitolbio.se/filmer"

    /**
     * How many day-pages to walk at most.
     *
     * This was 14, on the belief that Capitol only publishes a fortnight ahead. It does not —
     * its repertory and Afternoon Tea strands are on sale months out, and cutting the walk at
     * two weeks hid exactly the one-off screenings worth knowing about early. One page per day
     * is not free, so it is still bounded, but the bound is now the user's own search horizon
     * rather than a guess about the cinema.
     */
    private const val MAX_DAYS = 95
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** How far up from a booking link to look for that card's "Läs mer" link. */
    private const val FILM_LINK_SEARCH_DEPTH = 4

    /**
     * New film pages fetched per sync. Everything already looked up is free, so this only ever
     * caps the first run after a fresh start; the rest arrives on the next sync.
     */
    private const val MAX_FILM_LOOKUPS_PER_RUN = 40

    private val IMDB_ID = Regex("""tt\d{7,}""")
    private val COPYRIGHT_YEAR = Regex(""""copyrightYear"\s*:\s*(\d{4})""")
  }
}
