package dev.framegen

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** Shell root yang dibiarkan terbuka untuk membaca frekuensi GPU/CPU dan suhu baterai. */
class RootStats {
    private var proc: Process? = null
    private var out: OutputStreamWriter? = null
    private var inp: BufferedReader? = null

    private val cmd = """
echo G1 `grep '\[GPU-OPP\]' /proc/gpufreqv2/gpufreq_status 2>/dev/null | head -1 | grep -o 'Freq: [0-9]*' | grep -o '[0-9]*'`
echo G2 `cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null`
echo G3 `cat /sys/class/devfreq/*gpu*/cur_freq /sys/class/devfreq/*mali*/cur_freq 2>/dev/null | head -1`
echo C `cat /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq 2>/dev/null | sort -n | tail -1`
echo T `cat /sys/class/power_supply/battery/temp 2>/dev/null`
echo __END__
""".trimIndent()

    @Synchronized
    private fun ensure(): Boolean {
        if (proc != null) return true
        return try {
            val p = Runtime.getRuntime().exec("su")
            proc = p
            out = OutputStreamWriter(p.outputStream)
            inp = BufferedReader(InputStreamReader(p.inputStream))
            true
        } catch (e: Exception) { false }
    }

    /** Hasil berupa teks siap tampil, atau null jika root tidak tersedia. */
    fun read(): String? {
        if (!ensure()) return null
        return try {
            val o = out!!
            val i = inp!!
            o.write(cmd + "\n")
            o.flush()
            var gpuHz = 0L
            var cpuKhz = 0L
            var tempRaw = 0L
            while (true) {
                val l = i.readLine() ?: run { close(); return null }
                if (l == "__END__") break
                val sp = l.trim().split(" ")
                val v = sp.getOrNull(1)?.toLongOrNull() ?: continue
                when (sp[0]) {
                    "G1" -> if (gpuHz == 0L) gpuHz = v * 1000
                    "G2", "G3" -> if (gpuHz == 0L) gpuHz = v
                    "C" -> cpuKhz = v
                    "T" -> tempRaw = v
                }
            }
            val parts = ArrayList<String>()
            if (gpuHz > 0) parts.add("GPU %d MHz".format(gpuHz / 1_000_000))
            if (cpuKhz > 0) parts.add("CPU %.2f GHz".format(cpuKhz / 1_000_000.0))
            if (tempRaw > 0) parts.add("%.0f°C".format(tempRaw / 10.0))
            parts.joinToString(" · ")
        } catch (e: Exception) { close(); null }
    }

    @Synchronized
    fun close() {
        runCatching { proc?.destroy() }
        proc = null; out = null; inp = null
    }
}
