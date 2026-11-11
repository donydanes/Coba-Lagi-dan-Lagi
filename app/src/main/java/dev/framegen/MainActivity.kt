package dev.framegen

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    private val RQ_CAP = 2
    private val RQ_NOTIF = 3
    private lateinit var status: TextView
    private var auto = false // dibuka dari tile Quick Settings

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { f() }
        }.also { root.addView(it) }

        root.addView(TextView(this).apply { text = "Frame Gen 2x"; textSize = 26f })
        status = TextView(this).apply { setPadding(0, pad / 2, 0, 0) }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "1. Aktifkan Accessibility sekali saja (tombol root, atau lewat pengaturan)\n" +
                "2. Buka game dulu (harus ada di recent apps)\n" +
                "3. Tekan Mulai (atau tile FrameGen di Quick Settings), pilih \"Satu aplikasi\", lalu pilih game-nya\n" +
                "4. Kembali ke game. Ketuk bubble FG untuk panel kontrol.\n\n" +
                "Berhenti: tombol Stop di panel, tile, tombol di bawah, atau ketuk notifikasi."
            setPadding(0, pad / 2, 0, pad / 2)
        })
        btn("Aktifkan Accessibility (root)") { a11yRoot() }
        btn("Buka pengaturan Accessibility") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn("Mulai") { start() }
        btn("Berhenti") {
            startService(Intent(this, FrameGenService::class.java).setAction(FrameGenService.ACTION_STOP))
        }
        setContentView(ScrollView(this).apply { addView(root) })
        handleAuto(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuto(intent)
    }

    private fun handleAuto(i: Intent?) {
        if (i?.getBooleanExtra("autostart", false) == true) {
            i.removeExtra("autostart")
            auto = true
            start()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = "Accessibility: " + if (FgAccessibilityService.instance != null) "AKTIF" else "belum aktif"
    }

    private fun su(cmd: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return out
    }

    private fun a11yRoot() {
        Thread {
            val ok = try {
                val c = ComponentName(this, FgAccessibilityService::class.java).flattenToString()
                val cur = su("settings get secure enabled_accessibility_services")
                if (!cur.split(":").contains(c)) {
                    val nv = if (cur.isEmpty() || cur == "null") c else "$cur:$c"
                    su("settings put secure enabled_accessibility_services $nv")
                }
                su("settings put secure accessibility_enabled 1")
                true
            } catch (e: Exception) { false }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "Perintah root terkirim." else "Gagal (butuh root). Aktifkan manual lewat pengaturan Accessibility.",
                    Toast.LENGTH_LONG
                ).show()
                Handler(Looper.getMainLooper()).postDelayed({ refreshStatus() }, 1500)
            }
        }.start()
    }

    private fun start() {
        if (FgAccessibilityService.instance == null) {
            Toast.makeText(this, "Aktifkan Accessibility FrameGen dulu.", Toast.LENGTH_LONG).show()
            auto = false
            return
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), RQ_NOTIF)
            return
        }
        launchCapture()
    }

    private fun launchCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), RQ_CAP)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != RQ_CAP) return
        if (res == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, FrameGenService::class.java)
                    .putExtra("code", res)
                    .putExtra("data", data)
            )
            if (auto) finish() else moveTaskToBack(true)
        } else if (auto) {
            finish()
        }
        auto = false
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (r == RQ_NOTIF) launchCapture()
    }
}
