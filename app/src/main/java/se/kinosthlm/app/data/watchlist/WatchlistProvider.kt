package se.kinosthlm.app.data.watchlist

import se.kinosthlm.app.data.model.WatchlistItem

/**
 * A source of films the user wants to see.
 *
 * ## Adding a provider
 * Implement this, then surface it in the import UI. Providers that can refresh unattended
 * report [supportsBackgroundSync] = true and get pulled on every scheduled sync; the rest are
 * one-shot imports the user triggers by hand.
 */
interface WatchlistProvider {

  /** Stable id, stored on [WatchlistItem.source]. */
  val id: String

  val label: String

  /** Whether [sync] can run without the user present. File imports cannot. */
  val supportsBackgroundSync: Boolean

  /** True when this provider is configured and ready to sync. */
  suspend fun isConnected(): Boolean

  /**
   * Fetch the current watchlist.
   *
   * Throws on failure — callers surface the error rather than silently keeping stale data.
   */
  suspend fun sync(): List<WatchlistItem>
}
