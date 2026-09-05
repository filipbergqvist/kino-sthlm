package se.kinosthlm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.kinosthlm.app.data.match.MatchCandidate
import se.kinosthlm.app.data.match.TitleMatcher
import se.kinosthlm.app.data.model.WatchlistItem

/**
 * Matching is where a bug is expensive: a false positive sends someone to the wrong film.
 * These cases are the ones the old hardcoded alias list got wrong.
 *
 * Real films used here are public domain; the rest are invented, because the matcher only ever
 * sees strings and inventing them keeps the intent of each case obvious.
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
    val watchlist = listOf(film("Körkarlen", 1921, "tt0012364"))
    val match =
      TitleMatcher.findMatch(
        MatchCandidate(title = "The Phantom Carriage", year = 1921, imdbId = "tt0012364"),
        watchlist,
      )
    assertEquals("Körkarlen", match?.title)
  }

  @Test
  fun `matches a swedish original title against an english listing`() {
    // A cinema may list either; the watchlist may hold either.
    val watchlist = listOf(film("The Phantom Carriage", 1921, imdbId = null))
    val match =
      TitleMatcher.findMatch(
        MatchCandidate(title = "Körkarlen", originalTitle = "The Phantom Carriage", year = 1921),
        watchlist,
      )
    assertEquals("The Phantom Carriage", match?.title)
  }

  @Test
  fun `matches ignoring case punctuation and leading article`() {
    val watchlist = listOf(film("The Cabinet of Dr. Caligari", 1920))
    val match =
      TitleMatcher.findMatch(
        MatchCandidate(title = "cabinet of dr caligari", year = 1920),
        watchlist,
      )
    assertEquals("The Cabinet of Dr. Caligari", match?.title)
  }

  @Test
  fun `folds swedish characters so ascii listings still match`() {
    val watchlist = listOf(film("Gösta Berlings saga", 1924))
    val match = TitleMatcher.findMatch(MatchCandidate(title = "Gosta Berlings saga"), watchlist)
    assertEquals("Gösta Berlings saga", match?.title)
  }

  @Test
  fun `treats roman numerals as digits`() {
    val watchlist = listOf(film("Nightfall: Part Two", 1926))
    val match =
      TitleMatcher.findMatch(MatchCandidate(title = "Nightfall Part II", year = 1926), watchlist)
    assertEquals("Nightfall: Part Two", match?.title)
  }

  @Test
  fun `folds spelled-out sequel numbers across languages`() {
    val watchlist = listOf(film("Nightfall: Part Two", 1926))
    val match =
      TitleMatcher.findMatch(MatchCandidate(title = "Nightfall: Del 2", year = 1926), watchlist)
    assertEquals("Nightfall: Part Two", match?.title)
  }

  @Test
  fun `does not match a different film that merely contains the title`() {
    // The old substring rule matched "Harbour" against "Harbour: Nightfall" in both directions.
    val watchlist = listOf(film("Harbour", 1921))
    assertNull(
      TitleMatcher.findMatch(MatchCandidate(title = "Harbour: Nightfall", year = 2024), watchlist)
    )
  }

  @Test
  fun `does not confuse a plural sequel with its original`() {
    val watchlist = listOf(film("Harbours", 1986))
    assertNull(TitleMatcher.findMatch(MatchCandidate(title = "Harbour", year = 1979), watchlist))
  }

  @Test
  fun `does not fuzzy match without a year on both sides`() {
    val watchlist = listOf(film("Nosferatu", 1922))
    assertNull(TitleMatcher.findMatch(MatchCandidate(title = "Nosferatu in Venice"), watchlist))
  }

  @Test
  fun `prefers the entry whose year agrees when titles collide`() {
    val watchlist = listOf(film("Nosferatu", 1922), film("Nosferatu", 2024))
    val match = TitleMatcher.findMatch(MatchCandidate(title = "Nosferatu", year = 2024), watchlist)
    assertEquals(2024, match?.year)
  }

  @Test
  fun `allows a one year drift between databases`() {
    val watchlist = listOf(film("Metropolis", 1927))
    val match = TitleMatcher.findMatch(MatchCandidate(title = "Metropolis", year = 1926), watchlist)
    assertEquals("Metropolis", match?.title)
  }

  @Test
  fun `returns null for an empty watchlist`() {
    assertNull(TitleMatcher.findMatch(MatchCandidate(title = "Anything", year = 1925), emptyList()))
  }

  @Test
  fun `does not treat an ordinary word as a number`() {
    // "sex" is the Swedish six; folding it everywhere would mangle real titles.
    assertEquals("sex and the city", TitleMatcher.normalize("Sex and the City"))
    assertEquals("shame", TitleMatcher.normalize("Shame"))
  }

  @Test
  fun `normalize strips articles punctuation and case`() {
    assertEquals("general", TitleMatcher.normalize("The General"))
    assertEquals("2001 a space odyssey", TitleMatcher.normalize("2001: A Space Odyssey"))
    assertEquals("haxan", TitleMatcher.normalize("HÄXAN"))
  }
}
