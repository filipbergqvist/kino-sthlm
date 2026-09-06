package se.kinosthlm.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.NotificationLog
import se.kinosthlm.app.data.model.Screening
import se.kinosthlm.app.data.model.ScreeningTitleCache
import se.kinosthlm.app.data.model.TitleCandidate
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.model.WatchlistSource
import se.kinosthlm.app.data.source.BioRioSource
import se.kinosthlm.app.data.source.CapitolSource
import se.kinosthlm.app.data.source.CinemateketSource
import se.kinosthlm.app.data.source.FagelBlaSource
import se.kinosthlm.app.data.source.FilmstadenSource
import se.kinosthlm.app.data.source.KaskadSource
import se.kinosthlm.app.data.source.SkandiaSource
import se.kinosthlm.app.data.source.TellusSource
import se.kinosthlm.app.data.source.ZitaSource

@Database(
  entities = [
    WatchlistItem::class,
    Cinema::class,
    Screening::class,
    NotificationLog::class,
    TitleCandidate::class,
    WatchlistSource::class,
    ScreeningTitleCache::class,
  ],
  version = 10,
  exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun watchlistDao(): WatchlistDao

  abstract fun screeningDao(): ScreeningDao

  abstract fun cinemaDao(): CinemaDao

  abstract fun notificationDao(): NotificationDao

  abstract fun titleCandidateDao(): TitleCandidateDao

  abstract fun screeningTitleCacheDao(): ScreeningTitleCacheDao

  companion object {
    @Volatile private var INSTANCE: AppDatabase? = null

    /**
     * Two new columns on the watchlist, and nothing else — so this one is written out properly
     * rather than left to the destructive fallback. A watchlist is now hundreds of films behind a
     * Trakt authorisation and a couple of CSV imports; wiping it to add a boolean is not a fair
     * trade any more.
     */
    private val MIGRATION_8_9 =
      object : Migration(8, 9) {
        override fun migrate(connection: SQLiteConnection) {
          connection.execSQL(
            "ALTER TABLE watchlist_items ADD COLUMN posterChecked INTEGER NOT NULL DEFAULT 0"
          )
          connection.execSQL(
            "ALTER TABLE watchlist_items ADD COLUMN genres TEXT NOT NULL DEFAULT ''"
          )
        }
      }

    /** One diagnostic counter on the cinema list. Reference data, but no reason to wipe for it. */
    private val MIGRATION_9_10 =
      object : Migration(9, 10) {
        override fun migrate(connection: SQLiteConnection) {
          connection.execSQL(
            "ALTER TABLE cinemas ADD COLUMN lastSeenScreeningsCount INTEGER NOT NULL DEFAULT 0"
          )
        }
      }

    fun getDatabase(context: Context): AppDatabase =
      INSTANCE
        ?: synchronized(this) {
          INSTANCE
            ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "kinosthlm.db",
              )
              .addMigrations(MIGRATION_8_9, MIGRATION_9_10)
              // Still the fallback for the older pre-release versions, which have no migration
              // path written for them. The cinema list re-seeds and screenings re-fetch, so the
              // only real cost is a watchlist re-import.
              .fallbackToDestructiveMigration(dropAllTables = true)
              .build()
              .also { INSTANCE = it }
        }

    /**
     * Stockholm venues, seeded on first run and reconciled on every launch so new cinemas in an
     * app update appear without wiping the user's enable/disable choices.
     *
     * The first block all come from one API (see [FilmstadenSource]); `remoteId` is that API's
     * venue id. The independents each have their own adapter and need no remote id.
     */
    val defaultCinemas: List<Cinema> =
      listOf(
        // --- Filmstaden: genuine multiplexes, tagged for the "big screen" preference ---
        ncg("filmstaden_rigoletto", "Filmstaden Rigoletto", "NCG76480", "Norrmalm", "Kungsgatan 16", "Grand old picture palace on Kungsgatan", Cinema.TAG_BIG_SCREEN),
        ncg("filmstaden_sergel", "Filmstaden Sergel", "NCG27927", "Norrmalm", "Hötorget 2", "Central multiplex at Hötorget", Cinema.TAG_BIG_SCREEN),
        ncg("filmstaden_scandinavia", "Filmstaden Scandinavia", "NCG41487", "Solna", "Mall of Scandinavia, Stjärntorget 2", "IMAX and the largest screens in Stockholm", "${Cinema.TAG_BIG_SCREEN},${Cinema.TAG_IMAX}"),
        ncg("filmstaden_heron", "Filmstaden Heron City", "NCG16299", "Kungens kurva", "Heron City, Dialoggatan 3", "Large suburban multiplex", Cinema.TAG_BIG_SCREEN),
        ncg("filmstaden_kista", "Filmstaden Kista", "NCG48048", "Kista", "Kista Galleria", "Multiplex in Kista Galleria", Cinema.TAG_BIG_SCREEN),
        ncg("filmstaden_sickla", "Filmstaden Sickla", "NCG66921", "Nacka", "Sickla Köpkvarter", "Multiplex in Sickla", Cinema.TAG_BIG_SCREEN),
        ncg("filmstaden_vallingby", "Filmstaden Vällingby", "NCG78594", "Vällingby", "Vällingby Centrum", "Neighbourhood multiplex", Cinema.TAG_BIG_SCREEN),
        ncg("filmstaden_rasunda", "Filmstaden Råsunda", "NCG95905", "Solna", "Råsundavägen 150", "Classic Solna cinema", Cinema.TAG_BIG_SCREEN),
        ncg("filmstaden_taby", "Filmstaden Täby", "NCG84379", "Täby", "Täby Centrum", "Multiplex in Täby Centrum", Cinema.TAG_BIG_SCREEN),
        // --- Other venues on the same booking platform, but boutique/arthouse — "cozy" ---
        ncg("grand_stockholm", "Grand", "NCG49012", "Östermalm", "Sveavägen 45", "Arthouse and premieres on Sveavägen", Cinema.TAG_COZY),
        ncg("victoria_stockholm", "Victoria", "NCG74195", "Södermalm", "Götgatan 67", "Södermalm's big neighbourhood cinema", Cinema.TAG_COZY),
        ncg("sture", "Sture", "NCG58657", "Östermalm", "Birger Jarlsgatan 41", "Arthouse, documentaries and festivals", Cinema.TAG_COZY),
        ncg("saga", "Saga", "NCG50537", "Norrmalm", "Kungsgatan 24", "Historic Kungsgatan cinema", Cinema.TAG_COZY),
        ncg("grand_lidingo", "Grand Lidingö", "NCG23107", "Lidingö", "Stockholmsvägen 62", "Lidingö's local cinema", Cinema.TAG_COZY),
        // --- Independents, one adapter each — all boutique, all "cozy" ---
        Cinema(
          id = SkandiaSource.SOURCE_ID,
          name = "Bio Skandia",
          district = "Norrmalm",
          address = "Drottninggatan 82",
          websiteUrl = "https://bioskandia.se",
          sourceId = SkandiaSource.SOURCE_ID,
          specialty = "Gunnar Asplund's 1923 auditorium; repertory, premieres and festivals",
          tags = Cinema.TAG_COZY,
        ),
        Cinema(
          id = CapitolSource.SOURCE_ID,
          name = "Bio Capitol",
          district = "Vasastan",
          address = "Sankt Eriksgatan 82",
          websiteUrl = "https://www.capitolbio.se",
          sourceId = CapitolSource.SOURCE_ID,
          specialty = "Bistro cinema; 35mm, classics and dine-in screenings",
          tags = "${Cinema.TAG_COZY},${Cinema.TAG_FOOD_DRINK}",
        ),
        Cinema(
          id = BioRioSource.SOURCE_ID,
          name = "Bio Rio",
          district = "Hornstull",
          address = "Hornstulls strand 3",
          websiteUrl = "https://www.biorio.se",
          sourceId = BioRioSource.SOURCE_ID,
          specialty = "Independent Hornstull cinema with a full restaurant and bar",
          tags = "${Cinema.TAG_COZY},${Cinema.TAG_FOOD_DRINK}",
        ),
        Cinema(
          id = FagelBlaSource.SOURCE_ID,
          name = "Bio Fågel Blå",
          district = "Södermalm",
          address = "Rökerigatan 19",
          websiteUrl = "https://biofagelbla.se",
          sourceId = FagelBlaSource.SOURCE_ID,
          specialty = "Neighbourhood cinema on Södermalm; arthouse and repertory",
          tags = Cinema.TAG_COZY,
        ),
        Cinema(
          id = KaskadSource.SOURCE_ID,
          name = "Bio Kaskad",
          district = "Blackeberg",
          address = "Blackebergs torg",
          websiteUrl = "https://www.biokaskad.se",
          sourceId = KaskadSource.SOURCE_ID,
          specialty = "Local cinema in Blackeberg",
          tags = Cinema.TAG_COZY,
        ),
        Cinema(
          id = ZitaSource.SOURCE_ID,
          name = "Zita Folkets Bio",
          district = "Östermalm",
          address = "Birger Jarlsgatan 37",
          websiteUrl = "https://zita.se",
          sourceId = ZitaSource.SOURCE_ID,
          specialty = "Arthouse and world cinema, with its own film series",
          tags = "${Cinema.TAG_COZY},${Cinema.TAG_FOOD_DRINK}",
        ),
        // Cinemateket runs two auditoria out of one programme, so they are two venues sharing a
        // source. remoteId is the hall name its booking links use, which is how a showing is
        // routed to the right one.
        Cinema(
          id = "cinemateket_victor",
          name = "Cinemateket — Bio Victor",
          district = "Gärdet",
          address = "Filmhuset, Borgvägen 1",
          websiteUrl = "https://www.filminstitutet.se/cinemateket",
          sourceId = CinemateketSource.SOURCE_ID,
          remoteId = "Victor",
          specialty = "The Swedish Film Institute's repertory programme; the larger auditorium",
          tags = Cinema.TAG_COZY,
        ),
        Cinema(
          id = "cinemateket_mauritz",
          name = "Cinemateket — Bio Mauritz",
          district = "Gärdet",
          address = "Filmhuset, Borgvägen 1",
          websiteUrl = "https://www.filminstitutet.se/cinemateket",
          sourceId = CinemateketSource.SOURCE_ID,
          remoteId = "Mauritz",
          specialty = "The Swedish Film Institute's smaller auditorium",
          tags = Cinema.TAG_COZY,
        ),
        Cinema(
          id = TellusSource.SOURCE_ID,
          name = "Biocafé Tellus",
          district = "Midsommarkransen",
          address = "Vattenledningsvägen 46",
          websiteUrl = "https://tellusbio.nu",
          sourceId = TellusSource.SOURCE_ID,
          specialty = "Non-profit cinema café running since 1921",
          tags = Cinema.TAG_COZY,
        ),
      )

    private fun ncg(
      id: String,
      name: String,
      remoteId: String,
      district: String,
      address: String,
      specialty: String,
      tags: String = "",
    ) =
      Cinema(
        id = id,
        name = name,
        district = district,
        address = address,
        websiteUrl = "https://www.filmstaden.se",
        sourceId = FilmstadenSource.SOURCE_ID,
        remoteId = remoteId,
        specialty = specialty,
        tags = tags,
      )
  }
}
