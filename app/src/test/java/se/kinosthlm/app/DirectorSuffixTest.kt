package se.kinosthlm.app

import org.junit.Assert.assertEquals
import org.junit.Test
import se.kinosthlm.app.data.source.ProgrammeStrands

/**
 * Cinemateket writes every listing as "Title, Director". TMDB has never heard of a film called
 * "25th Hour, Spike Lee", so the credit has to come off — but carefully: plenty of real titles
 * end in a comma clause, and truncating one of those is worse than leaving a credit on.
 */
class DirectorSuffixTest {

  private fun strip(title: String) = ProgrammeStrands.stripTrailingDirector(title)

  @Test
  fun `drops a director credit`() {
    assertEquals("25th Hour", strip("25th Hour, Spike Lee"))
    assertEquals("As Tears Go By", strip("As Tears Go By, Wong Kar-wai"))
    assertEquals("Cirkeln", strip("Cirkeln, Jafar Panahi"))
    assertEquals("Den glädjelösa gatan", strip("Den glädjelösa gatan, G. W. Pabst"))
  }

  @Test
  fun `leaves a comma clause that is part of the title`() {
    assertEquals("Salò, eller Sodoms 120 dagar", strip("Salò, eller Sodoms 120 dagar"))
    assertEquals("Vem är rädd, Virginia Woolf?", strip("Vem är rädd, Virginia Woolf?"))
  }

  @Test
  fun `leaves anything it cannot read confidently`() {
    // A trailing comma, and a credit with an abbreviation in it: both ambiguous, both left alone.
    assertEquals("Asfalt, Joe May,", strip("Asfalt, Joe May,"))
    assertEquals(
      "Dom kallar oss amatörer, Troell, Windfeldt m.fl.",
      strip("Dom kallar oss amatörer, Troell, Windfeldt m.fl."),
    )
  }
}
