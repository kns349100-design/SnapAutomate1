package com.kaumidi.snapautomate

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SnapAccessibilityService : AccessibilityService() {

    private lateinit var prefs: SharedPreferences
    private var lastClickAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName != SNAPCHAT_PACKAGE) return
        if (!::prefs.isInitialized || !prefs.getBoolean(PREF_AUTO_ACCEPT, false)) return

        // Small debounce prevents the same accessibility update from clicking
        // repeatedly while Snapchat is redrawing the screen.
        val now = SystemClock.uptimeMillis()
        if (now - lastClickAt < CLICK_COOLDOWN_MS) return

        rootInActiveWindow?.let { root ->
            if (clickFirstAccept(root)) {
                lastClickAt = now
            }
            root.recycle()
        }
    }

    fun acceptVisible(): Int {
        val root = rootInActiveWindow ?: return 0
        var count = 0
        try {
            // Limit work to one click per scan; Snapchat normally redraws after
            // the click and emits another accessibility event.
            if (clickFirstAccept(root)) count = 1
        } finally {
            root.recycle()
        }
        return count
    }

    private fun clickFirstAccept(root: AccessibilityNodeInfo): Boolean {
        val labels = arrayOf("Accept", "Accept Request")

        for (label in labels) {
            val matches = root.findAccessibilityNodeInfosByText(label)
            for (node in matches) {
                if (!node.isVisibleToUser || !node.isEnabled) continue

                val target = findClickableSelfOrAncestor(node) ?: continue
                val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) return true
            }
        }
        return false
    }

    private fun findClickableSelfOrAncestor(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        if (node.isClickable && node.isEnabled && node.isVisibleToUser) return node

        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 8) {
            if (parent.isClickable && parent.isEnabled && parent.isVisibleToUser) {
                return parent
            }
            parent = parent.parent
            depth++
        }
        return null
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SnapAutomate"
        private const val PREFS = "snap_automate_prefs"
        const val PREF_AUTO_ACCEPT = "auto_accept_enabled"
        private const val SNAPCHAT_PACKAGE = "com.snapchat.android"
        private const val CLICK_COOLDOWN_MS = 900L

        @Volatile
        var instance: SnapAccessibilityService? = null
    }
}
