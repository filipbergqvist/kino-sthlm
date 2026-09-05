package se.kinosthlm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.kinosthlm.app.data.watchlist.PastedTitleList

/**
 * The paste box exists for lists kept somewhere with no export at all, which in practice means
 * lists written by hand — so it has to cope with how people actually write them rather than
 * demanding a tidy one-title-per-line file.
 */
class PastedTitleListTest {

  @Test
  fun `reads one title per line`() {
    val entries = PastedTitleList.parse("Stalker\nThe Third Man\nSolaris")

    assertEquals(listOf("Stalker", "The Third Man", "Solaris"), entries.map { it.title })
    assertTrue("No years were given, so none should be invented", entries.all { it.year == null })
  }

  @Test
  fun `keeps a trailing year, bracketed or not`() {
    val entries = PastedTitleList.parse("Amadeus 1984\nNosferatu (2024)")

    assertEquals(listOf("Amadeus", "Nosferatu"), entries.map { it.title })
    assertEquals(listOf(1984, 2024), entries.map { it.year })
  }

  @Test
  fun `strips the decoration people put on hand-written lists`() {
    val entries = PastedTitleList.parse("1. Stalker\n2) Solaris\n- Mirror\n* Ivan's Childhood\n• Nostalghia")

    assertEquals(
      listOf("Stalker", "Solaris", "Mirror", "Ivan's Childhood", "Nostalghia"),
      entries.map { it.title },
    )
  }

  @Test
  fun `ignores blank lines and trailing commas`() {
    val entries = PastedTitleList.parse("Stalker,\n\n   \nSolaris,\n")

    assertEquals(listOf("Stalker", "Solaris"), entries.map { it.title })
  }

  @Test
  fun `the same film twice is one film`() {
    val entries = PastedTitleList.parse("Stalker\nstalker\nSTALKER")

    assertEquals(1, entries.size)
  }

  @Test
  fun `the same title in two different years is two films`() {
    // A remake is exactly the case where a year is the whole point of typing one.
    val entries = PastedTitleList.parse("Nosferatu 1922\nNosferatu 2024")

    assertEquals(2, entries.size)
  }

  @Test
  fun `a number that cannot be a release year stays part of the title`() {
    val entries = PastedTitleList.parse("Blade Runner 2049")

    assertEquals("Blade Runner 2049", entries.single().title)
    assertNull(entries.single().year)
  }

  @Test
  fun `an empty paste yields nothing rather than a blank entry`() {
    assertTrue(PastedTitleList.parse("").isEmpty())
    assertTrue(PastedTitleList.parse("   \n\n  ").isEmpty())
  }
}
