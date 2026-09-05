package se.kinosthlm.app

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.kinosthlm.app.data.match.ScreeningMatcher
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.source.RawScreening

/**
 * "Standardize the key to TMDB id... when we scrub cinema schedules, we try to determine what
 * TMDB IDs they are showing, and that's how we link the watchlist and cinema screenings" — this
 * is that link. It never touches the network itself; the resolved TMDB id per screening is
 * handed in, exactly as the repository would after its own lookup pass.
 */
class ScreeningMatcherTest {

  private fun screening(
    title: String,
    year: Int? = null,
    originalTitle: String? = null,
    imdbId: String? = null,
    cinemaId: String = "bio_test",
  ) =
    RawScreening(
      cinemaId = cinemaId,
      cinemaName = "Test Cinema",
      title = title,
      originalTitle = originalTitle,
      year = year,
      imdbId = imdbId,
      startTime = Instant.parse("2026-09-05T18:00:00Z"),
      bookingUrl = "https://example.test/book",
    )

  private fun film(title: String, year: Int? = null, tmdbId: Int? = null, imdbId: String? = null) =
    WatchlistItem(
      id = WatchlistItem.idFor(tmdbId, imdbId, title, year),
      title = title,
      year = year,
      tmdbId = tmdbId,
      imdbId = imdbId,
    )

  @Test
  fun `matches by tmdb id even when the titles differ entirely`() {
    // A cinema listing an alternate or regional title: text matching alone would miss this.
    val item = film("The Phantom Carriage", 1921, tmdbId = 1)
    val show = screening("Körkarlen", 1921)

    val matches = ScreeningMatcher.match(listOf(show), listOf(item)) { 1 }

    assertEquals(1, matches.size)
    assertTrue(matches.single().matchedByTmdbId)
    assertEquals(item, matches.single().item)
  }

  @Test
  fun `falls back to title matching when tmdb id could not be resolved`() {
    val item = film("Metropolis", 1927, tmdbId = 19)
    val show = screening("Metropolis", 1927)

    // Simulates an unconfigured or failed lookup: no id for any screening.
    val matches = ScreeningMatcher.match(listOf(show), listOf(item)) { null }

    assertEquals(1, matches.size)
    assertTrue(!matches.single().matchedByTmdbId)
  }

  @Test
  fun `falls back to title matching when the watchlist entry has no tmdb id yet`() {
    // A Google TV import between syncs: identified by title, not yet given a TMDB id.
    val item = film("Metropolis", 1927, tmdbId = null)
    val show = screening("Metropolis", 1927)

    val matches = ScreeningMatcher.match(listOf(show), listOf(item)) { 19 }

    assertEquals(1, matches.size)
    assertTrue(!matches.single().matchedByTmdbId)
  }

  @Test
  fun `rejects a same-titled screening tmdb explicitly says is a different film`() {
    // Two Nosferatus. The watchlist wants the 1922 one; the cinema is showing the 2024 one.
    // Naive title matching (year-gated but imperfect) must not be allowed to override what TMDB
    // has already determined with certainty.
    val wanted = film("Nosferatu", 1922, tmdbId = 653)
    val onScreen = screening("Nosferatu", 1922) // mislabelled year on the cinema's own page

    val matches = ScreeningMatcher.match(listOf(onScreen), listOf(wanted)) { 999 }

    assertTrue("a confirmed TMDB mismatch must not fall back to a text match", matches.isEmpty())
  }

  @Test
  fun `does not reject when the watchlist entry itself has no id to disagree with`() {
    // The negative-match guard only fires when there is an actual disagreement to detect.
    val item = film("Metropolis", 1927, tmdbId = null)
    val show = screening("Metropolis", 1927)

    val matches = ScreeningMatcher.match(listOf(show), listOf(item)) { 19 }

    assertEquals(1, matches.size)
  }

  @Test
  fun `prefers the tmdb id match over a coincidentally-titled different watchlist entry`() {
    val correct = film("Nosferatu", 1922, tmdbId = 653)
    val decoy = film("Nosferatu", 1922, tmdbId = 1)
    val show = screening("Nosferatu", 1922)

    val matches = ScreeningMatcher.match(listOf(show), listOf(decoy, correct)) { 653 }

    assertEquals(correct, matches.single().item)
  }

  @Test
  fun `returns nothing for a screening matching neither by id nor by title`() {
    val item = film("Metropolis", 1927, tmdbId = 19)
    val show = screening("A Completely Different Film", 1927)

    val matches = ScreeningMatcher.match(listOf(show), listOf(item)) { null }

    assertTrue(matches.isEmpty())
  }

  @Test
  fun `matches every screening of the same film across several venues`() {
    val item = film("Metropolis", 1927, tmdbId = 19)
    val shows =
      listOf(
        screening("Metropolis", 1927, cinemaId = "bio_a"),
        screening("Metropolis", 1927, cinemaId = "bio_b"),
        screening("Metropolis", 1927, cinemaId = "bio_c"),
      )

    val matches = ScreeningMatcher.match(shows, listOf(item)) { 19 }

    assertEquals(3, matches.size)
    assertTrue(matches.all { it.item == item && it.matchedByTmdbId })
  }
}
