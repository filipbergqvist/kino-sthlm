package se.kinosthlm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.kinosthlm.app.data.match.MatchCandidate
import se.kinosthlm.app.data.match.TitleMatcher
import se.kinosthlm.app.data.model.WatchlistItem

/**
 * Matching is where a bug is expensive: a false positive sends someone to the wrong film.
 * These cases are the ones the previous hardcoded alias list got wrong.
 */
class TitleMatcherTest {

  private fun film(title: String, year: Int? = null, imdbId: String? = null) =
    WatchlistItem(
      id = WatchlistItem.idFor(imdbId, title, year),
      title = title,
      year = year,
      imdbId = imdbId,
    )

  @Test
  fun `matches on imdb id even when titles differ`() {
    val watchlist = listOf(film("Pojken och hägern", 2023, "tt6587046"))
    val match =
      TitleMatcher.findMatch(
        MatchCandidate(title = "The Boy and the Heron", year = 2023, imdbId = "tt6587046"),
        watchlist,
      )
    assertEquals("Pojken och hägern", match?.title)
  }

  @Test
  fun `matches ignoring case punctuation and leading article`() {
    val watchlist = listOf(film("The Zone of Interest", 2023))
    val match =
      TitleMatcher.findMatch(MatchCandidate(title = "zone of interest", year = 2023), watchlist)
    assertEquals("The Zone of Interest", match?.title)
  }

  @Test
  fun `folds swedish characters so ascii listings still match`() {
    val watchlist = listOf(film("Pojken och hägern", 2023))
    val match = TitleMatcher.findMatch(MatchCandidate(title = "Pojken och hagern"), watchlist)
    assertEquals("Pojken och hägern", match?.title)
  }

  @Test
  fun `treats roman numerals as digits`() {
    val watchlist = listOf(film("Dune: Part Two", 2024))
    val match =
      TitleMatcher.findMatch(MatchCandidate(title = "Dune Part II", year = 2024), watchlist)
    assertEquals("Dune: Part Two", match?.title)
  }

  @Test
  fun `does not match a different film that merely contains the title`() {
    // The old substring rule matched "Alien" against "Alien: Romulus" — and vice versa.
    val watchlist = listOf(film("Alien", 1979))
    assertNull(
      TitleMatcher.findMatch(MatchCandidate(title = "Alien: Romulus", year = 2024), watchlist)
    )
  }

  @Test
  fun `does not confuse a sequel with its original`() {
    val watchlist = listOf(film("Aliens", 1986))
    assertNull(TitleMatcher.findMatch(MatchCandidate(title = "Alien", year = 1979), watchlist))
  }

  @Test
  fun `does not fuzzy match without a year on both sides`() {
    val watchlist = listOf(film("Nosferatu", 1922))
    // No year on the listing: the fuzzy tier must not fire, and the titles are not equal.
    assertNull(TitleMatcher.findMatch(MatchCandidate(title = "Nosferatu the Vampyre"), watchlist))
  }

  @Test
  fun `prefers the entry whose year agrees when titles collide`() {
    val watchlist = listOf(film("Nosferatu", 1922), film("Nosferatu", 2024))
    val match = TitleMatcher.findMatch(MatchCandidate(title = "Nosferatu", year = 2024), watchlist)
    assertEquals(2024, match?.year)
  }

  @Test
  fun `allows a one year drift between databases`() {
    val watchlist = listOf(film("Anora", 2024))
    val match = TitleMatcher.findMatch(MatchCandidate(title = "Anora", year = 2025), watchlist)
    assertEquals("Anora", match?.title)
  }

  @Test
  fun `returns null for an empty watchlist`() {
    assertNull(TitleMatcher.findMatch(MatchCandidate(title = "Anything", year = 2024), emptyList()))
  }

  @Test
  fun `folds spelled-out sequel numbers`() {
    val watchlist = listOf(film("Dune: Part Two", 2024))
    val match =
      TitleMatcher.findMatch(MatchCandidate(title = "Dune: Del 2", year = 2024), watchlist)
    assertEquals("Dune: Part Two", match?.title)
  }

  @Test
  fun `does not treat an ordinary word as a number`() {
    // "sex" is the Swedish six; folding it everywhere would mangle real titles.
    assertEquals("sex and the city", TitleMatcher.normalize("Sex and the City"))
    assertEquals("shame", TitleMatcher.normalize("Shame"))
  }

  @Test
  fun `normalize strips articles punctuation and case`() {
    assertEquals("substance", TitleMatcher.normalize("The Substance"))
    assertEquals("2001 a space odyssey", TitleMatcher.normalize("2001: A Space Odyssey"))
    assertEquals("amelie fran montmartre", TitleMatcher.normalize("AMELIE FRÅN MONTMARTRE"))
  }
}
