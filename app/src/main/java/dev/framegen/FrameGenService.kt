package dev.framegen

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.opengl.*
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.Toast
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

class FrameGenService : Service() {
    companion object {
        const val ACTION_STOP = "dev.framegen.STOP"
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var overlay: SurfaceView? = null
    private var panel: Panel? = null
    private var renderer: Renderer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        startForegroundNotif()
        val data = i?.getParcelableExtra("data", Intent::class.java)
        if (i == null || data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            begin(i.getIntExtra("code", 0), data)
            running = true
            refreshTile()
        } catch (e: Throwable) {
            toast("Gagal mulai: " + e.message)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fg", "Frame Gen", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, FrameGenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "fg")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Frame Gen aktif")
            .setContentText("Ketuk untuk berhenti")
            .setContentIntent(stop)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun begin(code: Int, data: Intent) {
        FgState.load(this)
        val a11y = FgAccessibilityService.instance
            ?: throw IllegalStateException("Aktifkan Accessibility FrameGen dulu")
        val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
            override fun onCapturedContentResize(width: Int, height: Int) {
                if (width > 0 && height > 0) { FgState.contentW = width; FgState.contentH = height }
            }
        }, main)

        val b = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        FgState.contentW = b.width()
        FgState.contentH = b.height()
        val dpi = resources.displayMetrics.densityDpi

