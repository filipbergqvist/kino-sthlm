package se.kinosthlm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.watchlist.CsvWatchlistImporter

/** Films referenced here are public domain, or invented to exercise a formatting edge case. */
class CsvWatchlistImporterTest {

  private fun parse(csv: String, source: String = WatchlistItem.SOURCE_IMDB) =
    CsvWatchlistImporter.parse(csv.byteInputStream(), source)

  @Test
  fun `reads an imdb watchlist export`() {
    val csv =
      """
      Position,Const,Created,Modified,Description,Title,Original Title,URL,Title Type,IMDb Rating,Runtime (mins),Year,Genres,Num Votes,Release Date,Directors
      1,tt0013442,2026-10-01,,,Nosferatu,Nosferatu,https://www.imdb.com/title/tt0013442/,Movie,7.9,94,1922,"Fantasy, Horror",120000,1922-03-04,F.W. Murnau
      2,tt0017136,2026-10-02,,,Metropolis,Metropolis,https://www.imdb.com/title/tt0017136/,Movie,8.3,153,1927,"Drama, Sci-Fi",180000,1927-01-10,Fritz Lang
      """
        .trimIndent()

    val items = parse(csv)
    assertEquals(2, items.size)
    assertEquals("Nosferatu", items[0].title)
    assertEquals("tt0013442", items[0].imdbId)
    assertEquals(1922, items[0].year)
    assertEquals("imdb:tt0013442", items[0].id)
  }

  @Test
  fun `handles commas and quotes inside a title`() {
    val csv =
      """
      Const,Title,Year
      tt0000001,"Häxan, or Witchcraft Through the Ages",1922
      tt0000002,"The ""Phantom"" Carriage",1921
      """
        .trimIndent()

    val items = parse(csv)
    assertEquals("Häxan, or Witchcraft Through the Ages", items[0].title)
    assertEquals("The \"Phantom\" Carriage", items[1].title)
  }

  @Test
  fun `falls back to the first column when headers are unfamiliar`() {
    // Google Takeout column names vary by locale and export vintage.
    val csv =
      """
      Namn,Typ
      Körkarlen,Film
      Gösta Berlings saga,Film
      """
        .trimIndent()

    val items = parse(csv, WatchlistItem.SOURCE_GOOGLE_TV)
    assertEquals(2, items.size)
    assertEquals("Körkarlen", items[0].title)
  }

  @Test
  fun `extracts a year from a full release date column`() {
    val csv =
      """
      Title,Release Date
      The General,1926-12-31
      """
        .trimIndent()
    assertEquals(1926, parse(csv).single().year)
  }

  @Test
  fun `skips blank lines and rows without a title`() {
    val csv =
      """
      Const,Title,Year
      tt0000001,The General,1926

      tt0000002,,1927
      """
        .trimIndent()
    assertEquals(1, parse(csv).size)
  }

  @Test
  fun `keeps repeated titles apart so the user can disambiguate them`() {
    // Two rows, same title, no year: two different films the user watchlisted. Collapsing them
    // silently loses one, so they survive as separate entries flagged for review.
    val csv =
      """
      Title
      Nosferatu
      Nosferatu
      """
        .trimIndent()
    val items = parse(csv, WatchlistItem.SOURCE_GOOGLE_TV)
    assertEquals(2, items.size)
    assertEquals(2, items.map { it.id }.distinct().size)
    assertTrue(items.all { it.title == "Nosferatu" })
    assertTrue(items.all { it.needsReview })
  }

  @Test
  fun `deduplicates rows that are genuinely identical`() {
    // Same IMDb id twice is one film listed twice, not two films.
    val csv =
      """
      Const,Title,Year
      tt0000001,The General,1926
      tt0000001,The General,1926
      """
        .trimIndent()
    assertEquals(1, parse(csv).size)
  }

  // --- The real Google Takeout shape: Title,Note,URL,Tags,Comment ---

  private fun googleTakeout() =
    CsvWatchlistImporter.parse(
      checkNotNull(javaClass.getResourceAsStream("/fixtures/google_tv_watchlist.csv")),
      WatchlistItem.SOURCE_GOOGLE_TV,
    )

  @Test
  fun `reads a google takeout watchlist export`() {
    val items = googleTakeout()
    // 12 films; the blank second row Takeout always emits is dropped.
    assertEquals(12, items.size)
    assertEquals("The Cabinet of Dr. Caligari", items[0].title)
    // Takeout's URL column is a useless placeholder, so there are no ids to carry.
    assertTrue(items.all { it.imdbId == null })
  }

  @Test
  fun `lifts a year out of a google tv title`() {
    // Takeout has no year column; it disambiguates inside the title instead.
    val nosferatu = googleTakeout().single { it.title == "Nosferatu" }
    assertEquals(1922, nosferatu.year)
  }

  @Test
  fun `keeps numbers that are part of the title`() {
    val items = googleTakeout()
    assertTrue(items.any { it.title == "2001 Nights of Cinema" && it.year == null })
    assertTrue(items.any { it.title == "Metropolis 2026" && it.year == null })
  }

  @Test
  fun `preserves non-ascii titles`() {
    val titles = googleTakeout().map { it.title }
    assertTrue(titles.contains("Körkarlen"))
    assertTrue(titles.contains("Gösta Berlings saga"))
    assertTrue(titles.contains("SOS – Ett drama i tre akter"))
  }

  @Test
  fun `keeps commas inside quoted google tv titles`() {
    val titles = googleTakeout().map { it.title }
    assertTrue(titles.contains("Häxan, or Witchcraft Through the Ages"))
    assertTrue(titles.contains("The Good, the Bold and the Silent"))
  }

  @Test
  fun `strips a utf8 byte order mark from the header`() {
    val csv = "﻿Title,Note,URL\nThe General,,https://www.google.com\n"
    val items = parse(csv, WatchlistItem.SOURCE_GOOGLE_TV)
    assertEquals(1, items.size)
    assertEquals("The General", items.single().title)
  }

  @Test
  fun `does not mistake a title beginning with tt for an imdb id`() {
    val csv = "Title\nttl\n"
    assertEquals("ttl", parse(csv).single().title)
  }

  @Test
  fun `handles crlf line endings`() {
    val csv = "Const,Title,Year\r\ntt0000001,The General,1926\r\n"
    val items = parse(csv)
    assertEquals(1, items.size)
    assertEquals("The General", items[0].title)
    assertTrue(items[0].title.none { it == '\r' })
  }
}
