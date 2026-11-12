package com.gathin.tvhome.hero

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.gathin.tvhome.R

/**
 * The open card's "Watch trailer": the YouTube link in SmartTube when it is installed (any of its builds), else
 * YouTube for TV, else whatever plays the link.
 */
internal object TrailerHandoff {
    /** In order of preference: SmartTube's builds (current names, then the older ones), then YouTube for TV. */
    private val PLAYERS = listOf(
        "org.smarttube.stable",
        "org.smarttube.beta",
        "com.teamsmart.videomanager.tv",
        "com.liskovsoft.smarttubetv",
        "com.liskovsoft.smarttubetv.beta",
        "com.google.android.youtube.tv",
    )

    fun open(context: Context, key: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (pkg in PLAYERS + listOf<String?>(null)) {
            try {
                context.startActivity(Intent(view).setPackage(pkg))
                return
            } catch (e: ActivityNotFoundException) {
                // Not installed (or it does not take the link): the next choice.
            } catch (e: SecurityException) {
                // The next choice.
            }
        }
        Toast.makeText(context, R.string.hero_no_trailer_app, Toast.LENGTH_SHORT).show()
    }
}
