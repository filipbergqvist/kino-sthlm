package se.kinosthlm.app.data.source

import java.text.Normalizer
import java.util.Locale

/**
 * Strips a cinema's own programme branding off a listing title.
 *
 * Swedish independents rarely list a film under just its name. Bio Capitol runs strands —
 * "Afternoon Tea: Amelie från Montmartre", "Frukostbio: Breakfast at Tiffany's",
 * "Bröderna Marx: Duck Soup" — and tags premieres, guest appearances and anniversaries on the
 * end: "La Grazia - Premiär 11 sep", "Bad Apples med regissörsbesök!",
 * "Friday the 13th - 46 årsjubileum".
 *
 * None of that is part of the film's name, and all of it defeats matching: TMDB has never heard
 * of "Afternoon Tea: Amelie från Montmartre", and neither has anyone's watchlist. That is why
 * films genuinely on Capitol's schedule never reached the app.
 *
 * The prefixes are an explicit allowlist rather than "everything before the first separator" —
 * plenty of real titles contain a colon, and cutting "Spider-Man: Brand New Day" down to
 * "Brand New Day" would trade one matching failure for a worse one.
 */
object ProgrammeStrands {

  /**
   * What a listing title actually told us.
   *
   * A repertory cinema often knows more than the name: a bracketed year settles which of two
   * same-named films this is, and a bracketed original title is exactly what TMDB indexes a
   * foreign film under. Both are worth keeping rather than discarding with the branding.
   */
  data class Cleaned(
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    /** Presentation notes worth keeping as tags: "70mm", "4K", "3D". */
    val formats: List<String> = emptyList(),
  )

  /**
   * Bracketed notes that describe *how* a film is shown, not what it is.
   *
   * These have to be told apart from a bracketed original title — Bio Skandia writes both, as
   * "The Odyssey (70MM)" and "Parasite (기생충)". The Korean is genuinely the film's original
   * title and exactly what TMDB indexes it under, so it is worth keeping; "70MM" is a projector.
   */
  private val FORMAT_NOTES =
    setOf("70mm", "35mm", "16mm", "4k", "2k", "dcp", "3d", "imax", "70 mm", "35 mm", "digitalt", "analogt")

  /**
   * Programme-strand labels that precede the film's name. Compared accent-folded and lowercase,
   * so "Familjematiné" and "Familjematine" both match.
   */
  private val STRAND_PREFIXES =
    setOf(
      "afternoon tea",
      "babybio",
      "barnbio",
      "broderna marx",
      // A collaboration credit, not part of the film's name: "Cinemateket: Persona".
      "cinemateket",
      "dokumentar",
      "dokumentarbio",
      "familjematine",
      "filmfeber",
      "filmklubb",
      "filmklubben",
      "frukostbio",
      "julbio",
      "knattebio",
      "kortfilm",
      "mammabio",
      "matine",
      "musikal",
      "musikalbio",
      "nyarsbio",
      "seniorbio",
      "sommarbio",
      "sondagsbio",
    )

  /**
   * Strands that introduce the film with "med" rather than a colon: "Dress-Along med Chicago",
   * "Partyalong med Mamma Mia", "Sing-Along med Grease".
   *
   * These have to be an allowlist too, and a tight one — "Bus och mysterier med Alfons Åberg"
   * and "Frukost med Alzheimer" are whole titles, and a general "cut at ' med '" rule would
   * shred both.
   */
  private val MED_PREFIXES = setOf("dress-along", "dressalong", "partyalong", "party-along", "sing-along", "singalong", "quiz")

  /** Capitol uses a colon for most strands but a full stop for some, and a dash for others. */
  private val SEPARATORS = charArrayOf(':', '.', '–', '—')

  /** A trailing release year in brackets: "Daughters of Darkness (1971)". */
  private val TRAILING_YEAR = Regex("""\s*\((\d{4})\)\s*$""")

  /** A trailing original title in brackets: "Blommor av Stal (Steel Magnolias)". */
  private val TRAILING_PARENTHETICAL = Regex("""\s*\(([^)]{2,})\)\s*$""")

  private const val MONTHS = "jan|feb|mar|apr|maj|jun|jul|aug|sep|okt|nov|dec"

  /** "Smygpremiär: Bad Apples", "Förpremiär – La Grazia". */
  private val LEADING_PREMIERE =
    Regex("""^\s*(?:smyg|ny|för|for)?premi[aä]r\s*[:–—-]\s*""", RegexOption.IGNORE_CASE)

  /**
   * Event tags appended to a title. Each is anchored to the end and tolerant of a trailing
   * exclamation mark, which Capitol uses freely.
   */
  private val SUFFIX_PATTERNS =
    listOf(
      // "- Premiär 11 sep", "– Smygpremiär 18 september", "Nypremiär"
      Regex("""\s*[-–—]?\s*(?:smyg|ny|för|for)?premi[aä]r\b.*$""", RegexOption.IGNORE_CASE),
      // "med regissörsbesök!", "- med livemusik", "med introduktion".
      // A general "cut at ' med '" rule is not safe — "Bus och mysterier med Alfons Åberg" and
      // "Frukost med Alzheimer" are whole titles — so the noun is spelled out.
      Regex(
        """\s*[-–—]?\s*med\s+(?:regiss[oö]rs\p{L}*|live\s?musik|livemusik|introduktion|inledning|samtal|[oö]ppet\s+samtal|q\s*&\s*a)\s*[!.]*\s*$""",
        RegexOption.IGNORE_CASE,
      ),
      Regex("""\s*[-–—]?\s*\d{1,3}\s*[aå]rsjubileum\s*!*\s*$""", RegexOption.IGNORE_CASE),
      // Festival furniture: "Hope - Opening film", "Hope - with panel discussion".
      Regex(
        """\s*[-–—]\s*(?:with\s+)?(?:opening|closing)\s+film\s*$|""" +
          """\s*[-–—]\s*(?:with\s+)?panel\s+discussion\s*$|""" +
          """\s*[-–—]\s*med\s+panelsamtal\s*$""",
        RegexOption.IGNORE_CASE,
      ),
      Regex("""\s*\(?\s*\d{1,2}\s*[aå]r\s*\)?\s*!*\s*$""", RegexOption.IGNORE_CASE),
      Regex("""\s*\d{1,2}\s*(?:$MONTHS)[a-zäöå]*\.?\s*!*\s*$""", RegexOption.IGNORE_CASE),
    )

