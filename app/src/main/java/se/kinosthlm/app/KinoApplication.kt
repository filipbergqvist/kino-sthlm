package se.kinosthlm.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * Application entry point, here only to give Coil a cache worth having.
 *
 * Posters are the one thing this app downloads repeatedly, and they never change once fetched —
 * so they are cached to disk generously and kept across restarts. Without this the default cache
 * is small enough that scrolling a long watchlist twice re-downloads most of it, which costs both
 * the user's data and TMDB's image bandwidth for no benefit at all.
 */
class KinoApplication : Application(), ImageLoaderFactory {

  override fun newImageLoader(): ImageLoader =
    ImageLoader.Builder(this)
      .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
      .diskCache {
        DiskCache.Builder()
          .directory(cacheDir.resolve("poster_cache"))
          .maxSizeBytes(POSTER_CACHE_BYTES)
          .build()
      }
      // A poster keeps the same URL for the life of the film, so there is nothing to revalidate.
      .respectCacheHeaders(false)
      .build()

  private companion object {
    /** 128 MB is a few thousand posters — comfortably more than any one watchlist needs. */
    const val POSTER_CACHE_BYTES = 128L * 1024 * 1024
  }
}
