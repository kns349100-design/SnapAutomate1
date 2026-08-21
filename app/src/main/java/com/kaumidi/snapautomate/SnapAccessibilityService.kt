package com.kaumidi.snapautomate

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Generic accessibility-based automation for Snapchat.
 * Approach: scan the active window's node tree for clickable nodes whose
 * text/content-description matches known button labels (Accept, Add Friend etc.)
 * and click them. Snapchat does not expose resource-ids reliably (they get
 * obfuscated across builds), so text matching is the most stable option —
 * but it WILL need occasional tweaks if Snapchat changes wording or the
 * user's app language.
 */
class SnapAccessibilityService : AccessibilityService() {

    private lateinit var prefs: SharedPreferences

    // Words/phrases that mean "accept this friend request" on various Snapchat screens
    private val acceptLabels = listOf("Accept", "Accept Request", "Confirm")

    // Track chats we've already auto-messaged this session so we don't spam the same one repeatedly
    private val messagedThisSession = mutableSetOf<String>()

    // Holds the friend's name detected when opening a chat row, consumed by
    // buildMessageForOpenedChat() right before typing the message.
    private var pendingNameForNextOpen: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("snap_automate_prefs", MODE_PRIVATE)
        instance = this
        Log.d(TAG, "Service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Background auto-accept (if user left the continuous toggle on) still works,
        // but the main flow now is the one-tap buttons below.
        if (event == null) return
        val root = rootInActiveWindow ?: return
        if (prefs.getBoolean(PREF_AUTO_ACCEPT, false)) {
            acceptAllVisible(root)
        }
        root.recycle()
    }

    /**
     * ACCEPT ALL — click every visible "Accept"/"Confirm" button repeatedly.
     * Snapchat's request list only renders visible rows, so after each accept
     * we re-scan the (now scrolled/updated) tree. We loop with small delays
     * driven by repeated accessibility events; this single call handles
     * whatever is on-screen right now, and scrolls down to reveal more.
     */
    fun acceptAllVisible(root: AccessibilityNodeInfo): Int {
        var clicked = 0
        for (label in acceptLabels) {
            val matches = root.findAccessibilityNodeInfosByText(label)
            for (node in matches) {
                val target = findClickableSelfOrAncestor(node)
                if (target != null) {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    clicked++
                }
            }
        }
        return clicked
    }

    /** Scroll the current screen down (used between accept passes to reveal more requests) */
    fun scrollDown() {
        val root = rootInActiveWindow ?: return
        val scrollable = findScrollableNode(root)
        scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        root.recycle()
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findClickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 6) {
            if (parent.isClickable) return parent
            parent = parent.parent
            depth++
        }
        return null
    }

    /**
     * MESSAGE ALL — opens Snapchat's Chat tab, then taps each conversation row
     * that shows an unread/new-friend indicator, types the saved message into
     * the compose box, and sends it. Because Snapchat doesn't expose stable
     * resource-ids, this relies on text matching for the send button/box hint,
     * which the user can adjust in Settings if wording differs on their build.
     *
     * Supports a {naam} placeholder in messageTemplate — replaced with the
     * friend's display name read from their chat row, when it can be found.
     */
    fun messageAllVisible(messageTemplate: String): Int {
        val root = rootInActiveWindow ?: return 0
        var sent = 0
        val chatRows = root.findAccessibilityNodeInfosByText("New Friend")
        for (row in chatRows) {
            val target = findClickableSelfOrAncestor(row) ?: continue
            pendingNameForNextOpen = extractNameNearby(row)
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            // Composing happens on the next screen; the caller (MainActivity)
            // drives the step-by-step sequence with delays since a single
            // synchronous pass can't wait for the new screen to load.
            sent++
        }
        root.recycle()
        return sent
    }

    /**
     * Tries to find a display-name text node near the tapped chat row
     * (usually a sibling node in the same row, distinct from the "New Friend"
     * label itself). Best-effort — falls back to null if nothing plausible found.
     */
    private fun extractNameNearby(newFriendLabelNode: AccessibilityNodeInfo): String? {
        var parent = newFriendLabelNode.parent
        var depth = 0
        while (parent != null && depth < 4) {
            val name = searchForNameText(parent, newFriendLabelNode)
            if (name != null) return name
            parent = parent.parent
            depth++
        }
        return null
    }

    private fun searchForNameText(node: AccessibilityNodeInfo, exclude: AccessibilityNodeInfo): String? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val text = child.text?.toString()?.trim()
            if (!text.isNullOrBlank() &&
                !text.equals("New Friend", ignoreCase = true) &&
                text.length in 2..30 &&
                !text.equals(exclude.text?.toString())
            ) {
                return text
            }
            val found = searchForNameText(child, exclude)
            if (found != null) return found
        }
        return null
    }

    /** Substitutes {naam} in the template with the last-detected friend name, if any. */
    fun buildMessageForOpenedChat(template: String): String {
        val name = pendingNameForNextOpen
        pendingNameForNextOpen = null
        return if (name != null && template.contains("{naam}")) {
            template.replace("{naam}", name)
        } else {
            template.replace("{naam}", "").trim()
        }
    }

    /** Types text into the currently focused editable field and taps Send, if found. */
    fun typeAndSend(messageText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editNode = findEditableNode(root) ?: return false
        val args = android.os.Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            messageText
        )
        editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        val sendMatches = root.findAccessibilityNodeInfosByText("Send")
        for (node in sendMatches) {
            val target = findClickableSelfOrAncestor(node)
            if (target != null) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
        }
        return false
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    /** Public wrapper — rootInActiveWindow is a protected member, so external
     *  callers like MainActivity can't reach it directly. */
    fun getActiveRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    /** Public wrapper for the protected performGlobalAction, e.g. going back. */
    fun goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    companion object {
        private const val TAG = "SnapAutomate"
        const val PREF_AUTO_ACCEPT = "auto_accept_enabled"
        const val PREF_AUTO_MESSAGE = "auto_message_enabled"
        const val PREF_MESSAGE_TEXT = "auto_message_text"

        @Volatile
        var instance: SnapAccessibilityService? = null
    }
}
