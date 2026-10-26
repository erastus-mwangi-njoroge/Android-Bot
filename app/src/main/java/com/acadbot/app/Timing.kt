package com.acadbot.app

/**
 * Every wait/interval in one place. Values marked (orig) match the desktop build;
 * values marked (new) are Android-specific and still open for tuning.
 */
object Timing {
    const val IDLE_POLL_MS = 100L            // (new) pause between order-list scans; desktop spun with no pause
    const val GET_ORDER_POLL_MS = 50L        // (new) pause between "Get Order" button checks; desktop spun
    const val GET_ORDER_TIMEOUT_MS = 60_000L // (orig) max wait for the "Get Order" button
    const val AFTER_CLICK_SETTLE_MS = 1_000L // (new) wait after clicking before judging success
    const val PAGE_LOAD_TIMEOUT_MS = 20_000L // (new) give up if an order page never opens
    const val OFFPAGE_RECOVER_MS = 20_000L   // (new) go back to the order list if stuck elsewhere
    const val LOGIN_WAIT_MS = 15_000L        // (orig) wait for login to complete
    const val REFRESH_CHECK_MS = 30_000L     // (orig) how often to test whether a refresh is due
    const val HEARTBEAT_MS = 30_000L         // (orig)
    const val SOUND_COOLDOWN_MS = 5_000L     // (orig)
    const val ERROR_BACKOFF_MS = 2_000L      // (orig)
    const val WATCHDOG_MS = 30_000L          // (new) restart the hidden browser if it stops answering
}
