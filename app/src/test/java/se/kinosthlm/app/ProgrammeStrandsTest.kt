package se.kinosthlm.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
  fun `tells a format note apart from an original title`() {
    // Bio Skandia writes both. The Korean is the film's real title and exactly what TMDB
    // indexes it under; "70MM" is a projector.
    val format = ProgrammeStrands.clean("The Odyssey (70MM)")
    assertEquals("The Odyssey", format.title)
    assertNull(format.originalTitle)
    assertEquals(listOf("70MM"), format.formats)

    val korean = ProgrammeStrands.clean("Parasite (기생충)")
    assertEquals("Parasite", korean.title)
    assertEquals("기생충", korean.originalTitle)
  }

  @Test
  fun `handles a title carrying both`() {
    val cleaned = ProgrammeStrands.clean("Parasite (기생충) (4K)")

    assertEquals("Parasite", cleaned.title)
    assertEquals("기생충", cleaned.originalTitle)
    assertEquals(listOf("4K"), cleaned.formats)
  }

  @Test
  fun `strips festival furniture and tidies spacing`() {
    assertEquals("Hope", clean("Hope  (호프) -  Opening film"))
    assertEquals("Hope", clean("Hope - with panel discussion (호프)"))
    assertEquals("The Mutation", clean("The Mutation  (사랑의 탄생)"))
  }

  @Test
  fun `strips a qualified strand label`() {
    // Venues qualify their strands rather than using them bare, and enumerating every
    // combination is a losing game — so the label is matched on its first word.
    assertEquals(
      "Lillpojkens flykt till väst",
      clean("Dokumentär med regissörsbesök: Lillpojkens flykt till väst"),
    )
    assertEquals("Persona", clean("Cinemateket: Persona"))
  }

  @Test
  fun `drops events that are not screenings`() {
    assertTrue(ProgrammeStrands.isNonFilmEvent("Jazzkroki"))
    assertTrue(ProgrammeStrands.isNonFilmEvent("Torsdagssoppa"))
    assertTrue(ProgrammeStrands.isNonFilmEvent("Guidad visning av Bio Skandia"))
    // Secret cinema has no title to match on by design.
    assertTrue(ProgrammeStrands.isNonFilmEvent("Förstadens filmsalong"))
    assertTrue(ProgrammeStrands.isNonFilmEvent("Secret Cinema"))
    // And these are films, however much they sound like an evening out.
    assertFalse(ProgrammeStrands.isNonFilmEvent("Frukost med Alzheimer"))
    assertFalse(ProgrammeStrands.isNonFilmEvent("La Grazia"))
  }

  @Test
  fun `never reduces a listing to nothing`() {
    // A title that is only branding keeps what it had rather than becoming blank.
    assertEquals("Frukostbio", clean("Frukostbio"))
    assertEquals("Premiär 11 sep", clean("Premiär 11 sep"))
  }
}
