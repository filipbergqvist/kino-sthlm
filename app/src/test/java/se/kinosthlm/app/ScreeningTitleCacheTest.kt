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
import se.kinosthlm.app.data.local.ScreeningTitleCacheDao
import se.kinosthlm.app.data.model.ScreeningTitleCache

/**
 * The cache that stops every sync re-asking TMDB what every listed film is.
 *
 * Without it, each sync resolved every distinct title on every cinema's schedule from scratch —
 * on the order of a hundred searches, four times a day, per device, for answers that never
 * change. The two expiries matter as much as the caching: a hit is worth keeping for a very long
 * time, a miss only briefly, because a film TMDB cannot place today may simply not be listed yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreeningTitleCacheTest {

  private lateinit var db: AppDatabase
  private lateinit var dao: ScreeningTitleCacheDao

  @Before
  fun setUp() {
    db =
      Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    dao = db.screeningTitleCacheDao()
  }

  @After
  fun tearDown() {
    db.close()
  }

  private val day = 24L * 60 * 60 * 1000

  @Test
  fun `a resolved title is remembered and read back`() = runTest {
    dao.insertAll(listOf(ScreeningTitleCache("Barry Lyndon|1975", tmdbId = 3175)))

    val found = dao.get(listOf("Barry Lyndon|1975")).single()

    assertEquals(3175, found.tmdbId)
  }

  @Test
  fun `only the asked-for titles come back`() = runTest {
    dao.insertAll(
      listOf(
        ScreeningTitleCache("Barry Lyndon|1975", tmdbId = 3175),
        ScreeningTitleCache("Metropolis|1927", tmdbId = 19),
      )
    )

    val found = dao.get(listOf("Metropolis|1927"))

    assertEquals(listOf("Metropolis|1927"), found.map { it.titleKey })
  }

  @Test
  fun `a title TMDB could not place is remembered too, so it is not re-asked every sync`() = runTest {
    dao.insertAll(listOf(ScreeningTitleCache("Some Obscure Short|null", tmdbId = null)))

    val found = dao.get(listOf("Some Obscure Short|null")).single()

    // Present in the cache, with no id — which is exactly the distinction the sync needs: a
    // cached "no answer" must not look the same as "never asked".
    assertNull(found.tmdbId)
  }

  @Test
  fun `re-resolving a title replaces the old answer rather than duplicating it`() = runTest {
    dao.insertAll(listOf(ScreeningTitleCache("Nosferatu|null", tmdbId = null, resolvedAt = 1_000)))
    dao.insertAll(listOf(ScreeningTitleCache("Nosferatu|null", tmdbId = 653, resolvedAt = 2_000)))

    val found = dao.get(listOf("Nosferatu|null"))

    assertEquals(1, found.size)
    assertEquals(653, found.single().tmdbId)
  }

  @Test
  fun `stale misses expire but hits of the same age survive`() = runTest {
    val now = System.currentTimeMillis()
    val tenDaysAgo = now - 10 * day
    dao.insertAll(
      listOf(
        ScreeningTitleCache("Unplaceable|null", tmdbId = null, resolvedAt = tenDaysAgo),
        ScreeningTitleCache("Metropolis|1927", tmdbId = 19, resolvedAt = tenDaysAgo),
      )
    )

    // The sync's weekly miss expiry.
    dao.deleteStaleMisses(now - 7 * day)

    val remaining = dao.get(listOf("Unplaceable|null", "Metropolis|1927"))
    assertEquals(listOf("Metropolis|1927"), remaining.map { it.titleKey })
  }

  @Test
  fun `a recent miss is kept, so one bad week is not re-asked daily`() = runTest {
    val now = System.currentTimeMillis()
    dao.insertAll(listOf(ScreeningTitleCache("Too New|2026", tmdbId = null, resolvedAt = now - day)))

    dao.deleteStaleMisses(now - 7 * day)

    assertEquals(1, dao.get(listOf("Too New|2026")).size)
  }

  @Test
  fun `the long expiry eventually clears even a successful match`() = runTest {
    val now = System.currentTimeMillis()
    dao.insertAll(listOf(ScreeningTitleCache("Metropolis|1927", tmdbId = 19, resolvedAt = now - 400 * day)))

    dao.deleteResolvedBefore(now - 365 * day)

    assertTrue(dao.get(listOf("Metropolis|1927")).isEmpty())
  }
}
