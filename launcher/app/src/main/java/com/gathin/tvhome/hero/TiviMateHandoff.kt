package com.gathin.tvhome.hero

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.gathin.tvhome.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OK on a slide: hand its title to the target app's search (TiviMate, ar.tvplayer.tv, by default). TiviMate does
 * not document a search intent, so the routes are tried in order and the first one the app answers wins:
 *
 * 1. a searchable activity: ACTION_SEARCH with the query, the app's own search screen with the title submitted;
 * 2. the voice-search action TV apps declare for the Assistant (com.google.android.gms.actions.SEARCH_ACTION);
 * 3. its global-search suggestion provider: the top suggestion's own intent (straight to the title);
 * 4. otherwise open the app and put the title on the clipboard.
 *
 * TiviMate 5.1.5 (decompiled with jadx): its manifest has no SEARCH filter, no searchable meta-data, no suggestion
 * provider and no deep-link scheme; the only exported entry is ar.tvplayer.tv.ui.MainActivity (MAIN/LAUNCHER). Its UI
 * code is encrypted (DexProtector, assets/classes.dex.dat), so whether MainActivity reads any extra cannot be seen.
 * Routes 1 to 3 therefore find nothing in TiviMate and route 4 runs; they stay for other target apps. Route 4 also
 * carries the query as the usual extras, in case a TiviMate version ever reads them.
 */
object TiviMateHandoff {
    private const val TAG = "TiviMateHandoff"
    const val GMS_SEARCH = "com.google.android.gms.actions.SEARCH_ACTION"

    fun label(context: Context, pkg: String): String? = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    suspend fun search(context: Context, pkg: String, query: String) {
        val name = label(context, pkg)
        if (name == null) {
            Toast.makeText(context, context.getString(R.string.hero_not_installed, pkg), Toast.LENGTH_SHORT).show()
            return
        }
        val pm = context.packageManager
        // The accessibility helper, when the owner switched it on: open the app and let it walk to Search and fill
        // in the title (SearchHandoffService). TiviMate has no search intent, so this is the route that works there.
        if (SearchHandoffService.enabled(context)) {
            val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                SearchHandoffService.request(pkg, query)
                if (start(context, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "search helper")) return
            }
        }
        for (action in listOf(Intent.ACTION_SEARCH, GMS_SEARCH)) {
            val intent = Intent(action).setPackage(pkg)
                .putExtra(SearchManager.QUERY, query)
                .putExtra("query", query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pm.resolveActivity(intent, 0) != null && start(context, intent, "intent $action")) return
        }
        val suggested = withContext(Dispatchers.IO) { fromSuggestions(context, pkg, query) }
        if (suggested != null && start(context, suggested, "suggestion")) return
        fallback(context, pkg, name, query)
    }

    /** The first suggestion's intent from the app's global-search provider, or null when it has none it lets us read. */
    private fun fromSuggestions(context: Context, pkg: String, query: String): Intent? {
        val sm = context.getSystemService(Context.SEARCH_SERVICE) as? SearchManager ?: return null
        val info = try {
            sm.searchablesInGlobalSearch.firstOrNull { it.searchActivity?.packageName == pkg }
        } catch (e: RuntimeException) {
            null
        } ?: return null
        val authority = info.suggestAuthority ?: return null
        val uri = Uri.Builder().scheme("content").authority(authority).apply {
            info.suggestPath?.let { appendEncodedPath(it) }
        }.appendPath(SearchManager.SUGGEST_URI_PATH_QUERY).appendPath(query)
            .appendQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT, "1").build()
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                fun col(name: String) = c.getColumnIndex(name).takeIf { it >= 0 }?.let { c.getString(it) }
                val action = col(SearchManager.SUGGEST_COLUMN_INTENT_ACTION) ?: info.suggestIntentAction ?: Intent.ACTION_VIEW
                var data = col(SearchManager.SUGGEST_COLUMN_INTENT_DATA) ?: info.suggestIntentData
                col(SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID)?.let { id -> data = (data ?: "") + "/" + Uri.encode(id) }
                Intent(action).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                    data?.let { setData(Uri.parse(it)) }
                    col(SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA)?.let { putExtra(SearchManager.EXTRA_DATA_KEY, it) }
                    putExtra(SearchManager.QUERY, query)
                }
            }
        } catch (e: Exception) {
            // Global-search providers are usually guarded by GLOBAL_SEARCH, which only the system holds.
            Log.i(TAG, "suggestion provider $authority not readable: $e")
            null
        }
    }

    private fun fallback(context: Context, pkg: String, name: String, query: String) {
        val pm = context.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText(query, query))
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SearchManager.QUERY, query)
            .putExtra("query", query)
        if (start(context, launch, "launch")) {
            Toast.makeText(context, context.getString(R.string.hero_copied, query, name), Toast.LENGTH_LONG).show()
        }
    }

    private fun start(context: Context, intent: Intent, how: String): Boolean = try {
        context.startActivity(intent)
        Log.i(TAG, "opened ${intent.`package`} by $how")
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
