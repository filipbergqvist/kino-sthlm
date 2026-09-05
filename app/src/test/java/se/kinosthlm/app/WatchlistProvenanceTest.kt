package se.kinosthlm.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import se.kinosthlm.app.data.local.AppDatabase
import se.kinosthlm.app.data.local.WatchlistDao
import se.kinosthlm.app.data.model.WatchlistItem

/**
 * The watchlist's provenance ("Instigator") mechanism against a real, in-memory SQLite database
 * via Robolectric — not mocks, since the whole point is the interaction between
 * [WatchlistDao.replaceSource], manual add/remove and pinning.
 *
 * The rule under test throughout: a film survives as long as *any* source claims it — Trakt,
 * IMDb, Google TV, a manual add, or a pin — and disappears the moment none does. Importing or
 * re-syncing a source must never touch a film claimed only by a *different* source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchlistProvenanceTest {

  private lateinit var db: AppDatabase
  private lateinit var dao: WatchlistDao

  @Before
  fun setUp() {
    db =
      Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    dao = db.watchlistDao()
  }

  @After
  fun tearDown() {
    db.close()
  }

  private fun film(id: String, title: String) = WatchlistItem(id = id, title = title, titleType = WatchlistItem.TYPE_MOVIE)

  // --- Import adds, and a re-sync removes what the source no longer lists ---

  @Test
  fun `a fresh import adds every film from that source`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(film("a", "Metropolis"), film("b", "Nosferatu")))

    val all = dao.getAll().map { it.id }.toSet()
    assertEquals(setOf("a", "b"), all)
  }

  @Test
  fun `re-syncing without a film that source no longer lists removes it`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(film("a", "Metropolis"), film("b", "Nosferatu")))

    // The user removed "Nosferatu" from their Trakt watchlist; the next sync sees only "a".
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(film("a", "Metropolis")))

    val all = dao.getAll().map { it.id }
    assertEquals(listOf("a"), all)
  }

  @Test
  fun `a film on two sources survives being dropped from one`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(film("a", "Metropolis")))
    dao.replaceSource(WatchlistItem.SOURCE_IMDB, listOf(film("a", "Metropolis")))

    // Dropped from Trakt, but IMDb still claims it.
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, emptyList())

    assertEquals(listOf("a"), dao.getAll().map { it.id })
    assertEquals(listOf(WatchlistItem.SOURCE_IMDB), dao.sourcesFor("a").map { it.sourceId })
  }

  @Test
  fun `a film dropped from every source that claimed it is deleted`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(film("a", "Metropolis")))
    dao.replaceSource(WatchlistItem.SOURCE_IMDB, listOf(film("a", "Metropolis")))

    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, emptyList())
    dao.replaceSource(WatchlistItem.SOURCE_IMDB, emptyList())

    assertTrue(dao.getAll().isEmpty())
  }

  @Test
  fun `re-syncing one source never touches a film only a different source claims`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(film("a", "Metropolis")))
    dao.replaceSource(WatchlistItem.SOURCE_GOOGLE_TV, listOf(film("b", "Nosferatu")))

    // Google TV re-imports its own (unchanged) list; Trakt's film must be unaffected.
    dao.replaceSource(WatchlistItem.SOURCE_GOOGLE_TV, listOf(film("b", "Nosferatu")))

    assertEquals(setOf("a", "b"), dao.getAll().map { it.id }.toSet())
  }

  // --- Manual remove/add ---

  @Test
  fun `removing a film the user still has upstream hides it without deleting it`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(film("a", "Metropolis")))

    dao.removeByUser("a")

    // Still in the table (so it does not vanish from provenance bookkeeping)...
    val stored = dao.getAll().single { it.id == "a" }
    assertTrue(stored.suppressed)
    // ...but no longer matchable, which is what the UI and sync actually check.
    assertTrue(!stored.isMatchable)
  }

  @Test
  fun `a suppressed film stays hidden across a re-sync of the same source`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(film("a", "Metropolis")))
    dao.removeByUser("a")

    // The user never removed it from Trakt itself, so the next sync sees it again.
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(film("a", "Metropolis")))

    val stored = dao.getAll().single { it.id == "a" }
    assertTrue("a manual removal must survive an unrelated re-sync", stored.suppressed)
  }

  @Test
  fun `removing a film from every real source deletes it for good`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(film("a", "Metropolis")))
    dao.removeByUser("a")

    // Now the user also removes it from Trakt itself.
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, emptyList())

    assertTrue(dao.getAll().isEmpty())
  }

  @Test
  fun `a manually added film survives an unrelated source sync`() = runTest {
    dao.addManual(film("a", "Metropolis"))

    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(film("b", "Nosferatu")))
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, emptyList())

    assertTrue(dao.getAll().any { it.id == "a" })
  }

  @Test
  fun `re-adding a manually removed film by hand brings it back`() = runTest {
    dao.addManual(film("a", "Metropolis"))
    dao.removeByUser("a")
    assertTrue(dao.getAll().single { it.id == "a" }.suppressed)

    dao.addManual(film("a", "Metropolis"))

    assertTrue(!dao.getAll().single { it.id == "a" }.suppressed)
  }

  // --- Pinning ---

  @Test
  fun `pinning protects a film after its only real source drops it`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(film("a", "Metropolis")))
    dao.setPinned("a", true)

    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, emptyList())

    assertEquals(listOf("a"), dao.getAll().map { it.id })
  }

  @Test
  fun `unpinning a film with no other source deletes it immediately`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(film("a", "Metropolis")))
    dao.setPinned("a", true)
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, emptyList())

    dao.setPinned("a", false)

    assertTrue(dao.getAll().isEmpty())
  }

  @Test
  fun `unpinning a film that a real source still lists keeps it`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(film("a", "Metropolis")))
    dao.setPinned("a", true)

    dao.setPinned("a", false)

    assertEquals(listOf("a"), dao.getAll().map { it.id })
  }

  // --- Re-identification (bare title -> TMDB id) ---

  /**
   * Real callers (see [se.kinosthlm.app.data.repository.KinoRepository.applyResolutions] and
   * [se.kinosthlm.app.data.watchlist.TitleResolver]) always build the re-identified row as a
   * `.copy()` of the original — never a fresh [WatchlistItem] — precisely so fields like
   * [WatchlistItem.suppressed] survive the move. Tests must call [dao].reIdentify the same way,
   * or they exercise a code path nothing in the app actually takes.
   */
  private suspend fun identifyAs(oldId: String, newId: String, tmdbId: Int) {
    val original = dao.getAll().first { it.id == oldId }
    dao.reIdentify(oldId, original.copy(id = newId, tmdbId = tmdbId))
  }

  @Test
  fun `identifying a bare title moves it to the tmdb key and keeps its source`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_GOOGLE_TV, listOf(film("title:metropolis", "Metropolis")))

    identifyAs("title:metropolis", "tmdb:19", tmdbId = 19)

    val all = dao.getAll()
    assertEquals(listOf("tmdb:19"), all.map { it.id })
    assertEquals(listOf(WatchlistItem.SOURCE_GOOGLE_TV), dao.sourcesFor("tmdb:19").map { it.sourceId })
    assertTrue(dao.sourcesFor("title:metropolis").isEmpty())
  }

  @Test
  fun `identifying two lists' same bare title into one tmdb id merges them`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_GOOGLE_TV, listOf(film("title:metropolis", "Metropolis")))
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, listOf(film("tmdb:19", "Metropolis").copy(tmdbId = 19)))

    // Resolving the Google TV row lands it on the same tmdb key Trakt already uses.
    identifyAs("title:metropolis", "tmdb:19", tmdbId = 19)

    assertEquals(listOf("tmdb:19"), dao.getAll().map { it.id })
    assertEquals(
      setOf(WatchlistItem.SOURCE_GOOGLE_TV, WatchlistItem.SOURCE_TRAKT),
      dao.sourcesFor("tmdb:19").map { it.sourceId }.toSet(),
    )

    // Now it takes both sources dropping it to make it disappear.
    dao.replaceSource(WatchlistItem.SOURCE_TRAKT, emptyList())
    assertEquals(listOf("tmdb:19"), dao.getAll().map { it.id })
    dao.replaceSource(WatchlistItem.SOURCE_GOOGLE_TV, emptyList())
    assertTrue(dao.getAll().isEmpty())
  }

  @Test
  fun `identification never undoes a manual suppression`() = runTest {
    dao.replaceSource(WatchlistItem.SOURCE_GOOGLE_TV, listOf(film("title:metropolis", "Metropolis")))
    dao.removeByUser("title:metropolis")

    identifyAs("title:metropolis", "tmdb:19", tmdbId = 19)

    assertNull(dao.getAll().firstOrNull { it.id == "title:metropolis" })
    val moved = dao.getAll().single { it.id == "tmdb:19" }
    assertTrue("suppression must carry over when identification changes the key", moved.suppressed)
  }
}
