package com.acadbot.app

/**
 * Every wait/interval in one place. Values marked (orig) match the desktop build;
 * values marked (new) are Android-specific and still open for tuning.
 */
object Timing {
    const val BG_FRESH_MS = 8_000L           // (new) a background result older than this is ignored
    const val IDLE_POLL_MS = 50L             // (new) pause between order-list scans; desktop spun with no pause
    const val GET_ORDER_POLL_MS = 50L        // (new) pause between "Get Order" button checks; desktop spun
    const val GET_ORDER_MIN_WAIT_MS = 60_000L // (orig) lock: "Get Order" only works 60s after the order is opened; never click earlier
    const val READ_TICK_AT_MS = 10_000L      // (new) tick the "read" checkbox this long after the order opens (inside the 60s lock)
    const val GET_ORDER_TIMEOUT_MS = 90_000L // (new) max total wait (from order opening) for the button: 60s lock + 30s grace
    const val AFTER_CLICK_POLL_MS = 250L     // (new) pause between checks while waiting for the page to react to the click
    const val AFTER_CLICK_TIMEOUT_MS = 20_000L // (new) max wait for the site's response after clicking before judging
    const val PAGE_LOAD_TIMEOUT_MS = 20_000L // (new) give up if an order page never opens
    const val OFFPAGE_RECOVER_MS = 20_000L   // (new) go back to the order list if stuck elsewhere
    const val LOGIN_WAIT_MS = 15_000L        // (orig) wait for login to complete
    const val HEARTBEAT_MS = 30_000L         // (orig)
    const val SOUND_COOLDOWN_MS = 5_000L     // (orig)
    const val ERROR_BACKOFF_MS = 2_000L      // (orig)
    const val WATCHDOG_MS = 15_000L          // (new) re-arm the scan loop silently if a tick never returns (no browser restart)
}
