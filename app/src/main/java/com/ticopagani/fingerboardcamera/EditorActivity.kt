@file:Suppress("DEPRECATION")

package com.ticopagani.fingerboardcamera

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.SurfaceTexture
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/** Editor do clipe: escolhe o trecho em slow com duas barras, ajusta o efeito e exporta. */
class EditorActivity : Activity() {

    companion object {
        const val EXTRA_PATH = "path"
    }

    private lateinit var file: File
    private lateinit var meta: Clips.Meta
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: FisheyeRenderer
    private var player: MediaPlayer? = null
    private var playerSurface: Surface? = null

    private var fishPct = 75
    private var vigPct = 60
    private var mult = 4
    private var keepOrig = true
    private var durationSec = 1.0
    private var durationMs = 1
    private var fpsInfo = ""
    private var currentSpeed = 1f
    private var exporting = false

    private lateinit var slider: RangeSliderView
    private lateinit var rangeText: TextView
    private lateinit var finalText: TextView
    private lateinit var previewTag: TextView
    private lateinit var exportBtn: Chip
    private lateinit var playBtn: Chip
    private val speedChips = mutableListOf<Chip>()
    private val speeds = listOf(1, 2, 4)

    private val ui = Handler(Looper.getMainLooper())
    private val portrait get() = meta.aspect < 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null || !File(path).exists()) { finish(); return }
        file = File(path)
        meta = Clips.meta(file)
        mult = if (meta.slow) 4 else 1
        requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        var rotation = 0
        var vw = 1920
        var vh = 1080
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: vw
            vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: vh
            durationMs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 1000).coerceAtLeast(1)
            val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            if (frames != null) fpsInfo = " · ${((frames * 1000.0 / durationMs) / 10).let { Math.round(it) * 10 }}fps"
        } catch (_: Exception) {
        } finally {
            r.release()
        }
        durationSec = durationMs / 1000.0

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = false
        }
        renderer = FisheyeRenderer(glView) { st -> startPlayer(st) }
        // O player já aplica a rotação gravada no arquivo.
        renderer.rotation = 0
        renderer.srcAspect = if (rotation % 180 != 0) vh.toFloat() / vw else vw.toFloat() / vh
        renderer.outAspect = meta.aspect
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        setContentView(if (portrait) buildPortrait() else buildLandscape())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        glView.onResume()
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        releasePlayer()
        glView.onPause()
        super.onPause()
    }

    // ---------- reprodução com o slow ao vivo ----------

    private fun startPlayer(st: SurfaceTexture) {
        releasePlayer()
        val s = Surface(st)
        playerSurface = s
        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.absolutePath)
            mp.setSurface(s)
            mp.isLooping = true
            mp.setOnPreparedListener {
                it.start()
                currentSpeed = 1f
                playBtn.text = "II"
                ui.removeCallbacks(tick)
                ui.post(tick)
            }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            mp.release()
            toast("Não consegui abrir o vídeo")
        }
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { playerSurface?.release() } catch (_: Exception) {}
        playerSurface = null
    }

    private val tick = object : Runnable {
        override fun run() {
            val mp = player ?: return
            try {
                if (mp.isPlaying) {
                    val pos = mp.currentPosition / durationMs.toFloat()
                    slider.playhead = pos
                    val want = if (mult > 1 && pos >= slider.start && pos <= slider.end) 1f / mult else 1f
                    if (want != currentSpeed) {
                        mp.playbackParams = mp.playbackParams.setSpeed(want)
                        currentSpeed = want
                    }
                }
            } catch (_: IllegalStateException) {}
            ui.postDelayed(this, 30)
        }
    }

    private fun togglePlay() {
        val mp = player ?: return
        try {
            if (mp.isPlaying) { mp.pause(); playBtn.text = "▶" } else { mp.start(); playBtn.text = "II" }
        } catch (_: IllegalStateException) {}
    }

    // ---------- estado ----------

    private fun refresh() {
        speedChips.forEachIndexed { i, c -> c.on = speeds[i] == mult }
        val sLabel = when (mult) { 2 -> "0.5x"; 4 -> "0.25x"; else -> "1x" }
        slider.highlight = mult > 1
        slider.label = "SLOW $sLabel"
        val a = slider.start * durationSec
        val b = slider.end * durationSec
        val slowLen = if (mult > 1) b - a else 0.0
        rangeText.text = if (mult > 1) String.format(Locale.US, "slow de %.1fs até %.1fs", a, b) else "clipe todo em tempo real"
        finalText.text = String.format(Locale.US, "%.1fs", durationSec - slowLen + slowLen * mult)
        previewTag.text = if (mult > 1) "SLOW $sLabel" else "1x"
        renderer.k = FisheyeGl.strengthFromPercent(fishPct)
        renderer.vig = vigPct / 100f
        renderer.redraw()
    }

    private fun doExport() {
        if (exporting) return
        exporting = true
        exportBtn.isEnabled = false
        exportBtn.text = "EXPORTANDO 0%"
        val params = VideoExporter.Params(
            aspect = meta.aspect,
            k = FisheyeGl.strengthFromPercent(fishPct),
            vig = vigPct / 100f,
            slowStartSec = slider.start * durationSec,
            slowEndSec = slider.end * durationSec,
            mult = mult
        )
        val keep = keepOrig
        Thread {
            try {
                val out = VideoExporter(this).export(file, params) { f ->
                    ui.post { exportBtn.text = "EXPORTANDO ${(f * 100).toInt()}%" }
                }
                val stamp = Clips.stamp()
                Gallery.saveVideo(this, out, "FingerboardCamera_$stamp.mp4")
                out.delete()
                if (keep) Gallery.saveVideo(this, file, "FingerboardCamera_${stamp}_original.mp4")
                ui.post { toast("Salvo em Filmes › FingerboardCamera") }
            } catch (e: Throwable) {
                ui.post { toast("Erro ao exportar: ${e.message}") }
            } finally {
                ui.post {
                    exporting = false
                    exportBtn.isEnabled = true
                    exportBtn.text = "EXPORTAR"
                }
            }
        }.start()
    }

    // ---------- UI ----------

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun seek(labelText: String, value: Int, onChange: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(label(labelText, 12f, Pal.FG), LinearLayout.LayoutParams(0, -2, 1f))
        val v = label("$value%", 12f, Pal.FG, true)
        row.addView(v)
        box.addView(row)
        val sb = SeekBar(this).apply {
            max = 100
            progress = value
            progressTintList = ColorStateList.valueOf(Pal.ACCENT)
            thumbTintList = ColorStateList.valueOf(Pal.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Pal.LINE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    v.text = "$p%"
                    onChange(p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(sb, lp(-1, dp(32)))
        return box
    }

    private fun speedBlock(): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label("Velocidade do trecho em slow", 12f, Pal.FG))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        speedChips.clear()
        val texts = listOf("1x\nsem slow", "0.5x\n2x mais lento", "0.25x\n4x mais lento")
        texts.forEachIndexed { i, t ->
            val c = Chip(this, t).apply {
                textSize = 11f
                setOnClickListener { mult = speeds[i]; refresh() }
            }
            speedChips.add(c)
            row.addView(c, LinearLayout.LayoutParams(0, dp(50), 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        box.addView(row, lp(-1, -2).apply { topMargin = dp(6) })
        return box
    }

    private fun keepBox(): CheckBox = CheckBox(this).apply {
        text = "Também salvar o original sem efeito"
        textSize = 12f
        setTextColor(Pal.FG)
        typeface = MONO
        isChecked = keepOrig
        buttonTintList = ColorStateList.valueOf(Pal.ACCENT)
        setOnCheckedChangeListener { _, c -> keepOrig = c }
    }

    private fun previewBox(): FrameLayout {
        val f = FrameLayout(this)
        f.addView(glView, FrameLayout.LayoutParams(-1, -1))
        previewTag = label("SLOW", 11f, Pal.FG, true).apply {
            background = roundRect(this@EditorActivity, 0x99000000.toInt(), null, 4)
            setPadding(dp(7), dp(3), dp(7), dp(3))
        }
        f.addView(previewTag, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        return f
    }

    private fun timelineRow(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        playBtn = Chip(this, "II").apply { radiusDp = 22; setOnClickListener { togglePlay() } }
        row.addView(playBtn, lp(dp(44), dp(44)))
        slider = RangeSliderView(this).apply {
            onChange = { _, _ -> refresh() }
        }
        row.addView(slider, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(12) })
        return row
    }

    private fun topBar(withExport: Boolean): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        val back = label("‹ Câmera", 13f, Pal.FG).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(44)
            setOnClickListener { finish() }
        }
        bar.addView(back)
        bar.addView(label("EDITAR CLIPE", 15f, Pal.FG, true), lp(-2, -2).apply { marginStart = dp(14) })
        bar.addView(
            label(String.format(Locale.US, "%.1fs%s", durationSec, fpsInfo), 11f, Pal.MUTED),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
        )
        exportBtn = Chip(this, "EXPORTAR").apply {
            on = true
            radiusDp = 8
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { doExport() }
        }
        if (withExport) bar.addView(exportBtn, lp(-2, dp(44)))
        return bar
    }

    private fun buildLandscape(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Pal.BG) }
        root.addView(topBar(true), lp(-1, dp(56)))

        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        mid.addView(previewBox(), LinearLayout.LayoutParams(0, -1, 1f))
        val controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, 0, 0) }
        controls.addView(seek("Intensidade fisheye", fishPct) { fishPct = it; refresh() })
        controls.addView(seek("Vinheta (cantos pretos)", vigPct) { vigPct = it; refresh() })
        controls.addView(speedBlock(), lp(-1, -2).apply { topMargin = dp(6) })
        controls.addView(keepBox(), lp(-1, -2).apply { topMargin = dp(4) })
        mid.addView(controls, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(mid, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(10))
        }
        val tl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tl.addView(timelineRow())
        rangeText = label("", 11f, Pal.FG).apply { gravity = Gravity.CENTER_HORIZONTAL }
        tl.addView(rangeText, lp(-1, -2).apply { marginStart = dp(56) })
        bottom.addView(tl, LinearLayout.LayoutParams(0, -2, 1f))
        val fin = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        fin.addView(label("FINAL"))
        finalText = label("", 18f, Pal.FG, true)
        fin.addView(finalText)
        bottom.addView(fin, lp(dp(88), -2))
        root.addView(bottom, lp(-1, -2))
        return root
    }

    private fun buildPortrait(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Pal.BG) }
        root.addView(topBar(false), lp(-1, dp(56)))
        root.addView(previewBox(), LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) })

        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(16)) }
        panel.addView(timelineRow())
        val info = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        rangeText = label("", 11f, Pal.FG)
        info.addView(rangeText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(56) })
        info.addView(label("FINAL "))
        finalText = label("", 14f, Pal.FG, true)
        info.addView(finalText)
        panel.addView(info, lp(-1, -2).apply { topMargin = dp(4) })
        panel.addView(speedBlock(), lp(-1, -2).apply { topMargin = dp(14) })

        val sliders = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sliders.addView(seek("Fisheye", fishPct) { fishPct = it; refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        sliders.addView(Space(this), lp(dp(14), 1))
        sliders.addView(seek("Vinheta", vigPct) { vigPct = it; refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(sliders, lp(-1, -2).apply { topMargin = dp(10) })
        panel.addView(keepBox(), lp(-1, -2).apply { topMargin = dp(4) })
        exportBtn.text = "EXPORTAR VÍDEO"
        exportBtn.textSize = 15f
        panel.addView(exportBtn, lp(-1, dp(52)).apply { topMargin = dp(10) })
        root.addView(panel, lp(-1, -2))
        return root
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
