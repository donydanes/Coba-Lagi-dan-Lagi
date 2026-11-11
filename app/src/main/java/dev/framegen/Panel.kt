package dev.framegen

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs

/** Bubble "FG" yang bisa digeser; ketuk untuk membuka panel kontrol. */
class Panel(private val ctx: Context, private val wm: WindowManager) {
    private val main = Handler(Looper.getMainLooper())
    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun bg(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(12), dp(10), dp(12), dp(12))
        background = bg(0xEE15161B.toInt(), 14)
    }
    private val stats = TextView(ctx)
    private val scaleChips = ArrayList<TextView>()
    private val multChips = ArrayList<TextView>()
    private var hw = ""
    private var ht: HandlerThread? = null
    private var bg: Handler? = null
    private var shell: RootStats? = null
    private var attached = false
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    private fun chip(t: String, onClick: (TextView) -> Unit): TextView = TextView(ctx).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = bg(0xFF3A3A44.toInt(), 8)
        setOnClickListener { onClick(this) }
    }

    private fun label(s: Float) = when (s) { 0.5f -> "0.5x"; 0.75f -> "0.75x"; else -> "1x" }
    private fun modeText() = if (FgState.force) "Mode: Paksa" else "Mode: Auto"

    private fun refreshChips() {
        for (c in scaleChips) {
            val sel = (c.tag as Float) == FgState.scale
            c.background = bg(if (sel) 0xFF4C8DFF.toInt() else 0xFF3A3A44.toInt(), 8)
        }
        for (c in multChips) {
            val sel = (c.tag as Int) == FgState.mult
            c.background = bg(if (sel) 0xFF4C8DFF.toInt() else 0xFF3A3A44.toInt(), 8)
        }
    }

    private fun toggle() {
        val open = body.visibility != View.VISIBLE
        body.visibility = if (open) View.VISIBLE else View.GONE
        if (open) startHw() else stopHw()
    }

    private fun build() {
        val bubble = TextView(ctx).apply {
            text = "FG"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC4C8DFF.toInt()) }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        bubble.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) { lp.x = sx + dx; lp.y = sy + dy; wm.updateViewLayout(root, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) toggle(); true }
                else -> false
            }
        }
        root.addView(bubble)

        stats.setTextColor(Color.WHITE)
        stats.textSize = 13f
        body.addView(stats)

        val sw = Switch(ctx).apply {
            text = "Frame Gen"
            setTextColor(Color.WHITE)
            isChecked = FgState.enabled
            setOnCheckedChangeListener { _, c -> FgState.enabled = c }
        }
        body.addView(sw)

        body.addView(TextView(ctx).apply {
            text = "Resolusi proses"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        })
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        for (s in floatArrayOf(0.5f, 0.75f, 1.0f)) {
            val c = chip(label(s)) { FgState.scale = s; FgState.save(ctx); refreshChips() }
            c.tag = s
            scaleChips.add(c)
            r.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        body.addView(r)

        body.addView(TextView(ctx).apply {
            text = "Pengali fps"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        })
        val mrow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        for (m in 2..4) {
            val c = chip("${m}x") { FgState.mult = m; FgState.save(ctx); refreshChips() }
            c.tag = m
            multChips.add(c)
            mrow.addView(c, cell())
        }
        body.addView(mrow)

        val r2 = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        r2.addView(chip(modeText()) { FgState.force = !FgState.force; FgState.save(ctx); it.text = modeText() }, cell())
        r2.addView(chip("Zona HUD") { openZones() }, cell())
        body.addView(r2)
        val stopLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
        body.addView(chip("Stop") {
            ctx.startService(Intent(ctx, FrameGenService::class.java).setAction(FrameGenService.ACTION_STOP))
        }, stopLp)

        root.addView(body, LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT))
        refreshChips()
    }

    private val tick = object : Runnable {
        override fun run() {
            val b = FgState.baseFps
            val head = if (b <= 0f) "Menunggu frame…"
            else "Game %.0f fps → %.0f fps\nLayar %.0f Hz".format(b, FgState.outFps, FgState.hz)
            stats.text = if (hw.isEmpty()) head else head + "\n" + hw
            main.postDelayed(this, 500)
        }
    }

    private fun cell() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(dp(2), 0, dp(2), 0)
    }

    private fun startHw() {
        if (ht != null) return
        val s = RootStats()
        shell = s
        val t = HandlerThread("fg-hw").also { it.start() }
        ht = t
        val h = Handler(t.looper)
        bg = h
        h.post(object : Runnable {
            override fun run() {
                val r = s.read()
                main.post { hw = r ?: "" }
                h.postDelayed(this, 1000)
            }
        })
    }

    private fun stopHw() {
        bg?.removeCallbacksAndMessages(null)
        ht?.quitSafely()
        ht = null; bg = null
        shell?.close(); shell = null
    }

    /** Layar penuh untuk menggambar kotak di atas HUD (joystick, tombol, minimap, skor). */
    private fun openZones() {
        val land = FgState.contentW > FgState.contentH
        val src = if (land) FgState.zonesL else FgState.zonesP
        val init = ArrayList<FloatArray>()
        for (i in 0 until src.size / 4) {
            init.add(floatArrayOf(src[i * 4], 1f - src[i * 4 + 3], src[i * 4 + 2], 1f - src[i * 4 + 1]))
        }
        val ed = ZoneEditor(ctx, init)
        val frame = FrameLayout(ctx)
        frame.addView(ed, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun close() { runCatching { wm.removeView(frame) } }
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val m = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(4), 0, dp(4), 0) }
        bar.addView(TextView(ctx).apply { text = "Gambar kotak di HUD"; setTextColor(Color.WHITE); textSize = 13f }, m)
        bar.addView(chip("Simpan") {
            val out = FloatArray(ed.rects.size * 4)
            ed.rects.forEachIndexed { i, r ->
                out[i * 4] = r[0]; out[i * 4 + 1] = 1f - r[3]; out[i * 4 + 2] = r[2]; out[i * 4 + 3] = 1f - r[1]
            }
            if (land) FgState.zonesL = out else FgState.zonesP = out
            FgState.save(ctx)
            close()
        }, m)
        bar.addView(chip("Undo") {
            if (ed.rects.isNotEmpty()) { ed.rects.removeAt(ed.rects.size - 1); ed.invalidate() }
        }, m)
        bar.addView(chip("Hapus semua") { ed.rects.clear(); ed.invalidate() }, m)
        bar.addView(chip("Batal") { close() }, m)
        frame.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(16) })
        val flp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        flp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        body.visibility = View.GONE
        stopHw()
        wm.addView(frame, flp)
    }

    fun show() {
        build()
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(12)
        lp.y = dp(120)
        wm.addView(root, lp)
        attached = true
        tick.run()
    }

    fun hide() {
        main.removeCallbacksAndMessages(null)
        stopHw()
        if (attached) runCatching { wm.removeView(root) }
        attached = false
    }
}
