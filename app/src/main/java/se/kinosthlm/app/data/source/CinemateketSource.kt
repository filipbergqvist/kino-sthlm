package se.kinosthlm.app.data.source

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.net.Http

/**
 * Cinemateket at Filmhuset — two auditoria, Bio Victor and Bio Mauritz.
 *
 * One programme page holds both. Which room a showing is in comes from its bio.se booking link
 * (`.../filmhuset/20260906/1630/Victor`), which also carries the date and time, so the Swedish
 * date headings above it never need parsing.
 *
 * Cinemateket writes its listings as "Title, Director" and prefixes its strands
 * ("REGISSÖRSBESÖK: …", "Unga Cinemateket: …"), both of which are stripped before matching —
 * TMDB has never heard of a film called "Mina drömmars stad, Ingvar Skogsberg".
 */
class CinemateketSource(private val programmeUrl: String = PROGRAMME) : CinemaSource {

  override val id = SOURCE_ID
  override val label = "Cinemateket"

  override suspend fun fetchScreenings(
    cinemas: List<Cinema>,
    watchlist: List<WatchlistItem>,
    from: Instant,
    to: Instant,
  ): List<RawScreening> = withContext(Dispatchers.IO) {
    if (cinemas.isEmpty()) return@withContext emptyList()
    parse(Http.getString(programmeUrl, accept = "text/html"), cinemas)
      .filter { !it.startTime.isBefore(from) && !it.startTime.isAfter(to) }
  }

  /**
   * Split out from the fetch so the selectors can be tested against a saved page.
   *
   * [cinemas] is every Filmhuset auditorium the user follows; a showing is kept only if its own
   * room is among them, so switching off Bio Mauritz really does switch it off.
   */
  internal fun parse(html: String, cinemas: List<Cinema>): List<RawScreening> {
    val byHall = cinemas.associateBy { (it.remoteId ?: it.name).lowercase() }
    val document = Jsoup.parse(html, programmeUrl)

    return document.select("div.article-tickets").flatMap { block ->
      // The "add to calendar" link is the best-behaved thing on the page: it carries the film's
      // name on its own, without the director credit and format note the visible heading wraps
      // it in ("25th Hour, Spike Lee (35 mm)"), plus the exact start and the auditorium.
      val ical = block.selectFirst("a[href*=iCal]")?.attr("href")
      val listedTitle =
        ical?.let { queryParam(it, "title") }
          ?: block.selectFirst("a span.underline")?.text()?.trim()
          ?: return@flatMap emptyList()
      if (listedTitle.isBlank() || ProgrammeStrands.isNonFilmEvent(listedTitle)) {
        return@flatMap emptyList()
      }

      // Cleaned first so a trailing "(35 mm)" becomes a format tag, and only then stripped of a
      // director — otherwise the credit is not the last thing on the line and is left alone.
      val cleaned = ProgrammeStrands.clean(listedTitle)
      val title = ProgrammeStrands.stripTrailingDirector(cleaned.title)

      block.select("a[href*=bio.se/biografer/]").mapNotNull { link ->
        val href = link.absUrl("href").ifBlank { link.attr("href") }
        val showing = BioSeLinks.parse(href) ?: return@mapNotNull null
        val cinema = byHall[showing.auditorium.lowercase()] ?: return@mapNotNull null

        RawScreening(
          cinemaId = cinema.id,
          cinemaName = cinema.name,
          title = title,
          originalTitle = cleaned.originalTitle,
          year = cleaned.year,
          startTime = showing.startTime,
          auditorium = "Bio ${showing.auditorium}",
          formatTags = cleaned.formats,
          bookingUrl = href,
        )
      }
    }
      .distinctBy { it.cinemaId to (it.title to it.startTime) }
  }

  /**
   * One query parameter out of a raw href.
   *
   * Hand-rolled because these are not properly encoded — the title arrives with real spaces in
   * it — so a strict URI parser refuses the whole thing.
   */
  private fun queryParam(href: String, name: String): String? {
    val query = href.substringAfter('?', "").takeIf { it.isNotEmpty() } ?: return null
    return query
      .split('&')
      .firstOrNull { it.startsWith("$name=") }
      ?.substringAfter('=')
      ?.replace('+', ' ')
      ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
  }

  companion object {
    const val SOURCE_ID = "cinemateket"

    /**
     * A high page number makes the list render everything at once rather than paging, which is
     * the whole reason this is one request instead of a walk.
     */
    private const val PROGRAMME =
      "https://www.filminstitutet.se/sv/se-och-samtala-om-film/cinemateket-stockholm/program/" +
        "?eventtype=&listtype=text&page=100"
  }
}
