package dev.framegen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Hanya dipakai sebagai "pemilik" jendela overlay bertipe TYPE_ACCESSIBILITY_OVERLAY
 * (dianggap trusted oleh Android, sentuhan tembus ke game). Tidak membaca layar/input.
 */
class FgAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: FgAccessibilityService? = null }

    val wm: WindowManager get() = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        runCatching { startService(Intent(this, FrameGenService::class.java).setAction(FrameGenService.ACTION_STOP)) }
        return super.onUnbind(intent)
    }
}
