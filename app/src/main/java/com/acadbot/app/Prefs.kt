package com.acadbot.app

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("acadbot", Context.MODE_PRIVATE)

    var email: String
        get() = sp.getString("email", "") ?: ""
        set(v) { sp.edit().putString("email", v).apply() }
    var password: String
        get() = sp.getString("password", "") ?: ""
        set(v) { sp.edit().putString("password", v).apply() }
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) { sp.edit().putString("token", v).apply() }
    var remember: Boolean
        get() = sp.getBoolean("remember", true)
        set(v) { sp.edit().putBoolean("remember", v).apply() }
    var blockImages: Boolean
        get() = sp.getBoolean("block_images", true)
        set(v) { sp.edit().putBoolean("block_images", v).apply() }
    var verbose: Boolean
        get() = sp.getBoolean("verbose", true)
        set(v) { sp.edit().putBoolean("verbose", v).apply() }
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_on", false)
        set(v) { sp.edit().putBoolean("keep_on", v).apply() }
    var refreshMin: Int
        get() = sp.getInt("refresh_min", 10)
        set(v) { sp.edit().putInt("refresh_min", v).apply() }
}
