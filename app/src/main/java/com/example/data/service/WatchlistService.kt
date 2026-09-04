package com.example.data.service

import android.util.Log
import com.example.data.model.WatchlistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.UUID
import java.util.concurrent.TimeUnit

class WatchlistService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {

    /**
     * Extracts user ID or list ID from input string (URL or direct ID).
     * Examples:
     * - "ur12345678" -> "ur12345678"
     * - "https://www.imdb.com/user/ur12345678/watchlist" -> "ur12345678"
     * - "ls123456789" -> "ls123456789"
     * - "https://www.imdb.com/list/ls123456789/" -> "ls123456789"
     */
    fun extractImdbId(input: String): String {
        val trimmed = input.trim()
        val urRegex = Regex("ur\\d+")
        val lsRegex = Regex("ls\\d+")

        urRegex.find(trimmed)?.let { return it.value }
        lsRegex.find(trimmed)?.let { return it.value }
        return trimmed
    }

    /**
     * Attempts to fetch IMDb watchlist from RSS feed or web page.
     */
    suspend fun fetchImdbWatchlist(identifierOrUrl: String): List<WatchlistItem> = withContext(Dispatchers.IO) {
        val id = extractImdbId(identifierOrUrl)
        val items = mutableListOf<WatchlistItem>()

        // Strategy 1: Try public IMDb RSS feed (Works for public IMDb user watchlists)
        if (id.startsWith("ur")) {
            val rssUrl = "https://rss.imdb.com/user/$id/watchlist"
            try {
                val request = Request.Builder().url(rssUrl).build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val xml = response.body?.string().orEmpty()
                    if (xml.contains("<item>")) {
                        val doc = Jsoup.parse(xml, "", org.jsoup.parser.Parser.xmlParser())
                        val xmlItems = doc.select("item")
                        for (el in xmlItems) {
                            val title = el.select("title").text().trim()
                            val link = el.select("link").text().trim()
                            val imdbIdMatch = Regex("tt\\d+").find(link)
                            val imdbId = imdbIdMatch?.value ?: "tt_${UUID.randomUUID().toString().take(8)}"
                            if (title.isNotEmpty()) {
                                items.add(
                                    WatchlistItem(
                                        id = imdbId,
                                        title = cleanTitle(title),
                                        source = "IMDb",
                                        overview = "Imported from IMDb watchlist ($id)"
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("WatchlistService", "IMDb RSS fetch attempt: ${e.message}")
            }
        }

        // Strategy 2: If RSS was empty or failed (or list ID like ls...), try public web page parsing
        if (items.isEmpty()) {
            val pageUrl = if (id.startsWith("http")) {
                id
            } else if (id.startsWith("ls")) {
                "https://www.imdb.com/list/$id/"
            } else if (id.startsWith("ur")) {
                "https://www.imdb.com/user/$id/watchlist"
            } else {
                "https://www.imdb.com/user/$id/watchlist"
            }

            try {
                val request = Request.Builder()
                    .url(pageUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val html = response.body?.string().orEmpty()
                    val doc = Jsoup.parse(html)

                    // Find JSON-LD or title links
                    val titleLinks = doc.select("a[href*=/title/tt]")
                    for (link in titleLinks) {
                        val text = link.text().trim()
                        val href = link.attr("href")
                        val imdbId = Regex("tt\\d+").find(href)?.value
                        if (imdbId != null && text.length in 2..60 && !text.contains("Episode", ignoreCase = true) && !items.any { it.id == imdbId }) {
                            items.add(
                                WatchlistItem(
                                    id = imdbId,
                                    title = cleanTitle(text),
                                    source = "IMDb",
                                    overview = "Synced from IMDb public watchlist"
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("WatchlistService", "IMDb Web scrape attempt: ${e.message}")
            }
        }

        items
    }

    /**
     * Parses Google TV / Google Watchlist exported text or shared titles.
     * Users often copy/paste their Google Watchlist as lines of movie names.
     */
    fun parseGoogleTvWatchlist(textInput: String): List<WatchlistItem> {
        val lines = textInput.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").removePrefix("•").trim() }
            .filter { it.length in 2..80 && !it.startsWith("http") }

        return lines.distinct().map { title ->
            val yearMatch = Regex("\\((\\d{4})\\)").find(title)
            val year = yearMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            val cleanName = cleanTitle(title.replace(Regex("\\(\\d{4}\\)"), "").trim())
            val id = "gtv_${cleanName.lowercase().replace(Regex("[^a-z0-9]"), "_")}"

            WatchlistItem(
                id = id,
                title = cleanName,
                year = year,
                source = "Google TV",
                overview = "Imported from Google TV watchlist"
            )
        }
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("^\\d+\\.\\s*"), "") // remove "1. Movie" numbering
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Curated sample titles showcasing classic & contemporary cinema matching Stockholm venues.
     */
    val samplePresetWatchlist: List<WatchlistItem> = listOf(
        WatchlistItem(
            id = "tt15398776",
            title = "The Substance",
            year = 2024,
            director = "Coralie Fargeat",
            source = "IMDb",
            imdbRating = 7.4f,
            genres = "Horror, Sci-Fi, Drama",
            posterUrl = "https://m.media-amazon.com/images/M/MV5BMjYwOTg1OTctZmU2MC00YTc4LTg4NmItZjlmMjI5MGIzYmEyXkEyXkFqcGc@._V1_SX300.jpg",
            overview = "A fading celebrity uses a black market substance that temporarily creates a younger, better version of herself."
        ),
        WatchlistItem(
            id = "tt15239678",
            title = "Dune: Part Two",
            year = 2024,
            director = "Denis Villeneuve",
            source = "Google TV",
            imdbRating = 8.5f,
            genres = "Action, Adventure, Sci-Fi",
            posterUrl = "https://m.media-amazon.com/images/M/MV5BN2QyZGU4ZDctOWMzMy00NTc5LThlOGQtODhmNDI1NmY5YzAwXkEyXkFqcGc@._V1_SX300.jpg",
            overview = "Paul Atreides unites with Chani and the Fremen while seeking revenge against the conspirators who destroyed his family."
        ),
        WatchlistItem(
            id = "tt23736044",
            title = "Anora",
            year = 2024,
            director = "Sean Baker",
            source = "IMDb",
            imdbRating = 7.9f,
            genres = "Comedy, Drama, Romance",
            posterUrl = "https://m.media-amazon.com/images/M/MV5BYzA5ODVkZmQtNmU0ZS00MWI3LTlkYzYtMDgwOTAxNjJkYmRmXkEyXkFqcGc@._V1_SX300.jpg",
            overview = "Anora, a young sex worker from Brooklyn, gets her chance at a Cinderella story when she meets and impulsively marries the son of an oligarch."
        ),
        WatchlistItem(
            id = "tt0062622",
            title = "2001: A Space Odyssey",
            originalTitle = "2001: Ett rymdäventyr",
            year = 1968,
            director = "Stanley Kubrick",
            source = "IMDb",
            imdbRating = 8.3f,
            genres = "Adventure, Sci-Fi",
            posterUrl = "https://m.media-amazon.com/images/M/MV5BMmNlYzRiNDctZWNhMi00MzI4LThkZTctMTUzMmZkMmFmNThmXkEyXkFqcGc@._V1_SX300.jpg",
            overview = "After uncovering a mysterious artifact buried on the Lunar surface, a spacecraft is sent to Jupiter to find its origins."
        ),
        WatchlistItem(
            id = "tt13651794",
            title = "Past Lives",
            year = 2023,
            director = "Celine Song",
            source = "Google TV",
            imdbRating = 7.8f,
            genres = "Drama, Romance",
            posterUrl = "https://m.media-amazon.com/images/M/MV5BOTkzYmMxNTItZDAxNC00NGM0LWIyODMtMWYzMzRkMjIyMTE1XkEyXkFqcGc@._V1_SX300.jpg",
            overview = "Nora and Hae Sung, two deeply connected childhood friends, are wrested apart after Nora's family emigrates from South Korea."
        ),
        WatchlistItem(
            id = "tt14230458",
            title = "Poor Things",
            year = 2023,
            director = "Yorgos Lanthimos",
            source = "IMDb",
            imdbRating = 7.9f,
            genres = "Comedy, Drama, Romance, Sci-Fi",
            posterUrl = "https://m.media-amazon.com/images/M/MV5BNGIyYWMzNjktNDE3MC00YWQ0LWE4NjQtNzc4MWRmOTE2MmEzXkEyXkFqcGc@._V1_SX300.jpg",
            overview = "The incredible tale about the fantastical evolution of Bella Baxter, a young woman brought back to life by the brilliant and unorthodox scientist Dr. Godwin Baxter."
        ),
        WatchlistItem(
            id = "tt5034838",
            title = "Nosferatu",
            year = 2024,
            director = "Robert Eggers",
            source = "IMDb",
            imdbRating = 7.7f,
            genres = "Horror, Mystery",
            posterUrl = "https://m.media-amazon.com/images/M/MV5BZWE5MTE0MjctYjhhMC00MzE3LTkyNzUtM2ZhOGQ1OWEwYjBhXkEyXkFqcGc@._V1_SX300.jpg",
            overview = "A gothic tale of obsession between a haunted young woman and the terrifying vampire infatuated with her, causing untold horror in its wake."
        ),
        WatchlistItem(
            id = "tt6587046",
            title = "The Boy and the Heron",
            originalTitle = "Pojken och hägern",
            year = 2023,
            director = "Hayao Miyazaki",
            source = "IMDb",
            imdbRating = 7.5f,
            genres = "Animation, Adventure, Drama",
            posterUrl = "https://m.media-amazon.com/images/M/MV5BMjYxNWMyNTUtMDc1ZS00Y2NkLTkyNmYtMTFlYjhmZTU0ZTU0XkEyXkFqcGc@._V1_SX300.jpg",
            overview = "Through encounters with his friends and uncle, follows a boy's psychological development as he enters a world shared by the living and the dead."
        ),
        WatchlistItem(
            id = "tt0083658",
            title = "Blade Runner: The Final Cut",
            year = 1982,
            director = "Ridley Scott",
            source = "IMDb",
            imdbRating = 8.1f,
            genres = "Action, Drama, Sci-Fi",
            posterUrl = "https://m.media-amazon.com/images/M/MV5BNzQzMzJhZTEtOWM4NS00MTdhLTg0OGUtY2E3MTg2ODE4MDljXkEyXkFqcGc@._V1_SX300.jpg",
            overview = "A blade runner must pursue and terminate four replicants who stole a ship in space and have returned to Earth to find their creator."
        )
    )

    /**
     * Searchable film catalog to easily add titles.
     */
    val searchableCatalog: List<WatchlistItem> = samplePresetWatchlist + listOf(
        WatchlistItem(
            id = "tt16426418",
            title = "Challengers",
            year = 2024,
            director = "Luca Guadagnino",
            source = "Manual",
            imdbRating = 7.2f,
            genres = "Drama, Romance, Sport",
            overview = "Tashi, a former tennis prodigy turned coach, enters her grand slam champion husband into a challenger event against her former lover."
        ),
        WatchlistItem(
            id = "tt18411490",
            title = "Alien: Romulus",
            year = 2024,
            director = "Fede Álvarez",
            source = "Manual",
            imdbRating = 7.2f,
            genres = "Horror, Sci-Fi",
            overview = "While scavenging the deep ends of a derelict space station, a group of young space colonizers come face to face with the most terrifying life form in the universe."
        ),
        WatchlistItem(
            id = "tt27502004",
            title = "Perfect Days",
            year = 2023,
            director = "Wim Wenders",
            source = "Manual",
            imdbRating = 7.9f,
            genres = "Drama",
            overview = "Hirayama cleans public toilets in Tokyo, finding contentment in books, music, and trees until unexpected encounters reveal his past."
        ),
        WatchlistItem(
            id = "tt0166924",
            title = "Mulholland Drive",
            year = 2001,
            director = "David Lynch",
            source = "Manual",
            imdbRating = 7.9f,
            genres = "Drama, Mystery, Thriller",
            overview = "After a car wreck on the winding Mulholland Drive renders a woman amnesiac, she and a perky Hollywood-hopeful search for clues and answers across Los Angeles."
        ),
        WatchlistItem(
            id = "tt0060827",
            title = "Persona",
            year = 1966,
            director = "Ingmar Bergman",
            source = "Manual",
            imdbRating = 8.1f,
            genres = "Drama, Thriller",
            overview = "A nurse is put in charge of a mute actress and finds that their personae are beginning to meld or change."
        ),
        WatchlistItem(
            id = "tt0245429",
            title = "Spirited Away",
            originalTitle = "Chihiro och häxorna",
            year = 2001,
            director = "Hayao Miyazaki",
            source = "Manual",
            imdbRating = 8.6f,
            genres = "Animation, Adventure, Family",
            overview = "During her family's move to the suburbs, a sullen 10-year-old girl wanders into a world ruled by gods, witches, and spirits."
        ),
        WatchlistItem(
            id = "tt14208870",
            title = "La Chimera",
            year = 2023,
            director = "Alice Rohrwacher",
            source = "Manual",
            imdbRating = 7.3f,
            genres = "Adventure, Comedy, Drama",
            overview = "A central Italian town in the 1980s: Arthur, an English archaeologist with a gift for divination, falls in with tombaroli tomb-raiders searching for Etruscan relics."
        ),
        WatchlistItem(
            id = "tt7160372",
            title = "The Zone of Interest",
            year = 2023,
            director = "Jonathan Glazer",
            source = "Manual",
            imdbRating = 7.4f,
            genres = "Drama, History, War",
            overview = "Auschwitz commandant Rudolf Höss and his wife Hedwig strive to build a dream life for their family in a house and garden next to the camp."
        )
    )
}
