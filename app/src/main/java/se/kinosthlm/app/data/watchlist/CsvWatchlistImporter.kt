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
 *
 * This is a file import rather than a live sync because IMDb retired its RSS feeds and its export
 * endpoint requires a logged-in session; there is no unattended path left.
 */
object CsvWatchlistImporter {

  private val TITLE_KEYS = listOf("title", "primary title", "original title", "name", "movie")
  private val YEAR_KEYS = listOf("year", "release year", "release date")
  private val ID_KEYS = listOf("const", "imdb id", "imdbid", "tconst")

  /** An IMDb id occupying a whole cell, not a title that merely starts with "tt". */
  private val IMDB_ID_CELL = Regex("""^tt\d{5,}$""")

  /**
   * A year in the title itself, as Google TV writes disambiguated entries: "Ghostbusters (1984)".
   * Only trailing, so "2001: A Space Odyssey" and "Blade Runner 2049" keep their numbers.
   */
  private val TRAILING_YEAR = Regex("""\s*\((19|20)\d{2}\)\s*$""")

  /** Parse [input] as CSV. [sourceId] tags the resulting items, e.g. [WatchlistItem.SOURCE_IMDB]. */
  fun parse(input: InputStream, sourceId: String): List<WatchlistItem> {
    // removePrefix strips a UTF-8 BOM, which some exporters prepend and which would otherwise
    // glue itself to the first header name and break column detection.
    val rows = readCsv(input.bufferedReader().readText().removePrefix("﻿"))
    if (rows.isEmpty()) return emptyList()

    val header = rows.first().map { it.trim().lowercase().removeSurrounding("\"") }
    val titleColumn = header.indexOfFirstIn(TITLE_KEYS) ?: 0
    val yearColumn = header.indexOfFirstIn(YEAR_KEYS)
    val idColumn = header.indexOfFirstIn(ID_KEYS)

    return rows.drop(1)
      .mapNotNull { row ->
        val raw = row.getOrNull(titleColumn)?.trim()?.takeIf { it.isNotEmpty() }
          ?: return@mapNotNull null
        // A row whose "title" is a URL or a bare id is a malformed export, not a film.
        if (raw.startsWith("http") || IMDB_ID_CELL.matches(raw)) return@mapNotNull null

        // Google TV has no year column; it disambiguates in the title instead
        // ("Ghostbusters (1984)"). Lift that out — a year is what lets a remake match the
        // right entry, and it would otherwise be stuck inside the title where nothing sees it.
        val titleYear = TRAILING_YEAR.find(raw)?.value?.filter(Char::isDigit)?.toIntOrNull()
        val title = if (titleYear != null) raw.replace(TRAILING_YEAR, "").trim() else raw

        val year = yearColumn?.let { column ->
          Regex("(19|20)\\d{2}").find(row.getOrNull(column).orEmpty())?.value?.toIntOrNull()
        } ?: titleYear
        val imdbId = idColumn?.let { column ->
          Regex("tt\\d+").find(row.getOrNull(column).orEmpty())?.value
        }

        WatchlistItem(
          id = WatchlistItem.idFor(imdbId, title, year),
          title = title,
          year = year,
          imdbId = imdbId,
          source = sourceId,
        )
      }
      .distinctBy { it.id }
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
