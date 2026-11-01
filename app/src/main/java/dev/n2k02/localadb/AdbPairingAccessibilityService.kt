package dev.n2k02.localadb

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Optional and narrowly scoped: only read Android Settings' pairing-code dialog. */
class AdbPairingAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var previousSignature: Int? = null
    private val reader = object : Runnable {
        var remaining = 0
        override fun run() {
            if (readCode()) return
            if (remaining-- > 0) handler.postDelayed(this, 400L)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!AdbSettings.get(this, AdbSettings.Option.AUTO_PAIR) ||
            AdbEngine.status.value.pairingPort == null ||
            !PairingCodePolicy.isSystemSettings(event?.packageName?.toString())) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        handler.removeCallbacks(reader)
        reader.remaining = 12
        reader.run()
    }

    private fun readCode(): Boolean {
        if (!AdbSettings.get(this, AdbSettings.Option.AUTO_PAIR) ||
            AdbEngine.status.value.pairingPort == null) return true
        val root = rootInActiveWindow ?: return false
        if (!PairingCodePolicy.isSystemSettings(root.packageName?.toString())) return false
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        val labels = mutableListOf<String>()
        queue.add(root)
        var checked = 0
        while (queue.isNotEmpty() && checked++ < 100) {
            val node = queue.removeFirst()
            node.text?.toString()?.let(labels::add)
            node.contentDescription?.toString()?.let(labels::add)
            repeat(node.childCount.coerceAtMost(20)) { n -> node.getChild(n)?.let(queue::add) }
        }
        val code = PairingCodePolicy.extract(labels) ?: return false
        // Only the hash survives this event; never persist, display or log the pairing code.
        val signature = code.hashCode()
        if (signature != previousSignature) {
            previousSignature = signature
            AdbEngine.submitPairingCode(applicationContext, code)
        }
        return true
    }

    override fun onInterrupt() {
        handler.removeCallbacks(reader)
    }
    override fun onDestroy() {
        handler.removeCallbacks(reader)
        super.onDestroy()
    }
}
