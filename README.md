# KinoSthlm

Watches Stockholm cinema schedules for films on your watchlist and tells you the moment one is
booked in — early enough that you still get good seats.

Everything runs on your phone. There is no server, no account, and no analytics. The app talks
directly to the cinemas' own websites and to Trakt, on a schedule you choose or whenever you
pull to refresh.

## What it does

- **Syncs your watchlist** from Trakt (automatically, in the background) or from an IMDb /
  Google TV CSV export.
- **Checks Stockholm cinemas** for anything on that list — 14 venues on the Filmstaden booking
  platform plus four independents, each one individually switchable.
- **Notifies you** when a match appears, with the cinema, the time, and a link straight to the
  ticket page. Only for showings you have not already been told about.

## Cinemas

| Venue | Source |
| --- | --- |
| Filmstaden Rigoletto, Sergel, Scandinavia, Kista, Heron City, Sickla, Vällingby, Råsunda, Täby | `services.cinema-api.com` (the JSON API filmstaden.se itself uses) |
| Grand, Victoria, Sture, Saga, Grand Lidingö | same API |
| Bio Skandia | film index + per-film pages on bioskandia.se |
| Bio Capitol | server-rendered programme on capitolbio.se |
| Bio Rio | schema.org `ScreeningEvent` JSON-LD on biorio.se |
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

Without these the app still works — the Trakt option just says it is not configured, and CSV
import covers the rest.

### IMDb and Google TV

IMDb retired its RSS feeds and its export endpoint needs a logged-in session, so there is no
unattended path. Use **Your Watchlist → Export** on IMDb, then **Settings → IMDb → Import** and
pick the CSV. There is also a best-effort reader for a *public* IMDb list, but IMDb can block or
reshape it at any time — the CSV is the dependable route.

Google TV goes through [Google Takeout](https://takeout.google.com) (`Saved → Watchlist.csv`).
Be aware of what that export contains: `Title,Note,URL,Tags,Comment`, where the URL column is a
placeholder for every row. **No release years and no film ids**, so Google TV titles are matched
by name alone. Google disambiguates the odd remake in the title itself (`Ghostbusters (1984)`)
and the importer lifts that out, but two films sharing a name and no year collapse into one
entry. IMDb's export carries `tt` ids and years, so prefer it where you have both.

Takeout also mixes TV series into the same file. They are imported and simply never match a
cinema screening — harmless, but it is why the watchlist count can look higher than expected.

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

Add `TRAKT_CLIENT_ID` and `TRAKT_CLIENT_SECRET` as repository secrets to ship builds with Trakt
enabled.

## Stack

Kotlin, Jetpack Compose (Material 3), Room, WorkManager, DataStore, OkHttp, Moshi, Jsoup. No
Firebase, no AI services, no third-party analytics.

## Licence

MIT — see [LICENSE](LICENSE).

Not affiliated with any of the cinemas. Be considerate with the sync interval: their websites are
run by small teams.
