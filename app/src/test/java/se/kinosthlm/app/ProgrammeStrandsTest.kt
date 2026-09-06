package se.kinosthlm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.kinosthlm.app.data.source.ProgrammeStrands

/**
 * Bio Capitol lists almost nothing under a plain title. Amélie was on its schedule as "Afternoon
 * Tea: Amelie från Montmartre", which TMDB has never heard of and no watchlist contains — so a
 * film genuinely playing never reached the app. These pin the shapes that branding takes, and
 * (just as importantly) the ones that only look like branding.
 */
class ProgrammeStrandsTest {

  private fun clean(title: String) = ProgrammeStrands.clean(title).title

  @Test
  fun `strips a strand prefix`() {
    assertEquals("Amelie från Montmartre", clean("Afternoon Tea: Amelie från Montmartre"))
    assertEquals("Breakfast at Tiffany's", clean("Frukostbio: Breakfast at Tiffany's"))
    assertEquals("Duck Soup", clean("Bröderna Marx: Duck Soup"))
    assertEquals("Cabaret", clean("Musikal: Cabaret"))
    assertEquals("Marie Antoinette", clean("Filmfeber: Marie Antoinette"))
    assertEquals("Gosford Park", clean("Seniorbio: Gosford Park"))
  }

  @Test
  fun `handles a strand separated by a full stop`() {
    assertEquals("Stolthet & fördom", clean("Afternoon Tea. Stolthet & fördom 21 år"))
  }

  @Test
  fun `leaves a real title that happens to contain a colon alone`() {
    // The whole reason the prefixes are an allowlist: cutting at the first colon would turn this
    // into "Brand New Day", trading one matching failure for a worse one.
    assertEquals("Spider-Man: Brand New Day", clean("Spider-Man: Brand New Day"))
    assertEquals("Oasis: Don't Look Back In Anger", clean("Oasis: Don't Look Back In Anger - Premiär 11 sep"))
    assertEquals("Practical Magic: Family Legacy", clean("Practical Magic: Family Legacy - Premiär 11 sep"))
  }

  @Test
  fun `strips premiere notes, leading and trailing`() {
    assertEquals("La Grazia", clean("La Grazia - Premiär 11 sep"))
    assertEquals("Bad Apples", clean("Bad Apples – Smygpremiär 18 september"))
    assertEquals("Bad Apples", clean("Smygpremiär: Bad Apples"))
    assertEquals("Autofiktion", clean("Autofiktion Nypremiär"))
  }

  @Test
  fun `strips event notes`() {
    assertEquals("Bad Apples", clean("Bad Apples med regissörsbesök!"))
    assertEquals("Metropolis", clean("Metropolis med Livemusik"))
    assertEquals("Friday the 13th", clean("Friday the 13th - 46 årsjubileum"))
  }

  @Test
  fun `strips a med-strand only for known strands`() {
    assertEquals("Chicago", clean("Dress-Along med Chicago"))
    assertEquals("Mamma mia", clean("Partyalong med Mamma mia"))
    // These are whole titles. A general "cut at ' med '" rule would shred both.
    assertEquals("Bus och mysterier med Alfons Åberg", clean("Bus och mysterier med Alfons Åberg"))
    assertEquals("Frukost med Alzheimer", clean("Frukost med Alzheimer"))
  }

  @Test
  fun `lifts a bracketed year out of the title`() {
    val cleaned = ProgrammeStrands.clean("Daughters of Darkness (1971)")

    assertEquals("Daughters of Darkness", cleaned.title)
    assertEquals(1971, cleaned.year)
  }

  @Test
  fun `keeps a bracketed original title, which is what TMDB indexes`() {
    val cleaned = ProgrammeStrands.clean("Blommor av Stål (Steel Magnolias)")

    assertEquals("Blommor av Stål", cleaned.title)
    assertEquals("Steel Magnolias", cleaned.originalTitle)
    assertNull(cleaned.year)
  }

  @Test
  fun `never reduces a listing to nothing`() {
    // A title that is only branding keeps what it had rather than becoming blank.
    assertEquals("Frukostbio", clean("Frukostbio"))
    assertEquals("Premiär 11 sep", clean("Premiär 11 sep"))
  }
}
