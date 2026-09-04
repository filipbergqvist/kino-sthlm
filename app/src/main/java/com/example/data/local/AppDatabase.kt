package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.Cinema
import com.example.data.model.NotificationLog
import com.example.data.model.Screening
import com.example.data.model.WatchlistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        WatchlistItem::class,
        Cinema::class,
        Screening::class,
        NotificationLog::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun watchlistDao(): WatchlistDao
    abstract fun screeningDao(): ScreeningDao
    abstract fun cinemaDao(): CinemaDao
    abstract fun notificationDao(): NotificationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "stockholm_cinema_db"
                )
                    .addCallback(DatabaseCallback())
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                INSTANCE?.let { database ->
                    CoroutineScope(Dispatchers.IO).launch {
                        populateInitialCinemas(database.cinemaDao())
                    }
                }
            }

            private suspend fun populateInitialCinemas(cinemaDao: CinemaDao) {
                cinemaDao.insertCinemas(defaultStockholmCinemas)
            }
        }

        val defaultStockholmCinemas = listOf(
            Cinema(
                id = "bio_capitol",
                name = "Bio Capitol",
                district = "Vasastan",
                address = "Sankt Eriksplan 82, Stockholm",
                websiteUrl = "https://www.capitolbio.se",
                bookingUrlTemplate = "https://www.capitolbio.se/filmer/",
                specialty = "Bistro cinema, 70mm screenings, classic retrospectives & auteur premieres",
                isEnabled = true
            ),
            Cinema(
                id = "bio_rio",
                name = "Bio Rio (Kvarteret Rio)",
                district = "Hornstull",
                address = "Hornstulls strand 3, Stockholm",
                websiteUrl = "https://kvarteretbiorio.se",
                bookingUrlTemplate = "https://kvarteretbiorio.se/bio/",
                specialty = "Waterside indie venue, documentary festivals & special director cuts",
                isEnabled = true
            ),
            Cinema(
                id = "bio_zita",
                name = "Bio Zita (Folkets Bio)",
                district = "Östermalm",
                address = "Birger Jarlsgatan 37, Stockholm",
                websiteUrl = "https://zita.se",
                bookingUrlTemplate = "https://zita.se/biljetter",
                specialty = "Stockholm's oldest running cinema, world cinema, European arthouse & festivals",
                isEnabled = true
            ),
            Cinema(
                id = "bio_skandia",
                name = "Bio Skandia",
                district = "City / Norrmalm",
                address = "Drottninggatan 82, Stockholm",
                websiteUrl = "https://skandiabio.se",
                bookingUrlTemplate = "https://skandiabio.se/program",
                specialty = "Gunnar Asplund architectural masterpiece, Stockholm Int Film Festival venue & film history icons",
                isEnabled = true
            ),
            Cinema(
                id = "filmstaden",
                name = "Filmstaden Stockholm",
                district = "Multiple Theaters",
                address = "Sergel, Rigoletto, Saga, Söder, Scandinavia & Sickla",
                websiteUrl = "https://www.filmstaden.se",
                bookingUrlTemplate = "https://www.filmstaden.se/film/",
                specialty = "Mainstream releases, IMAX, Dolby Atmos & selected festival crossovers across Stockholm",
                isEnabled = true
            ),
            Cinema(
                id = "bio_aspen",
                name = "Bio Aspen",
                district = "Aspudden",
                address = "Hägerstensvägen 100A, Hägersten",
                websiteUrl = "https://bioaspen.se",
                bookingUrlTemplate = "https://bioaspen.se/program",
                specialty = "Neighborhood cultural bistro cinema, cult film screenings & local Q&As",
                isEnabled = true
            ),
            Cinema(
                id = "klarabiografen",
                name = "Klarabiografen",
                district = "Sergels Torg",
                address = "Kulturhuset Stadsteatern, Plan 2, Sergels Torg",
                websiteUrl = "https://kulturhusetstadsteatern.se/film",
                bookingUrlTemplate = "https://kulturhusetstadsteatern.se/film",
                specialty = "Kulturhuset art cinema, rare retrospectives, animations & international docs",
                isEnabled = true
            ),
            Cinema(
                id = "bio_tellus",
                name = "Bio Tellus",
                district = "Midsommarkransen",
                address = "Vattenledningsvägen 46, Hägersten",
                websiteUrl = "https://tellusbio.nu",
                bookingUrlTemplate = "https://tellusbio.nu/program",
                specialty = "Historic 1920 non-profit cinema club, live score screenings & vintage gems",
                isEnabled = true
            )
        )
    }
}
