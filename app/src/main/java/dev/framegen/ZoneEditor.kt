package dev.framegen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** Seret jari untuk menandai area HUD (maks 8). Koordinat 0..1 dari kiri-atas. */
class ZoneEditor(ctx: Context, initial: List<FloatArray>) : View(ctx) {
    val rects = ArrayList<FloatArray>(initial)
    private var cur: FloatArray? = null
    private var sx = 0f
    private var sy = 0f
    private val fill = Paint().apply { color = 0x5500E676 }
    private val line = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val dim = Paint().apply { color = 0x33000000 }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        for (r in rects + listOfNotNull(cur)) {
            val x0 = r[0] * width
            val y0 = r[1] * height
            val x1 = r[2] * width
            val y1 = r[3] * height
            c.drawRect(x0, y0, x1, y1, fill)
            c.drawRect(x0, y0, x1, y1, line)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = (e.x / width).coerceIn(0f, 1f)
        val y = (e.y / height).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { sx = x; sy = y; cur = floatArrayOf(x, y, x, y) }
            MotionEvent.ACTION_MOVE -> cur = floatArrayOf(minOf(sx, x), minOf(sy, y), maxOf(sx, x), maxOf(sy, y))
            MotionEvent.ACTION_UP -> {
                cur?.let { if (it[2] - it[0] > 0.02f && it[3] - it[1] > 0.02f && rects.size < 8) rects.add(it) }
                cur = null
            }
        }
        invalidate()
        return true
    }
}
