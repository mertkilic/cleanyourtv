package com.gathin.tvhome.hero

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * OK on a carousel slide: [TiviMateHandoff] opens TiviMate and asks this helper to search for the title. Reading
 * TiviMate's screens (the same tree UI Automator dumps), it walks to Search and puts the title in the field:
 *
 * 1. the search field is on screen: set the text (results follow as it changes) and press the keyboard's Enter;
 * 2. else the side menu's Search item is in the tree (it is even behind the player): click it;
 * 3. else (still the full-screen player): Back, which opens the TV guide and with it the menu.
 *
 * At most [MAX_ACTIONS] clicks or Backs, and only for [TIMEOUT_MS] after the request; after that, or once the text is
 * in, it does nothing until the next OK. Events come from TiviMate only (search_handoff_service.xml).
 */
class SearchHandoffService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            step()
            if (pending != null) main.postDelayed(this, POLL_MS)
        }
    }

    override fun onServiceConnected() {
        instance = this
        if (pending != null) kick()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val req = pending ?: return
        if (event?.packageName?.toString() == req.pkg) step()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun kick() {
        main.removeCallbacks(poll)
        main.postDelayed(poll, POLL_MS)
    }

    private fun step() {
        val req = pending ?: return
        val now = SystemClock.uptimeMillis()
        if (now - req.startedAt > TIMEOUT_MS) return finish("timed out")
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != req.pkg) return

        findField(root, req.pkg)?.let { field ->
            if (field.text?.toString() != req.query) {
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, req.query)
                }
                if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return
            }
            // Leanback's search bar searches as the text changes; Enter submits it and hides the keyboard.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            return finish("searched")
        }

        // One action at a time, with room for TiviMate to change screens before the next.
        if (now - req.lastActionAt < SETTLE_MS) return
        if (req.actions >= MAX_ACTIONS) return finish("gave up after ${req.actions} steps")
        val entry = if (req.clicks < 2) findSearchEntry(root, req.pkg) else null
        if (entry != null && entry.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            req.clicks++
        } else if (req.backs < 2) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            req.backs++
        } else {
            return finish("no Search on screen")
        }
        req.actions++
        req.lastActionAt = now
    }

    private fun finish(why: String) {
        Log.i(TAG, "search handoff: $why")
        pending = null
        main.removeCallbacks(poll)
    }

    internal class Request(val pkg: String, val query: String) {
        val startedAt = SystemClock.uptimeMillis()
        var lastActionAt = 0L
        var actions = 0
        var clicks = 0
        var backs = 0
    }

    companion object {
        private const val TAG = "SearchHandoff"
        private const val TIMEOUT_MS = 15_000L
        private const val POLL_MS = 400L
        private const val SETTLE_MS = 900L
        private const val MAX_ACTIONS = 5

        /**
         * TiviMate 5.1.5's view ids (uiautomator dump on a BRAVIA AE2): the side menu's Search item and the search
         * bar's field. They are obfuscated and may change with an update, so a label or an editable field is the
         * fallback.
         */
        private val SEARCH_ENTRY_IDS = mapOf("ar.tvplayer.tv" to "ar.tvplayer.tv:id/fn")
        private val FIELD_IDS = mapOf("ar.tvplayer.tv" to "ar.tvplayer.tv:id/4n0")
        private val SEARCH_LABELS = setOf("search", "ara", "suche", "buscar", "recherche", "cerca", "zoeken", "поиск")

        @Volatile
        private var instance: SearchHandoffService? = null

        @Volatile
        private var pending: Request? = null

        /** Whether the owner switched this helper on in Accessibility settings. */
        fun enabled(context: Context): Boolean {
            val me = ComponentName(context, SearchHandoffService::class.java)
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Search [query] in [pkg] once it is in front (call right before starting it). */
        fun request(pkg: String, query: String) {
            pending = Request(pkg, query)
            instance?.kick()
        }

        internal fun findField(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
            FIELD_IDS[pkg]?.let { id ->
                root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }?.let { return it }
            }
            return firstOrNull(root) { it.isEditable && it.isVisibleToUser }
        }

        internal fun findSearchEntry(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
            SEARCH_ENTRY_IDS[pkg]?.let { id ->
                root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isClickable }?.let { return it }
            }
            val label = firstOrNull(root) { n ->
                n.text?.toString()?.trim()?.lowercase() in SEARCH_LABELS
            } ?: return null
            var n: AccessibilityNodeInfo? = label
            while (n != null && !n.isClickable) n = n.parent
            return n
        }

        private fun firstOrNull(root: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var seen = 0
            while (queue.isNotEmpty() && seen++ < 800) {
                val n = queue.removeFirst()
                if (match(n)) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
            return null
        }
    }
}