        val sv = SurfaceView(this)
        sv.setZOrderOnTop(true)
        sv.holder.setFormat(PixelFormat.TRANSLUCENT)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                renderer?.quit()
                val r = Renderer(this@FrameGenService, h.surface, mp, dpi) { msg ->
                    main.post { toast(msg); stopSelf() }
                }
                renderer = r
                r.start()
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { renderer?.quit(); renderer = null }
        })
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        a11y.wm.addView(sv, lp)
        overlay = sv

        // panel ditambah setelah overlay supaya berada di atasnya
        panel = Panel(a11y, a11y.wm).also { it.show() }
    }

    override fun onDestroy() {
        running = false
        refreshTile()
        panel?.hide(); panel = null
        overlay?.let { v -> runCatching { FgAccessibilityService.instance?.wm?.removeView(v) } }
        overlay = null
        renderer?.quit(); renderer = null
        projection?.stop(); projection = null
        super.onDestroy()
    }

    private fun refreshTile() {
        runCatching {
            android.service.quicksettings.TileService.requestListeningState(
                this, android.content.ComponentName(this, FgTileService::class.java)
            )
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}

// ---------------------------------------------------------------- util
private fun snap(x: Float) = maxOf(16, (x / 16).toInt() * 16)
private fun wantSize() = intArrayOf(snap(FgState.contentW * FgState.scale), snap(FgState.contentH * FgState.scale))
private fun mvDim(x: Int, k: Float) = maxOf(1, ceil(x / (16f * k)).toInt())
private fun mvDimF(x: Int, k: Float) = maxOf(1, ceil(x / (8f * k)).toInt())

// ---------------------------------------------------------------- shaders
private const val VS = """#version 300 es
out vec2 vUv;
void main(){
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  vUv = p;
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}"""

private const val FS_COPY = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uOes;
uniform mat4 uTex;
in vec2 vUv; out vec4 o;
void main(){ o = vec4(texture(uOes, (uTex * vec4(vUv, 0.0, 1.0)).xy).rgb, 1.0); }"""

private const val FS_BLIT = """#version 300 es
precision mediump float;
uniform sampler2D uTex;
in vec2 vUv; out vec4 o;
void main(){ o = vec4(texture(uTex, vUv).rgb, 1.0); }"""

// Block matching. Blok = 16*uK piksel, langkah cari = 2*uK piksel, jangkauan +-8*uK piksel.
private const val FS_ME = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform ivec2 uSize;
uniform float uK;
out vec4 o;
ivec2 cl(ivec2 p){ return clamp(p, ivec2(0), uSize - 1); }
float lum(sampler2D s, ivec2 p){ return dot(texelFetch(s, cl(p), 0).rgb, vec3(0.299, 0.587, 0.114)); }
ivec2 offs(int k){ return ivec2(vec2(float((k & 3) * 4 + 2), float((k >> 2) * 4 + 2)) * uK); }
void main(){
  float B = 16.0 * uK;
  ivec2 ob = ivec2(vec2(ivec2(gl_FragCoord.xy)) * B);
  float c[16];
  for(int k = 0; k < 16; k++){ c[k] = lum(uCur, ob + offs(k)); }
  float best = 1e9;
  ivec2 bd = ivec2(0);
  for(int dy = -4; dy <= 4; dy++){
    for(int dx = -4; dx <= 4; dx++){
      ivec2 d = ivec2(vec2(float(dx), float(dy)) * 2.0 * uK);
      float s = 0.0;
      for(int k = 0; k < 16; k++){ s += abs(c[k] - lum(uPrev, ob + offs(k) + d)); }
      s += 0.01 * float(abs(dx) + abs(dy));
      if(s < best){ best = s; bd = d; }
    }
  }
  o = vec4(vec2(bd) / B + 0.5, clamp(best / 16.0, 0.0, 1.0), 1.0);
}"""

// Penyempurnaan: blok 8*K px, mulai dari vektor kasar, langkah 0.5*K px (sub-piksel lewat filter linear).
private const val FS_ME_B = """#version 300 es
precision highp float;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform sampler2D uMvA;
uniform vec2 uSize;
uniform float uK;
out vec4 o;
float lumF(sampler2D s, vec2 p){ return dot(texture(s, (p + 0.5) / uSize).rgb, vec3(0.299, 0.587, 0.114)); }
vec2 offs(int k){ return vec2(float((k & 3) * 2 + 1), float((k >> 2) * 2 + 1)) * uK; }
void main(){
  ivec2 bi = ivec2(gl_FragCoord.xy);
  vec2 ob = vec2(bi) * 8.0 * uK;
  ivec2 pa = clamp(bi / 2, ivec2(0), textureSize(uMvA, 0) - 1);
  vec2 par = (texelFetch(uMvA, pa, 0).rg - 0.5) * 16.0 * uK;
  float c[16];
  for(int k = 0; k < 16; k++){ c[k] = lumF(uCur, ob + offs(k)); }
  float best = 1e9;
  vec2 bd = par;
  for(int dy = -3; dy <= 3; dy++){
    for(int dx = -3; dx <= 3; dx++){
      vec2 d = par + vec2(float(dx), float(dy)) * 0.5 * uK;
      float s = 0.0;
      for(int k = 0; k < 16; k++){ s += abs(c[k] - lumF(uPrev, ob + offs(k) + d)); }
      s += 0.004 * float(abs(dx) + abs(dy));
      if(s < best){ best = s; bd = d; }
    }
  }
  o = vec4(bd / (24.0 * uK) + 0.5, clamp(best / 16.0, 0.0, 1.0), 1.0);
}"""

// Frame perantara: vektor gerak dihaluskan (filter linear), zona HUD diambil apa adanya dari frame asli.
private const val FS_INTERP = """#version 300 es
precision highp float;
uniform sampler2D uCur;
uniform sampler2D uPrev;
uniform sampler2D uMv;
uniform vec2 uSize;
uniform float uT;
uniform float uK;
uniform vec4 uZ[8];
uniform int uZn;
in vec2 vUv; out vec4 o;
void main(){
  vec3 fb = texture(uCur, vUv).rgb;
  for(int i = 0; i < 8; i++){
    if(i < uZn){
      vec4 z = uZ[i];
      if(vUv.x >= z.x && vUv.x <= z.z && vUv.y >= z.y && vUv.y <= z.w){ o = vec4(fb, 1.0); return; }
    }
  }
  vec2 muv = vUv * uSize / (8.0 * uK * vec2(textureSize(uMv, 0)));
  vec4 m = texture(uMv, muv);
  vec2 d = (m.rg - 0.5) * 24.0 * uK / uSize;
  vec3 a = texture(uPrev, vUv + d * uT).rgb;
  vec3 c = texture(uCur, vUv - d * (1.0 - uT)).rgb;
  float occ = max(smoothstep(0.10, 0.30, length(a - c)), smoothstep(0.12, 0.35, m.b));
  o = vec4(mix(mix(a, c, uT), fb, occ), 1.0);
}"""

// ---------------------------------------------------------------- renderer
private class Tgt(val w: Int, val h: Int, filter: Int) {
    val tex: Int
    val fbo: Int
    init {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        tex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
    }
    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
    }
}

