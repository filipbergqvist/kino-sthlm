package se.kinosthlm.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import se.kinosthlm.app.data.model.Cinema
import se.kinosthlm.app.data.model.NotificationLog
import se.kinosthlm.app.data.model.Screening
import se.kinosthlm.app.data.model.TitleCandidate
import se.kinosthlm.app.data.model.WatchlistItem
import se.kinosthlm.app.data.model.WatchlistSource
import se.kinosthlm.app.data.source.BioRioSource
import se.kinosthlm.app.data.source.CapitolSource
import se.kinosthlm.app.data.source.FilmstadenSource
import se.kinosthlm.app.data.source.SkandiaSource
import se.kinosthlm.app.data.source.TellusSource

@Database(
  entities = [
    WatchlistItem::class,
    Cinema::class,
    Screening::class,
    NotificationLog::class,
    TitleCandidate::class,
    WatchlistSource::class,
  ],
  version = 4,
  exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun watchlistDao(): WatchlistDao

  abstract fun screeningDao(): ScreeningDao

  abstract fun cinemaDao(): CinemaDao

  abstract fun notificationDao(): NotificationDao

  abstract fun titleCandidateDao(): TitleCandidateDao

  companion object {
    @Volatile private var INSTANCE: AppDatabase? = null

    fun getDatabase(context: Context): AppDatabase =
      INSTANCE
        ?: synchronized(this) {
          INSTANCE
            ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "kinosthlm.db",
              )
              // Pre-1.0: the cinema list is reference data we re-seed on every launch, and
              // screenings are re-fetched, so throwing the schema away costs nothing but a
              // watchlist re-import. Replace with real migrations once released.
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
        // --- Filmstaden ---
        ncg("filmstaden_rigoletto", "Filmstaden Rigoletto", "NCG76480", "Norrmalm", "Kungsgatan 16", "Grand old picture palace on Kungsgatan"),
        ncg("filmstaden_sergel", "Filmstaden Sergel", "NCG27927", "Norrmalm", "Hötorget 2", "Central multiplex at Hötorget"),
        ncg("filmstaden_scandinavia", "Filmstaden Scandinavia", "NCG41487", "Solna", "Mall of Scandinavia, Stjärntorget 2", "IMAX and the largest screens in Stockholm"),
        ncg("filmstaden_heron", "Filmstaden Heron City", "NCG16299", "Kungens kurva", "Heron City, Dialoggatan 3", "Large suburban multiplex"),
        ncg("filmstaden_kista", "Filmstaden Kista", "NCG48048", "Kista", "Kista Galleria", "Multiplex in Kista Galleria"),
        ncg("filmstaden_sickla", "Filmstaden Sickla", "NCG66921", "Nacka", "Sickla Köpkvarter", "Multiplex in Sickla"),
        ncg("filmstaden_vallingby", "Filmstaden Vällingby", "NCG78594", "Vällingby", "Vällingby Centrum", "Neighbourhood multiplex"),
        ncg("filmstaden_rasunda", "Filmstaden Råsunda", "NCG95905", "Solna", "Råsundavägen 150", "Classic Solna cinema"),
        ncg("filmstaden_taby", "Filmstaden Täby", "NCG84379", "Täby", "Täby Centrum", "Multiplex in Täby Centrum"),
        // --- Other venues on the same booking platform ---
        ncg("grand_stockholm", "Grand", "NCG49012", "Östermalm", "Sveavägen 45", "Arthouse and premieres on Sveavägen"),
        ncg("victoria_stockholm", "Victoria", "NCG74195", "Södermalm", "Götgatan 67", "Södermalm's big neighbourhood cinema"),
        ncg("sture", "Sture", "NCG58657", "Östermalm", "Birger Jarlsgatan 41", "Arthouse, documentaries and festivals"),
        ncg("saga", "Saga", "NCG50537", "Norrmalm", "Kungsgatan 24", "Historic Kungsgatan cinema"),
        ncg("grand_lidingo", "Grand Lidingö", "NCG23107", "Lidingö", "Stockholmsvägen 62", "Lidingö's local cinema"),
        // --- Independents, one adapter each ---
        Cinema(
          id = SkandiaSource.SOURCE_ID,
          name = "Bio Skandia",
          district = "Norrmalm",
          address = "Drottninggatan 82",
          websiteUrl = "https://bioskandia.se",
          sourceId = SkandiaSource.SOURCE_ID,
          specialty = "Gunnar Asplund's 1923 auditorium; repertory, premieres and festivals",
        ),
        Cinema(
          id = CapitolSource.SOURCE_ID,
          name = "Bio Capitol",
          district = "Vasastan",
          address = "Sankt Eriksgatan 82",
          websiteUrl = "https://www.capitolbio.se",
          sourceId = CapitolSource.SOURCE_ID,
          specialty = "Bistro cinema; 35mm, classics and dine-in screenings",
        ),
        Cinema(
          id = BioRioSource.SOURCE_ID,
          name = "Bio Rio",
          district = "Hornstull",
          address = "Hornstulls strand 3",
          websiteUrl = "https://www.biorio.se",
          sourceId = BioRioSource.SOURCE_ID,
          specialty = "Independent Hornstull cinema; arthouse, docs and retrospectives",
        ),
        Cinema(
          id = TellusSource.SOURCE_ID,
          name = "Biocafé Tellus",
          district = "Midsommarkransen",
          address = "Vattenledningsvägen 46",
          websiteUrl = "https://tellusbio.nu",
          sourceId = TellusSource.SOURCE_ID,
          specialty = "Non-profit cinema café running since 1921",
        ),
      )

    private fun ncg(
      id: String,
      name: String,
      remoteId: String,
      district: String,
      address: String,
      specialty: String,
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
      )
  }
}
