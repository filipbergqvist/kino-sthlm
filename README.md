# KinoSthlm

[![Build](https://github.com/filipbergqvist/kino-sthlm/actions/workflows/build.yml/badge.svg)](https://github.com/filipbergqvist/kino-sthlm/actions/workflows/build.yml)
[![Cinema source canary](https://github.com/filipbergqvist/kino-sthlm/actions/workflows/canary.yml/badge.svg)](https://github.com/filipbergqvist/kino-sthlm/actions/workflows/canary.yml)
[![Latest release](https://img.shields.io/github/v/release/filipbergqvist/kino-sthlm)](https://github.com/filipbergqvist/kino-sthlm/releases/latest)
[![License: MIT](https://img.shields.io/github/license/filipbergqvist/kino-sthlm)](LICENSE)

Watches Stockholm cinema schedules for films on your watchlist and tells you the moment one is
booked in — early enough that you still get good seats.

Everything runs on your phone. There is no server, no account, and no analytics. The app talks
directly to the cinemas' own websites and to Trakt, on a schedule you choose or whenever you
pull to refresh.

## What it does

- **Syncs your watchlist** from Trakt (automatically, in the background) or from an IMDb /
  Google TV CSV export. Films remember which lists they came from, so removing one upstream
  removes it here — and a film on two lists survives being dropped from one.
- **Identifies bare titles** via TMDB: which year, which of two same-named films, and whether it
  is a TV series (Google TV exports mix those in; they are hidden, since they never play in
  cinemas).
- **Checks Stockholm cinemas** for anything on that list — 14 venues on the Filmstaden booking
  platform plus four independents, each one individually switchable. Two months ahead by
  default, since the independents post their repertory programme that far out; adjustable in
  Settings.
- **Notifies you about the right venue** — cinemas are tagged (Big Screen, Cozy, IMAX) and a film
  can require one, so a blockbuster only pings you for a big screen and a classic only for a
  small independent.
- **Shows you the film** — tap a watchlist entry for its poster, synopsis, an IMDb link and which
  lists it came from. Removing it lives here too, as a deliberate second step rather than a
  stray tap in the list.
- **Notifies you** when a match appears, with the cinema, the time, and a link straight to the
  ticket page. Only for showings you have not already been told about.

## Cinemas

| Venue | Source |
| --- | --- |
| Filmstaden Rigoletto, Sergel, Scandinavia, Kista, Heron City, Sickla, Vällingby, Råsunda, Täby | `services.cinema-api.com` (the JSON API filmstaden.se itself uses) |
| Grand, Victoria, Sture, Saga, Grand Lidingö | same API |
| Bio Skandia | film index + per-film pages on bioskandia.se |
| Bio Capitol | server-rendered programme on capitolbio.se |
| Bio Rio | server-rendered calendar page on biorio.se |
| Biocafé Tellus | The Events Calendar REST API on tellusbio.nu |

No cinema here offers a public API contract, so the four independents are read from their public
pages. When one redesigns, that adapter breaks — loudly. The app says which source failed and
keeps the screenings it already knows about; it never fills the gap with invented data. A daily
[canary workflow](.github/workflows/canary.yml) checks every source and opens an issue when one
stops responding.

## Building it yourself

You need **Android Studio** (free). Open it once and let the setup wizard install the Android
SDK — that is the only prerequisite.

```bash
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

…or copy the file to your phone and tap it. Android will ask you to allow installs from your
file manager the first time.

Inside Android Studio, plugging in a phone with USB debugging enabled and pressing **Run** does
the same thing.

**You do not need a Google Play Console account.** That is only for publishing to the Play Store.

### API keys

Two optional keys, both free, both registered **once by whoever builds the app** — people
installing your APK need nothing. Put them in `local.properties` (gitignored) or, for CI, in
repository secrets.

| Key | What it unlocks | Without it |
| --- | --- | --- |
| `TRAKT_CLIENT_ID` / `TRAKT_CLIENT_SECRET` | Automatic background watchlist sync | The Trakt option says it is not configured; CSV import still works |
| `TMDB_API_KEY` | Identifying bare titles: years, IMDb ids, film-vs-series | Google TV titles stay unidentified, so series are not hidden and same-named films cannot be told apart |

TMDB keys come from <https://www.themoviedb.org/settings/api> (pick "Developer", it is instant
and free for non-commercial use).

### Connecting Trakt

Trakt is the only source that can refresh on its own, and it needs a free app registration:

1. Go to <https://trakt.tv/oauth/applications> → **New Application**.
2. Name it anything. For the redirect URI put `urn:ietf:wg:oauth:2.0:oob` — the app uses the
   device-code flow, so the redirect is never used.
3. Copy the Client ID and Client Secret into `local.properties` (already gitignored):

   ```properties
   TRAKT_CLIENT_ID=your_client_id
   TRAKT_CLIENT_SECRET=your_client_secret
   ```

4. Rebuild. In **Settings → Trakt → Connect**, the app shows an eight-character code; enter it at
   <https://trakt.tv/activate> once and it syncs from then on.

Each person using the app does step 4 with their own Trakt account. Steps 1–3 are yours alone.

### IMDb and Google TV

IMDb retired its RSS feeds and its export endpoint needs a logged-in session, so there is no
unattended path. Use **Your Watchlist → Export** on IMDb, then **Settings → IMDb → Import** and
pick the CSV. There is also a best-effort reader for a *public* IMDb list, but IMDb can block or
reshape it at any time — the CSV is the dependable route.

Google TV goes through [Google Takeout](https://takeout.google.com) (`Saved → Watchlist.csv`).
That export is thin: `Title,Note,URL,Tags,Comment`, where the URL column is a placeholder on
every row. **No release years, no film ids, and no way to tell a film from a TV series.**

TMDB fills that in. After an import the app identifies each title, attaches the year and IMDb
id, hides anything that turns out to be a series, and — when a name really is shared by several
films — asks you which one you meant rather than guessing. Two rows with the same name are kept
apart for exactly that reason, so an original and its remake do not silently become one entry.

## How the watchlist behaves

Each film records which lists put it there. That provenance is what makes removal work:

- Remove a film **from Trakt** and it disappears here on the next sync — unless another
  connected list still has it, in which case it stays.
- Remove it **in the app** and it is hidden, even while a source still lists it. Deleting it
  here cannot undo itself on the next sync, but the source is still the source of truth: clear
  it upstream too and the entry is discarded for good.
- **Add a film by hand**, either with an IMDb/TMDB link (identifies it exactly, no guessing) or
  by typing a title — add a year if it is shared by more than one film — which searches TMDB and
  offers up to three matches to pick between. Hand-added films survive every sync until you
  remove them by hand.

## Adding a cinema

One class and one line:

1. Implement [`CinemaSource`](app/src/main/java/se/kinosthlm/app/data/source/CinemaSource.kt) in
   `data/source/`. Prefer a structured source — a JSON API, JSON-LD, a WordPress REST endpoint —
   over parsing rendered markup; look at what the site's own frontend calls before scraping it.
2. Register it in
   [`CinemaSourceRegistry`](app/src/main/java/se/kinosthlm/app/data/source/CinemaSourceRegistry.kt).
3. Add the venue to `AppDatabase.defaultCinemas` with a matching `sourceId`.
4. Save a fixture under `app/src/test/resources/fixtures/` and add a parsing test, so a redesign
   fails a test rather than going unnoticed.

Throw on failure. Never return placeholder data — an empty list must mean "nothing is scheduled",
or the whole app becomes untrustworthy.

Venues already on the Filmstaden API need no code at all: find the venue's `NCG…` id in
`https://services.cinema-api.com/show/stripped/sv/1/2000/?Channel=Web` and add a row.

## Tests

```bash
./gradlew test                                             # offline: fixtures, matching, CSV
KINO_LIVE_TESTS=1 ./gradlew test --tests '*LiveSourceCanaryTest*'   # hits the real sites
```

The offline suite runs on every push. The live canaries run daily in CI.

## Releases

Push a tag and [`release.yml`](.github/workflows/release.yml) builds the APK and attaches it to a
GitHub Release:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

Add `TRAKT_CLIENT_ID`, `TRAKT_CLIENT_SECRET` and `TMDB_API_KEY` as repository secrets so released
builds ship with both integrations enabled.

## Stack

Kotlin, Jetpack Compose (Material 3), Room, WorkManager, DataStore, OkHttp, Moshi, Jsoup. No
Firebase, no AI services, no third-party analytics.

## Licence

MIT — see [LICENSE](LICENSE).

Not affiliated with any of the cinemas. Be considerate with the sync interval: their websites are
run by small teams.
