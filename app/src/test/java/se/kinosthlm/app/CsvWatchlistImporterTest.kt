package se.kinosthlm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.watchlist.CsvWatchlistImporter

class CsvWatchlistImporterTest {

  private fun parse(csv: String, source: String = WatchlistItem.SOURCE_IMDB) =
    CsvWatchlistImporter.parse(csv.byteInputStream(), source)

  @Test
  fun `reads an imdb watchlist export`() {
    val csv =
      """
      Position,Const,Created,Modified,Description,Title,Original Title,URL,Title Type,IMDb Rating,Runtime (mins),Year,Genres,Num Votes,Release Date,Directors
      1,tt15398776,2024-10-01,,,The Substance,The Substance,https://www.imdb.com/title/tt15398776/,Movie,7.4,141,2024,"Drama, Horror",250000,2024-09-20,Coralie Fargeat
      2,tt0062622,2024-10-02,,,2001: A Space Odyssey,2001: A Space Odyssey,https://www.imdb.com/title/tt0062622/,Movie,8.3,149,1968,"Adventure, Sci-Fi",700000,1968-04-02,Stanley Kubrick
      """
        .trimIndent()

    val items = parse(csv)
    assertEquals(2, items.size)
    assertEquals("The Substance", items[0].title)
    assertEquals("tt15398776", items[0].imdbId)
    assertEquals(2024, items[0].year)
    assertEquals("imdb:tt15398776", items[0].id)
  }

  @Test
  fun `handles commas and quotes inside a title`() {
    val csv =
      """
      Const,Title,Year
      tt0000001,"Good Night, and Good Luck.",2005
      tt0000002,"The ""Burning"" Plain",2008
      """
        .trimIndent()

    val items = parse(csv)
    assertEquals("Good Night, and Good Luck.", items[0].title)
    assertEquals("The \"Burning\" Plain", items[1].title)
  }

  @Test
  fun `falls back to the first column when headers are unfamiliar`() {
    // Google Takeout column names vary by locale and export vintage.
    val csv =
      """
      Namn,Typ
      Persona,Film
      Sommaren med Monika,Film
      """
        .trimIndent()

    val items = parse(csv, WatchlistItem.SOURCE_GOOGLE_TV)
    assertEquals(2, items.size)
    assertEquals("Persona", items[0].title)
    assertEquals(WatchlistItem.SOURCE_GOOGLE_TV, items[0].source)
  }

  @Test
  fun `extracts a year from a full release date column`() {
    val csv =
      """
      Title,Release Date
      Anora,2024-10-18
      """
        .trimIndent()
    assertEquals(2024, parse(csv).single().year)
  }

  @Test
  fun `skips blank lines and rows without a title`() {
    val csv =
      """
      Const,Title,Year
      tt0000001,Persona,1966

      tt0000002,,1970
      """
        .trimIndent()
    assertEquals(1, parse(csv).size)
  }

  @Test
  fun `deduplicates repeated entries`() {
    val csv =
      """
      Const,Title,Year
      tt0000001,Persona,1966
      tt0000001,Persona,1966
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
    assertEquals("Hell or High Water", items[0].title)
    assertTrue(items.all { it.source == WatchlistItem.SOURCE_GOOGLE_TV })
    // Takeout's URL column is a useless placeholder, so there are no ids to carry.
    assertTrue(items.all { it.imdbId == null })
  }

  @Test
  fun `lifts a year out of a google tv title`() {
    // Takeout has no year column; it disambiguates inside the title instead.
    val ghostbusters = googleTakeout().single { it.title.startsWith("Ghostbusters") }
    assertEquals("Ghostbusters", ghostbusters.title)
    assertEquals(1984, ghostbusters.year)
  }

  @Test
  fun `keeps numbers that are part of the title`() {
    val items = googleTakeout()
    assertTrue(items.any { it.title == "2001: A Space Odyssey" && it.year == null })
    assertTrue(items.any { it.title == "Blade Runner 2049" && it.year == null })
  }

  @Test
  fun `preserves non-ascii titles`() {
    val titles = googleTakeout().map { it.title }
    assertTrue(titles.contains("Le Samouraï"))
    assertTrue(titles.contains("Tár"))
    assertTrue(titles.contains("SOS – En segelsällskapsresa"))
  }

  @Test
  fun `keeps commas inside quoted google tv titles`() {
    val titles = googleTakeout().map { it.title }
    assertTrue(titles.contains("Lust, Caution"))
    assertTrue(titles.contains("The Good, the Bad and the Ugly"))
  }

  @Test
  fun `strips a utf8 byte order mark from the header`() {
    val csv = "﻿Title,Note,URL\nPersona,,https://www.google.com\n"
    val items = parse(csv, WatchlistItem.SOURCE_GOOGLE_TV)
    assertEquals(1, items.size)
    assertEquals("Persona", items.single().title)
  }

  @Test
  fun `does not mistake a title beginning with tt for an imdb id`() {
    val csv = "Title\nttl\n"
    assertEquals("ttl", parse(csv).single().title)
  }

  @Test
  fun `handles crlf line endings`() {
    val csv = "Const,Title,Year\r\ntt0000001,Persona,1966\r\n"
    val items = parse(csv)
    assertEquals(1, items.size)
    assertEquals("Persona", items[0].title)
    assertTrue(items[0].title.none { it == '\r' })
  }
}
