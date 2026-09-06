package se.kinosthlm.app.data.watchlist

import java.io.InputStream
import se.kinosthlm.app.data.model.WatchlistItem

/**
 * Imports a watchlist from a CSV export.
 *
 * Handles both formats the app cares about with one parser, because the difference is only which
 * columns hold the title, year and id:
 *  - **IMDb** — "Your Watchlist" > Export. Columns include `Const` (the tt id), `Title`, `Year`.
 *  - **Google TV** — via Google Takeout. Column names vary by locale and export vintage, so we
 *    match on a set of known aliases and fall back to the first column.
 *  - **Letterboxd** — Settings › Data › Export, `watchlist.csv` inside the zip. Its header is
 *    `Date,Name,Year,Letterboxd URI`, which the same aliases already cover; the URI column is
 *    ignored because it carries a Letterboxd slug rather than a TMDB or IMDb id.
 *
 * This is a file import rather than a live sync because IMDb retired its RSS feeds and its export
 * endpoint requires a logged-in session; there is no unattended path left.
 */
object CsvWatchlistImporter {

  private val TITLE_KEYS = listOf("title", "primary title", "original title", "name", "movie")
  private val YEAR_KEYS = listOf("year", "release year", "release date")
  // "id" last: it is what our own (Trakt-format) export calls the column, but a plain "id" in
  // someone else's export is more likely a row number, so a real IMDb column wins where present.
  private val ID_KEYS = listOf("const", "imdb id", "imdbid", "tconst", "id")
  private val TYPE_KEYS = listOf("title type", "type")

  /**
   * IMDb's own words for things that are not films, from its `Title Type` column.
   *
   * A watchlist full of series is normal on IMDb, and taking IMDb's word for it here means they
   * never reach TMDB to be identified — one less round trip each, and no series sitting in the
   * review queue waiting to be told apart from a film that does not exist. "TV Movie" is
   * deliberately absent: those do turn up in cinema retrospectives.
   */
  private val NON_FILM_TYPES = listOf("tv series", "tv mini", "tv episode", "video game", "podcast")

  /** Trakt's prefixed TMDB id, as our own export writes it: "tmdb_id:603". */
  private val TMDB_ID_CELL = Regex("""tmdb_id:(\d+)""")

  /** An IMDb id occupying a whole cell, not a title that merely starts with "tt". */
  private val IMDB_ID_CELL = Regex("""^tt\d{5,}$""")

  /**
   * A year in the title itself, as Google TV writes disambiguated entries: "Nosferatu (1922)".
   * Only trailing, so a title ending in a number of its own keeps it.
   */
  private val TRAILING_YEAR = Regex("""\s*\((19|20)\d{2}\)\s*$""")

