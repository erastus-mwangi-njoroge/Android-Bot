package com.acadbot.app

import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock

class Sound(private val ctx: Context) {
    private var player: MediaPlayer? = null
    private val last = HashMap<Int, Long>()

    fun play(res: Int) {
        val now = SystemClock.elapsedRealtime()
        val prev = last[res]
        if (prev != null && now - prev < Timing.SOUND_COOLDOWN_MS) return
        last[res] = now
        try {
            stop()
            val p = MediaPlayer.create(ctx, res) ?: return
            p.setOnCompletionListener { mp ->
                mp.release()
                if (player === mp) player = null
            }
            player = p
            p.start()
        } catch (e: Exception) {
            player = null
        }
    }

    fun stop() {
        try { player?.release() } catch (e: Exception) { }
        player = null
    }
}