private class Renderer(
    val ctx: Context, val surface: Surface, val mp: MediaProjection,
    val dpi: Int, val onFail: (String) -> Unit
) : Thread("fg-render") {
    @Volatile private var alive = true
    private val q = LinkedBlockingQueue<Long>()

    fun quit() {
        alive = false
        q.offer(0L)
        if (currentThread() !== this) join(1500)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Shader gagal: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    private fun prog(fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, VS))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("Link gagal: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    override fun run() {
        var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var ec: EGLContext = EGL14.EGL_NO_CONTEXT
        var es: EGLSurface = EGL14.EGL_NO_SURFACE
        var vd: VirtualDisplay? = null
        var st: SurfaceTexture? = null
        var cs: Surface? = null
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            EGL14.eglInitialize(dpy, ver, 0, ver, 1)
            val cfgAttr = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, 0x40, // EGL_OPENGL_ES3_BIT_KHR
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfgs, 0, 1, num, 0)
            val cfg = cfgs[0] ?: throw RuntimeException("EGL config OpenGL ES 3 tidak ada")
            ec = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            es = EGL14.eglCreateWindowSurface(dpy, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(dpy, es, es, ec)
            EGL14.eglSwapInterval(dpy, 1)

            val pCopy = prog(FS_COPY)
            val pMe = prog(FS_ME)
            val pMeB = prog(FS_ME_B)
            val pInt = prog(FS_INTERP)
            val pBlit = prog(FS_BLIT)

            val ot = IntArray(1)
            GLES30.glGenTextures(1, ot, 0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ot[0])
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            val w0 = wantSize()
            var cw = w0[0]
            var ch = w0[1]
            var scaleUsed = FgState.scale
            var k = scaleUsed / 0.5f

            val stl = SurfaceTexture(ot[0])
            st = stl
            stl.setDefaultBufferSize(cw, ch)
            stl.setOnFrameAvailableListener { q.offer(1L) }
            val capSurface = Surface(stl)
            cs = capSurface

            var prev = Tgt(cw, ch, GLES30.GL_LINEAR)
            var cur = Tgt(cw, ch, GLES30.GL_LINEAR)
            var mv = Tgt(mvDim(cw, k), mvDim(ch, k), GLES30.GL_NEAREST)
            var mvB = Tgt(mvDimF(cw, k), mvDimF(ch, k), GLES30.GL_LINEAR)

            // createVirtualDisplay hanya boleh sekali per MediaProjection (Android 14+); ukuran diubah lewat resize()
            val vdl = mp.createVirtualDisplay(
                "FrameGen", cw, ch, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, capSurface, null, null
            )
            vd = vdl

            val dm = ctx.getSystemService(DisplayManager::class.java)
            var hz = dm.getDisplay(Display.DEFAULT_DISPLAY).refreshRate
            val tm = FloatArray(16)
            val sz = IntArray(2)
            var lastTs = 0L
            var ema = 0.0
            var frames = 0
            var gen = true
            var shown = false

            fun tex(unit: Int, t: Int) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            }
            fun loc(p: Int, n: String) = GLES30.glGetUniformLocation(p, n)
            fun target(fbo: Int, w: Int, h: Int) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                GLES30.glViewport(0, 0, w, h)
            }
            fun screen() {
                EGL14.eglQuerySurface(dpy, es, EGL14.EGL_WIDTH, sz, 0)
                EGL14.eglQuerySurface(dpy, es, EGL14.EGL_HEIGHT, sz, 1)
                target(0, sz[0], sz[1])
            }
            fun swap() { if (!EGL14.eglSwapBuffers(dpy, es)) alive = false }
            fun draw() = GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            fun blit(t: Tgt) {
                screen()
                GLES30.glUseProgram(pBlit)
                tex(0, t.tex)
                GLES30.glUniform1i(loc(pBlit, "uTex"), 0)
                draw()
            }

            while (alive) {
                if (q.poll(200, TimeUnit.MILLISECONDS) == null) { FgState.baseFps = 0f; continue }
                if (!alive) break
                var n = 1
                while (q.poll() != null) n++
                repeat(n) { stl.updateTexImage() }
                stl.getTransformMatrix(tm)
                val ts = stl.timestamp
                var dt = if (lastTs == 0L) 0.0 else (ts - lastTs) / 1e9 / n
                lastTs = ts

                // ukuran game / resolusi proses berubah -> alokasi ulang tanpa membuat VirtualDisplay baru
                val want = wantSize()
                if (want[0] != cw || want[1] != ch || FgState.scale != scaleUsed) {
                    scaleUsed = FgState.scale
                    k = scaleUsed / 0.5f
                    cw = want[0]; ch = want[1]
                    prev.release(); cur.release(); mv.release(); mvB.release()
                    prev = Tgt(cw, ch, GLES30.GL_LINEAR)
                    cur = Tgt(cw, ch, GLES30.GL_LINEAR)
                    mv = Tgt(mvDim(cw, k), mvDim(ch, k), GLES30.GL_NEAREST)
                    mvB = Tgt(mvDimF(cw, k), mvDimF(ch, k), GLES30.GL_LINEAR)
                    stl.setDefaultBufferSize(cw, ch)
                    vdl.resize(cw, ch, dpi)
                    frames = 0; lastTs = 0L; ema = 0.0; dt = 0.0
                }

                // frame baru -> cur, frame lama -> prev
                val tmp = prev; prev = cur; cur = tmp
                target(cur.fbo, cw, ch)
                GLES30.glUseProgram(pCopy)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ot[0])
                GLES30.glUniform1i(loc(pCopy, "uOes"), 0)
                GLES30.glUniformMatrix4fv(loc(pCopy, "uTex"), 1, false, tm, 0)
                draw()

                frames++
                if (frames % 120 == 0) hz = dm.getDisplay(Display.DEFAULT_DISPLAY).refreshRate
                if (hz < 1f) hz = 60f
                if (dt > 0) ema = if (ema == 0.0 || dt > 0.1) dt else ema * 0.9 + dt * 0.1
                // auto: game sudah >= separuh refresh rate -> jeda; mode paksa: selalu 2x
                val m = FgState.mult.coerceIn(2, 4)
                if (FgState.force) gen = true
                else if (gen && ema > 0 && ema < (m - 0.4) / hz) gen = false
                else if (!gen && ema > (m - 0.08) / hz) gen = true

                val base = if (ema > 0) (1.0 / ema).toFloat() else 0f
                val on = gen && FgState.enabled
                val stale = frames < 2 || dt <= 0 || dt > 0.08
                FgState.baseFps = base
                FgState.hz = hz
                FgState.outFps = if (on && !stale) base * m else base

                if (!on) {
                    if (shown) {
                        screen()
                        GLES30.glClearColor(0f, 0f, 0f, 0f)
                        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                        swap()
                        shown = false
                    }
                } else if (stale) {
                    blit(cur); swap(); shown = true
                } else {
                    // 1a) estimasi gerak kasar
                    target(mv.fbo, mv.w, mv.h)
                    GLES30.glUseProgram(pMe)
                    tex(0, cur.tex); tex(1, prev.tex)
                    GLES30.glUniform1i(loc(pMe, "uCur"), 0)
                    GLES30.glUniform1i(loc(pMe, "uPrev"), 1)
                    GLES30.glUniform2i(loc(pMe, "uSize"), cw, ch)
                    GLES30.glUniform1f(loc(pMe, "uK"), k)
                    draw()
                    // 1b) penyempurnaan blok 8*K px, sub-piksel
                    target(mvB.fbo, mvB.w, mvB.h)
                    GLES30.glUseProgram(pMeB)
                    tex(0, cur.tex); tex(1, prev.tex); tex(2, mv.tex)
                    GLES30.glUniform1i(loc(pMeB, "uCur"), 0)
                    GLES30.glUniform1i(loc(pMeB, "uPrev"), 1)
                    GLES30.glUniform1i(loc(pMeB, "uMvA"), 2)
                    GLES30.glUniform2f(loc(pMeB, "uSize"), cw.toFloat(), ch.toFloat())
                    GLES30.glUniform1f(loc(pMeB, "uK"), k)
                    draw()
                    // 2) (m-1) frame perantara, lalu frame asli
                    val zsrc = if (FgState.contentW > FgState.contentH) FgState.zonesL else FgState.zonesP
                    val zn = minOf(8, zsrc.size / 4)
                    val zf = FloatArray(32)
                    System.arraycopy(zsrc, 0, zf, 0, zn * 4)
                    for (fi in 1 until m) {
                        screen()
                        GLES30.glUseProgram(pInt)
                        tex(0, cur.tex); tex(1, prev.tex); tex(2, mvB.tex)
                        GLES30.glUniform1i(loc(pInt, "uCur"), 0)
                        GLES30.glUniform1i(loc(pInt, "uPrev"), 1)
                        GLES30.glUniform1i(loc(pInt, "uMv"), 2)
                        GLES30.glUniform2f(loc(pInt, "uSize"), cw.toFloat(), ch.toFloat())
                        GLES30.glUniform1f(loc(pInt, "uT"), fi.toFloat() / m)
                        GLES30.glUniform1f(loc(pInt, "uK"), k)
                        GLES30.glUniform4fv(loc(pInt, "uZ"), 8, zf, 0)
                        GLES30.glUniform1i(loc(pInt, "uZn"), zn)
                        draw()
                        swap()
                    }
                    blit(cur); swap(); shown = true
                }
            }
        } catch (e: Throwable) {
            if (alive) onFail(e.message ?: e.javaClass.simpleName)
        } finally {
            alive = false
            FgState.baseFps = 0f
            runCatching { vd?.release() }
            runCatching { cs?.release() }
            runCatching { st?.release() }
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (es != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, es)
                if (ec != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ec)
                EGL14.eglTerminate(dpy)
            }
        }
    }
}