  /**
   * Parse [input] as CSV.
   *
   * [sourceId] is accepted for symmetry with the other providers but is not stamped on the
   * items: which sources contributed a film is recorded separately, so one film can belong to
   * several lists at once.
   */
  fun parse(input: InputStream, @Suppress("UNUSED_PARAMETER") sourceId: String): List<WatchlistItem> {
    // removePrefix strips a UTF-8 BOM, which some exporters prepend and which would otherwise
    // glue itself to the first header name and break column detection.
    val rows = readCsv(input.bufferedReader().readText().removePrefix("﻿"))
    if (rows.isEmpty()) return emptyList()

    val header = rows.first().map { it.trim().lowercase().removeSurrounding("\"") }
    val titleColumn = header.indexOfFirstIn(TITLE_KEYS) ?: 0
    val yearColumn = header.indexOfFirstIn(YEAR_KEYS)
    val idColumn = header.indexOfFirstIn(ID_KEYS)
    // Guard against "Title Type" being mistaken for the title itself on an export that lacks a
    // plain "Title" column.
    val typeColumn = header.indexOfFirstIn(TYPE_KEYS)?.takeIf { it != titleColumn }

    return rows.drop(1)
      .mapNotNull { row ->
        val raw = row.getOrNull(titleColumn)?.trim()?.takeIf { it.isNotEmpty() }
          ?: return@mapNotNull null
        // A row whose "title" is a URL or a bare id is a malformed export, not a film.
        if (raw.startsWith("http") || IMDB_ID_CELL.matches(raw)) return@mapNotNull null

        // Google TV has no year column; it disambiguates in the title instead
        // ("Nosferatu (1922)"). Lift that out — a year is what lets a remake match the right
        // entry, and it would otherwise be stuck inside the title where nothing sees it.
        val titleYear = TRAILING_YEAR.find(raw)?.value?.filter(Char::isDigit)?.toIntOrNull()
        val title = if (titleYear != null) raw.replace(TRAILING_YEAR, "").trim() else raw

        val year = yearColumn?.let { column ->
          Regex("(19|20)\\d{2}").find(row.getOrNull(column).orEmpty())?.value?.toIntOrNull()
        } ?: titleYear
        val idCell = idColumn?.let { row.getOrNull(it).orEmpty() }.orEmpty()
        val imdbId = Regex("tt\\d+").find(idCell)?.value
        // Our own export writes Trakt's prefixed form, "tmdb_id:603". Reading it back keeps the
        // film on the id it already had rather than sending it round TMDB again to rediscover
        // something we knew when we wrote the file.
        val tmdbId = TMDB_ID_CELL.find(idCell)?.groupValues?.get(1)?.toIntOrNull()

        val declaredType = typeColumn?.let { row.getOrNull(it)?.trim()?.lowercase() }.orEmpty()
        val isSeries = NON_FILM_TYPES.any { declaredType.contains(it) }

        WatchlistItem(
          // Usually no TMDB id at parse time — most exports carry only IMDb ids, and
          // TitleResolver backfills the rest afterwards, re-keying the row onto it. Our own
          // export is the exception: it wrote the TMDB id, so the entry lands identified.
          id = WatchlistItem.idFor(tmdbId, imdbId, title, year),
          title = title,
          year = year,
          imdbId = imdbId,
          tmdbId = tmdbId,
          // Only ever set to "series" here; an unrecognised or missing type stays UNKNOWN so
          // TMDB still gets its say.
          titleType =
            if (isSeries) WatchlistItem.TYPE_SERIES else WatchlistItem.TYPE_UNKNOWN,
        )
      }
      .let(::separateRepeats)
  }

  /**
   * Two rows sharing a title with nothing to tell them apart are two different films the user
   * watchlisted — the 1922 Nosferatu and a later remake, say. Collapsing them on their identical
   * generated id would silently drop one, so instead they are kept as separate entries and
   * flagged for review; resolution gives each its own IMDb id.
   *
   * Rows that share an id *and* carry a real IMDb id are genuinely the same film listed twice,
   * and those are deduplicated.
   */
  private fun separateRepeats(items: List<WatchlistItem>): List<WatchlistItem> {
    val seen = mutableMapOf<String, Int>()
    val out = mutableListOf<WatchlistItem>()

    for (item in items) {
      val occurrence = (seen[item.id] ?: 0) + 1
      seen[item.id] = occurrence

      when {
        occurrence == 1 -> out += item
        // A repeat of a row with a real id is a duplicate; drop it.
        item.imdbId != null -> Unit
        else -> {
          // Retro-flag the first occurrence too: it is equally ambiguous.
          val firstIndex = out.indexOfFirst { it.id == item.id }
          if (firstIndex >= 0) out[firstIndex] = out[firstIndex].copy(needsReview = true)
          out += item.copy(id = "${item.id}#$occurrence", needsReview = true)
        }
      }
    }
    return out
  }

  private fun List<String>.indexOfFirstIn(keys: List<String>): Int? =
    keys.firstNotNullOfOrNull { key ->
      indexOfFirst { it == key }.takeIf { it >= 0 }
    } ?: keys.firstNotNullOfOrNull { key ->
      indexOfFirst { it.contains(key) }.takeIf { it >= 0 }
    }

  /**
   * Minimal RFC 4180 reader: handles quoted fields, embedded commas, doubled quotes and
   * newlines inside quotes. Film titles contain all of these, so a naive split breaks on them.
   */
  fun readCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var inQuotes = false
    var index = 0

    while (index < text.length) {
      val char = text[index]
      when {
        inQuotes && char == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
          field.append('"')
          index++
        }
        char == '"' -> inQuotes = !inQuotes
        !inQuotes && char == ',' -> {
          row.add(field.toString())
          field.setLength(0)
        }
        !inQuotes && (char == '\n' || char == '\r') -> {
          if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
          row.add(field.toString())
          field.setLength(0)
          if (row.any { it.isNotBlank() }) rows.add(row)
          row = mutableListOf()
        }
        else -> field.append(char)
      }
      index++
    }
    row.add(field.toString())
    if (row.any { it.isNotBlank() }) rows.add(row)
    return rows
  }
}
