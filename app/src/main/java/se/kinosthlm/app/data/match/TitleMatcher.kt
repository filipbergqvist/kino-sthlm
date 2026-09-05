package se.kinosthlm.app.data.match

import java.text.Normalizer
import java.util.Locale
import se.kinosthlm.app.data.model.WatchlistItem

/**
 * Decides whether a film showing at a cinema is the film the user watchlisted.
 *
 * Deliberately conservative: a false positive means a wasted notification and, worse, a trip to
 * the wrong film, so every fuzzy path is gated on the release year agreeing.
 */
object TitleMatcher {

  /** Words that carry no distinguishing weight at the start of a title. */
  private val LEADING_ARTICLES = setOf("the", "a", "an", "den", "det", "de", "en", "ett")

  /**
   * Sequel numbering, folded to digits so "Part II", "Part Two" and "Del 2" all compare equal.
   *
   * Roman numerals and spelled-out numbers in both languages, because a Swedish cinema and an
   * English-language watchlist rarely write a sequel the same way.
   */
  private val NUMERALS = mapOf(
    // Roman
    "ii" to "2", "iii" to "3", "iv" to "4", "vi" to "6",
    "vii" to "7", "viii" to "8", "ix" to "9",
    // English
    "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
    "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
    // Swedish. "en" is left out on purpose: it is far more often the article than the number.
    "ett" to "1", "tva" to "2", "tre" to "3", "fyra" to "4", "fem" to "5",
    "sex" to "6", "sju" to "7", "atta" to "8", "nio" to "9", "tio" to "10",
  )

  /** Words meaning "part", so "Part 2" and "Del 2" agree. */
  private val PART_WORDS = mapOf("del" to "part", "kapitel" to "chapter", "volym" to "vol")

  /** Only after one of these, or at the very end, is a number word actually sequel numbering. */
  private val NUMBERED_AFTER = setOf("part", "chapter", "vol", "volume", "episode", "book")

  /** Minimum similarity for the fuzzy tier. Tuned so "Alien" does not reach "Aliens". */
  private const val FUZZY_THRESHOLD = 0.90

  /**
   * Find the watchlist entry that [candidate] refers to, or null.
   *
   * Tiers, strongest first:
   *  1. IMDb id — exact, whenever both sides carry one.
   *  2. Normalised title equality, across every title/original-title combination.
   *  3. Levenshtein similarity above [FUZZY_THRESHOLD], but only when the years agree.
   */
  fun findMatch(candidate: MatchCandidate, watchlist: List<WatchlistItem>): WatchlistItem? {
    if (watchlist.isEmpty()) return null

    // 1. IMDb id.
    candidate.imdbId?.let { id ->
      watchlist.firstOrNull { it.imdbId != null && it.imdbId.equals(id, ignoreCase = true) }
        ?.let { return it }
    }

    val candidateTitles = candidate.titles().map(::normalize).filter { it.isNotEmpty() }
    if (candidateTitles.isEmpty()) return null

    // 2. Exact normalised title, with the year as a tie-breaker rather than a requirement:
    // a cinema listing sometimes omits the year, and a re-release carries the original one.
    val exact = watchlist.filter { item ->
      item.titles().map(::normalize).any { it in candidateTitles }
    }
    if (exact.isNotEmpty()) {
      return exact.firstOrNull { yearsAgree(candidate.year, it.year) } ?: exact.first()
    }

    // 3. Fuzzy, year-gated. Without a year on both sides we do not guess.
    if (candidate.year == null) return null
    return watchlist
      .filter { it.year != null && yearsAgree(candidate.year, it.year) }
      .firstOrNull { item ->
        item.titles().map(::normalize).any { watchTitle ->
          candidateTitles.any { similarity(it, watchTitle) >= FUZZY_THRESHOLD }
        }
      }
  }

  /** Re-releases and festival runs drift by a year between databases, so allow ±1. */
  private fun yearsAgree(a: Int?, b: Int?): Boolean =
    a != null && b != null && kotlin.math.abs(a - b) <= 1

  /**
   * Fold a title down to its comparable core: lowercase, accent-stripped, punctuation-free,
   * leading article dropped, roman numerals digitised.
   *
   * Swedish å/ä/ö are folded to a/a/o so "Pojken och hägern" survives an ASCII-only listing.
   */
  fun normalize(raw: String): String {
    val folded = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
      .replace(Regex("\\p{Mn}+"), "")
      .replace('ø', 'o')
      .replace('æ', 'a')
      .replace('ß', 's')
    val rawWords = folded
      .replace(Regex("[^a-z0-9]+"), " ")
      .trim()
      .split(' ')
      .filter { it.isNotEmpty() }
      .map { PART_WORDS[it] ?: it }

    // Fold number words only where they are plainly sequel numbering — after "part"/"chapter",
    // or as the last word of a longer title. Otherwise "Sex and the City" becomes "6 and the
    // city" and "Ett hål i mitt hjärta" loses its article.
    val words = rawWords.mapIndexed { index, word ->
      val isNumbering =
        (index > 0 && rawWords[index - 1] in NUMBERED_AFTER) ||
          (index == rawWords.lastIndex && rawWords.size > 1)
      if (isNumbering) NUMERALS[word] ?: word else word
    }
    val withoutArticle =
      if (words.size > 1 && words.first() in LEADING_ARTICLES) words.drop(1) else words
    return withoutArticle.joinToString(" ")
  }

  /**
   * Like [normalize] but keeps leading articles and number words as written.
   *
   * Use this when deciding whether two strings name the *same work*, rather than whether a
   * cinema listing refers to a watchlisted film. The looser rules are right for matching a
   * listing — a cinema may drop "The" — but wrong for identity: article-stripping makes
   * "The Sopranos" collide with the unrelated film "Sopranos".
   */
  fun normalizeStrict(raw: String): String =
    Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
      .replace(Regex("""\p{Mn}+"""), "")
      .replace('ø', 'o')
      .replace('æ', 'a')
      .replace('ß', 's')
      .replace(Regex("[^a-z0-9]+"), " ")
      .trim()

  /** Levenshtein distance expressed as a 0..1 similarity ratio. */
  fun similarity(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val longest = maxOf(a.length, b.length)
    return (longest - levenshtein(a, b)).toDouble() / longest
  }

  private fun levenshtein(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
      current[0] = i
      for (j in 1..b.length) {
        val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
        current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
      }
      val swap = previous
      previous = current
      current = swap
    }
    return previous[b.length]
  }

  private fun WatchlistItem.titles(): List<String> = listOfNotNull(title, originalTitle)
}

/** The film side of a match: whatever a cinema listing tells us about what is playing. */
data class MatchCandidate(
  val title: String,
  val originalTitle: String? = null,
  val year: Int? = null,
  val imdbId: String? = null,
) {
  fun titles(): List<String> = listOfNotNull(title, originalTitle)
}
