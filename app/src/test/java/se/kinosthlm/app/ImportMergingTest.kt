package se.kinosthlm.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.kinosthlm.app.data.local.AppDatabase
import se.kinosthlm.app.data.local.ScreeningDao
import se.kinosthlm.app.data.local.WatchlistDao
import se.kinosthlm.app.data.model.Screening
import se.kinosthlm.app.data.model.WatchlistItem

/**
 * What happens when one film arrives from two different lists.
 *
 * Trakt hands back a TMDB id, so its entries are keyed on it immediately. A Google TV export
 * hands back a bare name, so those are keyed on the name until something identifies them. Import
 * both and, for the window between the import and identification, the same film is two rows with
 * one source badge each — which is how two Terminator 2s ended up in the list.
 *
 * The merge itself is [WatchlistDao.reIdentify]. These pin that it unions the provenance rather
 * than picking a winner, and that nothing the user set by hand is lost in the process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportMergingTest {

  private lateinit var db: AppDatabase
  private lateinit var dao: WatchlistDao
  private lateinit var screenings: ScreeningDao

  @Before
  fun setUp() {
    db =
      Room.inMemoryDatabaseBuilder(
          ApplicationProvider.getApplicationContext(),
          AppDatabase::class.java,
        )
        .allowMainThreadQueries()
        .build()
    dao = db.watchlistDao()
    screenings = db.screeningDao()
  }

  @After
  fun tearDown() {
    db.close()
  }

  /** As Trakt delivers it: already on the standardized TMDB key. */
  private fun fromTrakt(tmdbId: Int, title: String, year: Int?) =
    WatchlistItem(
      id = "tmdb:$tmdbId",
      title = title,
      year = year,
      tmdbId = tmdbId,
      titleType = WatchlistItem.TYPE_MOVIE,
    )

  /** As a Google TV export delivers it: a name and nothing else. */
  private fun fromGoogleTv(title: String) =
    WatchlistItem(id = WatchlistItem.idFor(null, null, title, null), title = title)

  @Test
  fun `identifying a bare title merges it into the film already there`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(fromTrakt(280, "Terminator 2", 1991)))
    dao.replaceSource(WatchlistItem.SOURCE_GOOGLE_TV, listOf(fromGoogleTv("Terminator 2")))
    // Two rows for one film, which is the state the user was seeing.
    assertEquals(2, dao.getAll().size)

    dao.reIdentify(
      oldId = WatchlistItem.idFor(null, null, "Terminator 2", null),
      updated = fromTrakt(280, "Terminator 2", 1991),
    )

    assertEquals(1, dao.getAll().size)
  }

  @Test
  fun `the merged film keeps both source badges`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(fromTrakt(280, "Terminator 2", 1991)))
    dao.replaceSource(WatchlistItem.SOURCE_GOOGLE_TV, listOf(fromGoogleTv("Terminator 2")))

    dao.reIdentify(
      oldId = WatchlistItem.idFor(null, null, "Terminator 2", null),
      updated = fromTrakt(280, "Terminator 2", 1991),
    )

    val sources = dao.sourcesFor("tmdb:280").map { it.sourceId }.toSet()
    assertEquals(setOf(WatchlistItem.SOURCE_TRAKT, WatchlistItem.SOURCE_GOOGLE_TV), sources)
  }

  @Test
  fun `dropping the film from one list leaves the merged entry alive`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(fromTrakt(280, "Terminator 2", 1991)))
    dao.replaceSource(WatchlistItem.SOURCE_GOOGLE_TV, listOf(fromGoogleTv("Terminator 2")))
    dao.reIdentify(
      oldId = WatchlistItem.idFor(null, null, "Terminator 2", null),
      updated = fromTrakt(280, "Terminator 2", 1991),
    )

    // Removed from Trakt, still on the Google TV list.
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, emptyList())

    assertEquals(listOf("tmdb:280"), dao.getAll().map { it.id })
  }

  @Test
  fun `a merge does not undo per-film settings`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(fromTrakt(280, "Terminator 2", 1991)))
    dao.setMuted("tmdb:280", true)
    dao.setRequiredVenueTag("tmdb:280", "IMAX")
    dao.replaceSource(WatchlistItem.SOURCE_GOOGLE_TV, listOf(fromGoogleTv("Terminator 2")))

    dao.reIdentify(
      oldId = WatchlistItem.idFor(null, null, "Terminator 2", null),
      updated = fromTrakt(280, "Terminator 2", 1991),
    )

    val merged = dao.getById("tmdb:280")!!
    assertTrue("Mute was lost in the merge", merged.notificationsMuted)
    assertEquals("IMAX", merged.requiredVenueTag)
  }

  @Test
  fun `a film the user deleted stays deleted when the other list identifies it`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(fromTrakt(280, "Terminator 2", 1991)))
    dao.removeByUser("tmdb:280")
    dao.replaceSource(WatchlistItem.SOURCE_GOOGLE_TV, listOf(fromGoogleTv("Terminator 2")))

    dao.reIdentify(
      oldId = WatchlistItem.idFor(null, null, "Terminator 2", null),
      updated = fromTrakt(280, "Terminator 2", 1991),
    )

    assertTrue("A merge must not resurrect something deleted", dao.getById("tmdb:280")!!.suppressed)
  }

  // --- Overruling a series verdict ---

  @Test
  fun `keeping a title as a film clears both the series verdict and the review flag`() = runTest {
    dao.insertAll(
      listOf(
        WatchlistItem(
          id = "title:andor",
          title = "Andor",
          titleType = WatchlistItem.TYPE_SERIES,
          needsReview = true,
        )
      )
    )

    dao.keepAsFilm("title:andor")

    val item = dao.getById("title:andor")!!
    assertEquals(WatchlistItem.TYPE_MOVIE, item.titleType)
    assertFalse(item.needsReview)
    assertTrue("It should be matched against listings again", item.isMatchable)
  }

  // --- Unfollowing a cinema ---

  private fun screening(id: String, cinemaId: String) =
    Screening(
      id = id,
      watchlistMovieId = "tmdb:280",
      movieTitle = "Terminator 2",
      cinemaId = cinemaId,
      cinemaName = cinemaId,
      screeningTime = System.currentTimeMillis() + 86_400_000,
      bookingUrl = "https://example.test/$id",
    )

  @Test
  fun `unfollowing a cinema forgets the showings found there, and only those`() = runTest {
    screenings.insertAll(
      listOf(
        screening("a", "bio_rio"),
        screening("b", "bio_rio"),
        screening("c", "filmstaden_sergel"),
      )
    )

    screenings.deleteForCinema("bio_rio")

    // A disabled cinema is never polled again, so the usual "did this sync see it?" pruning can
    // never reach it — without this its showings would sit under "Showing soon" until the date
    // passed, which looked exactly like the toggle not working.
    val left = db.screeningDao().observeUpcoming(0L).first()
    assertEquals(listOf("c"), left.map { it.id })
  }
}
