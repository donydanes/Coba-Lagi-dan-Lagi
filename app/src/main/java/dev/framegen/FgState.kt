package dev.framegen

import android.content.Context

/** Status bersama panel overlay dan thread render; pengaturan disimpan ke SharedPreferences. */
object FgState {
    @Volatile var enabled = true
    @Volatile var force = false
    @Volatile var scale = 1.0f
    @Volatile var mult = 2
    @Volatile var contentW = 1080
    @Volatile var contentH = 2400
    @Volatile var baseFps = 0f
    @Volatile var outFps = 0f
    @Volatile var hz = 0f
    /** Zona HUD: x0,y0,x1,y1 per zona (koordinat 0..1, y dari bawah), terpisah untuk landscape/portrait. */
    @Volatile var zonesL = FloatArray(0)
    @Volatile var zonesP = FloatArray(0)

    private fun dec(s: String?): FloatArray =
        if (s.isNullOrBlank()) FloatArray(0) else s.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()

    fun load(c: Context) {
        val p = c.getSharedPreferences("fg", Context.MODE_PRIVATE)
        scale = p.getFloat("scale", 1f)
        force = p.getBoolean("force", false)
        mult = p.getInt("mult", 2)
        zonesL = dec(p.getString("zl", ""))
        zonesP = dec(p.getString("zp", ""))
    }

    fun save(c: Context) {
        c.getSharedPreferences("fg", Context.MODE_PRIVATE).edit()
            .putFloat("scale", scale).putBoolean("force", force).putInt("mult", mult)
            .putString("zl", zonesL.joinToString(",")).putString("zp", zonesP.joinToString(","))
            .apply()
    }
}
