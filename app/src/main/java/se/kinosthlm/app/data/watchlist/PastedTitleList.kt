package se.kinosthlm.app.data.watchlist

/**
 * Reads a hand-typed list of film names — one per line, as it would be kept in a notes app.
 *
 * Deliberately forgiving about how people actually write these: numbered lines, bullets, trailing
 * commas, a year in brackets or not, blank lines between sections. None of that is worth making
 * someone tidy up before pasting.
 *
 * It does *not* try to identify anything. A parsed line is a title and possibly a year; deciding
 * which film that is belongs to [TitleResolver], which knows how to ask when a name is shared.
 */
object PastedTitleList {

  data class Entry(val title: String, val year: Int?)

  /** Leading list decoration: "1.", "1)", "-", "*", "•". */
  private val LEADING_MARKER = Regex("""^\s*(?:\d{1,3}[.)]|[-*•])\s+""")

  /** A trailing year, bracketed or not: "Amadeus 1984", "Amadeus (1984)". */
  private val TRAILING_YEAR = Regex("""^(.*?)[\s,(]+(\d{4})\)?$""")

  fun parse(text: String): List<Entry> {
    // A release year can be a little ahead of now — an announced film — but not decades ahead.
    // Without the ceiling, "Blade Runner 2049" loses half its title to a year it does not have.
    val plausibleYears = 1880..(java.time.Year.now().value + 5)

    return text
      .lines()
      .map { it.replace(LEADING_MARKER, "").trim().trim(',').trim() }
      .filter { it.isNotEmpty() }
      .map { line ->
        val match = TRAILING_YEAR.find(line)
        val year = match?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in plausibleYears }
        val title =
          if (year != null) match!!.groupValues[1].trim().takeIf { it.isNotEmpty() } ?: line
          else line
        Entry(title, year)
      }
      // The same film twice in one paste is a typo, not two films.
      .distinctBy { it.title.lowercase() to it.year }
  }
}
