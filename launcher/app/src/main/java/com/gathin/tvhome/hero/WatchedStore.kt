package com.gathin.tvhome.hero

import android.content.Context

/**
 * Titles marked as watched (a long press of OK on a slide, or the open card's button), by [Slide.key]. They
 * never show in the carousel again, even on a later day's feed. Kept on the TV only; clearing Home's data forgets them.
 */
class WatchedStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun keys(): Set<String> = prefs.getStringSet(KEY, null)?.toSet().orEmpty()

    fun save(keys: Set<String>) {
        // A copy: SharedPreferences must not be handed back the set it returned.
        prefs.edit().putStringSet(KEY, HashSet(keys)).apply()
    }

    private companion object {
        const val PREFS = "hero_watched"
        const val KEY = "keys"
    }
}