  /**
   * Calendar entries that are events rather than screenings.
   *
   * The independents run their venues as cultural spaces and publish everything through one
   * calendar: Tellus has a jazz programme and a supper club, Skandia does guided tours, live
   * performances and birthday parties. None of it is a film. They could be left in — nothing
   * would ever match them — but each costs a TMDB lookup and can land in the review queue as a
   * name no film has, which is a question the user cannot answer.
   */
  private val NON_FILM_EVENTS =
    listOf(
      "jazz",
      "torsdagssoppa",
      // Secret-cinema nights: both venues run them, and they have no title to match on by
      // design — the whole point is that you find out what it is when it starts.
      "filmsalong",
      "secret cinema",
      "hemlig film",
      "programsläpp",
      "programslapp",
      "block party",
      "guidad visning",
      "guided tour",
      "quiz",
      "vernissage",
      "konsert",
      "live performance",
    )

  /** True when a calendar entry is an event the cinema is hosting, not a film it is showing. */
  fun isNonFilmEvent(title: String): Boolean {
    val folded = fold(title)
    return NON_FILM_EVENTS.any { folded.contains(fold(it)) }
  }

  /**
   * The film's own name, plus anything the branding was hiding. Returns [title] unchanged when
   * nothing matches, and never returns blank — a listing that is *only* branding is left alone
   * rather than reduced to nothing.
   */
  fun clean(title: String): Cleaned {
    var result = title.trim()

    // The label before the separator, matched on its first word rather than the whole thing:
    // venues qualify their strands ("Dokumentär med regissörsbesök: …", "Frukostbio söndag: …")
    // and enumerating every combination is a losing game.
    val cut = result.indexOfFirst { it in SEPARATORS }
    if (cut > 0) {
      val label = fold(result.substring(0, cut))
      if (STRAND_PREFIXES.any { label == it || label.startsWith("$it ") }) {
        result = result.substring(cut + 1).trim()
      }
    }

    // "Dress-Along med Chicago" → "Chicago".
    val med = result.indexOf(" med ", ignoreCase = true)
    if (med > 0 && fold(result.substring(0, med)) in MED_PREFIXES) {
      result = result.substring(med + 5).trim()
    }

    // "Smygpremiär: Bad Apples" — a premiere note can lead as well as trail.
    result = LEADING_PREMIERE.replace(result, "").trim()

    // Suffixes first, so "Hope (호프) - Opening film" loses the festival note and leaves the
    // bracket for the loop below to read as the original title.
    result = stripSuffixes(result)

    // A bracketed year is a fact about the film; a bracketed phrase is either how it is being
    // shown or, more usefully, its original title — which is what TMDB indexes a foreign film
    // under, and the only handle we have on a Korean or Japanese title listed in English.
    var year: Int? = null
    var originalTitle: String? = null
    val formats = mutableListOf<String>()

    TRAILING_YEAR.find(result)?.let { match ->
      val parsed = match.groupValues[1].toIntOrNull()?.takeIf { it in 1880..2100 }
      if (parsed != null) {
        year = parsed
        result = TRAILING_YEAR.replace(result, "").trim()
      }
    }

    // Repeatedly, because both can appear: "Parasite (기생충) (4K)".
    while (true) {
      val match = TRAILING_PARENTHETICAL.find(result) ?: break
      val inner = match.groupValues[1].trim()
      when {
        fold(inner) in FORMAT_NOTES -> formats += inner
        // A title, not a note. Two characters is enough — "호프" is a whole Korean title, and
        // requiring three would throw away exactly the short CJK originals this is here for.
        inner.length >= 2 && inner.any { it.isLetter() } && originalTitle == null ->
          originalTitle = inner
        else -> break
      }
      // And again after removing it: the two can be written in either order, so
      // "Hope - with panel discussion (호프)" only reveals its suffix once the bracket is gone.
      result = stripSuffixes(TRAILING_PARENTHETICAL.replace(result, "").trim())
    }

    return Cleaned(
      // Collapse the double spaces a hand-typed listing leaves behind ("The Mutation  (…)").
      title =
        result.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', ':', '!')
          .ifBlank { title.trim() },
      originalTitle = originalTitle,
      year = year,
      formats = formats,
    )
  }

  private fun stripSuffixes(raw: String): String {
    var result = raw
    for (pattern in SUFFIX_PATTERNS) {
      result = pattern.replace(result, "").trim()
    }
    return result
  }

  private fun fold(raw: String): String =
    Normalizer.normalize(raw.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
      .replace(Regex("""\p{Mn}+"""), "")
      .replace('ø', 'o')
      .replace('æ', 'a')
}
