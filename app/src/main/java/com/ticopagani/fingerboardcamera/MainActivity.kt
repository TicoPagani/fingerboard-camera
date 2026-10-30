@file:Suppress("DEPRECATION")

package com.ticopagani.fingerboardcamera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.media.ThumbnailUtils
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var camera: CameraController
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: FisheyeRenderer
    private var surfaceTexture: SurfaceTexture? = null
    private var lenses: List<LensOption> = emptyList()
    private var front: LensOption? = null
    private var hasPermission = false
    private var sessionReady = false

    // estado
    private var portrait = false
    private var photoMode = false
    private var formatIdx = 1
    private var fps = 120
    private var slow = true
    private var slowFps = 120
    private var focusIdx = 1

    /** Foco travado como no anel da filmadora (dioptrias = 1/metros). null = autofoco. */
    private val focusPresets = listOf<Pair<String, Float?>>(
        "AUTO" to null,
        "PERTO" to 6.5f,  // ~15cm: fingerboard colado na lente
        "MÉDIO" to 2f,    // ~50cm
        "LONGE" to 0.2f   // ~5m em diante: tudo nítido de longe
    )
    private var fish = true
    private var lensIdx = 0
    private var useFront = false
    private var recording = false
    private var busy = false
    private var recStart = 0L

    // views
    private val formatChips = mutableListOf<Chip>()
    private val fpsChips = mutableListOf<Chip>()
    private val videoOnly = mutableListOf<View>()
    private val lockWhileRec = mutableListOf<View>()
    private lateinit var slowChip: Chip
    private lateinit var fishChip: Chip
    private lateinit var lensChip: Chip
    private lateinit var focusChip: Chip
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView
    private lateinit var shutter: ShutterView
    private lateinit var galleryBtn: View
    private lateinit var statusText: TextView
    private lateinit var badgeText: TextView
    private lateinit var formatText: TextView
    private lateinit var flash: View

    private val ui = Handler(Looper.getMainLooper())

    private val landscapeLabels = listOf("16:9", "4:3", "1:1")
    private val portraitLabels = listOf("9:16", "3:4", "1:1")
    private val landscapeAspects = listOf(16f / 9f, 4f / 3f, 1f)
    private val portraitAspects = listOf(9f / 16f, 3f / 4f, 1f)

    private fun aspect() = (if (portrait) portraitAspects else landscapeAspects)[formatIdx]
    private fun effectiveFps() = if (photoMode) 30 else if (slow) slowFps else fps

    /**
     * FPS da sessão da câmera. A Xiaomi só libera até 30fps no modo normal pra outros apps,
     * então o 60 grava por baixo no modo de alta velocidade (120) e vira 60 na exportação.
     */
    private fun sessionFps(): Int {
        val e = effectiveFps()
        val lens = currentLens() ?: return e
        if (e == 60 && !camera.supports(lens, 60) && camera.supports(lens, 120)) return 120
        return e
    }
    private fun currentLens(): LensOption? = if (useFront) front else lenses.getOrNull(lensIdx)

    // ---------- ciclo de vida ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("state", MODE_PRIVATE)
        loadState()
        requestedOrientation = if (portrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        camera = CameraController(this)
        lenses = camera.backLenses()
        front = camera.frontLens()
        if (lensIdx >= lenses.size) lensIdx = 0
        if (useFront && front == null) useFront = false

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = false
        }
        renderer = FisheyeRenderer(glView) { st ->
            surfaceTexture = st
            reopenCamera()
        }
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        setContentView(if (portrait) buildPortrait() else buildLandscape())
        applyRendererLook()
        refreshUi()

        hasPermission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        if (lenses.isEmpty()) toast("Nenhuma câmera traseira encontrada")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        hasPermission = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (hasPermission) reopenCamera() else toast("Sem permissão de câmera o app não funciona")
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        glView.onResume()
        refreshThumb()
    }

    override fun onPause() {
        if (recording) stopRecording()
        sessionReady = false
        camera.closeBlocking()
        glView.onPause()
        surfaceTexture = null
        super.onPause()
    }

    override fun onDestroy() {
        camera.shutdown()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // Botões de volume = disparo
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) shutterPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return true
        return super.onKeyUp(keyCode, event)
    }

    // ---------- estado ----------

    private fun loadState() {
        portrait = prefs.getBoolean("portrait", false)
        photoMode = prefs.getBoolean("photo", false)
        formatIdx = prefs.getInt("format", 1).coerceIn(0, 2)
        fps = prefs.getInt("fps", 120)
        slow = prefs.getBoolean("slow", true)
        slowFps = if (prefs.getInt("slowFps", 120) >= 240) 240 else 120
        focusIdx = prefs.getInt("focus", 1).coerceIn(0, 3)
        fish = prefs.getBoolean("fish", true)
        lensIdx = prefs.getInt("lens", 0)
        useFront = prefs.getBoolean("front", false)
    }

    private fun saveState() {
        prefs.edit()
            .putBoolean("portrait", portrait)
            .putBoolean("photo", photoMode)
            .putInt("format", formatIdx)
            .putInt("fps", fps)
            .putBoolean("slow", slow)
            .putInt("slowFps", slowFps)
            .putInt("focus", focusIdx)
            .putBoolean("fish", fish)
            .putInt("lens", lensIdx)
            .putBoolean("front", useFront)
            .apply()
    }

    // ---------- câmera ----------

    private fun displayDegrees(): Int {
        val rot = if (Build.VERSION.SDK_INT >= 30) (display?.rotation ?: Surface.ROTATION_0) else windowManager.defaultDisplay.rotation
        return when (rot) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /** Quanto girar a imagem do sensor (horário) pra ficar em pé na tela. */
    private fun rotationFor(lens: LensOption): Int {
        val s = camera.sensorOrientation(lens.cameraId)
        val d = displayDegrees()
        return if (lens.front) (s + d) % 360 else (s - d + 360) % 360
    }

    private fun reopenCamera() {
        val st = surfaceTexture ?: return
        if (!hasPermission) return
        val lens = currentLens() ?: return
        sessionReady = false
        camera.focus = focusPresets[focusIdx].second
        camera.open(lens, photoMode, sessionFps(), st,
            onReady = { info -> runOnUiThread { onSessionReady(lens, info) } },
            onError = { msg -> runOnUiThread { toast(msg) } })
    }

    private fun onSessionReady(lens: LensOption, info: SessionInfo) {
        sessionReady = true
        val rot = rotationFor(lens)
        // O Android já entrega o preview em pé na orientação natural (retrato);
        // só falta compensar o giro da tela.
        renderer.rotation = (360 - displayDegrees()) % 360
        renderer.mirror = false
        val w = info.size.width.toFloat()
        val h = info.size.height.toFloat()
        renderer.srcAspect = if (rot % 180 != 0) h / w else w / h
        applyRendererLook()
    }

    private fun applyRendererLook() {
        renderer.outAspect = aspect()
        renderer.k = if (fish) FishDefaults.K else 0f
        renderer.vig = if (fish) FishDefaults.VIG else 0f
        renderer.redraw()
    }

    // ---------- ações ----------

    private fun shutterPressed() {
        if (busy) return
        if (photoMode) takePhoto() else if (recording) stopRecording() else startRecording()
    }

    private fun takePhoto() {
        val lens = currentLens() ?: return
        if (!sessionReady) return
        busy = true
        flash.alpha = 0.8f
        flash.animate().alpha(0f).setDuration(250).start()
        val rot = rotationFor(lens)
        val a = aspect()
        val k = if (fish) FishDefaults.K else 0f
        val v = if (fish) FishDefaults.VIG else 0f
        camera.takePhoto({ bytes ->
            Thread {
                try {
                    val bmp = PhotoProcessor.process(bytes, rot, a, k, v)
                    Gallery.saveJpeg(this, bmp, "FingerboardCamera_${Clips.stamp()}.jpg")
                    bmp.recycle()
                    runOnUiThread { toast("Foto salva em Fotos › FingerboardCamera") }
                } catch (e: Throwable) {
                    runOnUiThread { toast("Erro ao salvar foto: ${e.message}") }
                } finally {
                    runOnUiThread { busy = false }
                }
            }.start()
        }, { msg -> runOnUiThread { busy = false; toast(msg) } })
    }

    private fun startRecording() {
        val lens = currentLens() ?: return
        if (!sessionReady) return
        busy = true
        val file = Clips.newFile(this, aspect(), if (slow) slowFps else 0)
        camera.startRecording(file, rotationFor(lens)) { ok, err ->
            runOnUiThread {
                busy = false
                if (ok) {
                    recording = true
                    recStart = SystemClock.elapsedRealtime()
                    ui.post(tick)
                } else toast("Não deu pra gravar: $err")
                refreshUi()
            }
        }
    }

    private fun stopRecording() {
        busy = true
        camera.stopRecording { file ->
            runOnUiThread {
                busy = false
                recording = false
                ui.removeCallbacks(tick)
                if (file == null) toast("O clipe ficou curto demais e foi descartado")
                refreshUi()
                refreshThumb()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            val ms = SystemClock.elapsedRealtime() - recStart
            val s = ms / 1000
            val frames = (ms % 1000) * 30 / 1000
            statusText.text = String.format(Locale.US, "● REC %02d:%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60, frames)
            ui.postDelayed(this, 50)
        }
    }

    private fun pickFormat(i: Int) {
        formatIdx = i
        saveState()
        applyRendererLook()
        refreshUi()
    }

    private fun pickFps(f: Int) {
        if (f == 120) {
            if (!ensureHighSpeedLens(120)) return
            fps = 120
            if (slow) slowFps = 120
        } else {
            val lens = currentLens() ?: return
            val ok = camera.supports(lens, f) || (f == 60 && camera.supports(lens, 120))
            if (!ok) { toast("Essa câmera não grava em ${f}fps"); return }
            fps = f
            slow = false
        }
        saveState(); refreshUi(); reopenCamera()
    }

    /** Cada toque: OFF → 120 → 240 → OFF (pula o 240 se a câmera atual não tiver). */
    private fun toggleSlow() {
        val lens = currentLens()
        when {
            !slow -> {
                if (!ensureHighSpeedLens(120)) return
                slow = true
                slowFps = 120
                fps = 120
            }
            slowFps == 120 && lens != null && camera.supports(lens, 240) -> slowFps = 240
            else -> {
                slow = false
                slowFps = 120
            }
        }
        saveState(); refreshUi(); reopenCamera()
    }

    /** Alta velocidade só existe em algumas câmeras: troca pra uma que tenha, se precisar. */
    private fun ensureHighSpeedLens(want: Int): Boolean {
        val lens = currentLens()
        if (lens != null && camera.supports(lens, want)) return true
        val idx = lenses.indexOfFirst { camera.supports(it, want) }
        if (idx < 0) {
            toast("Esse celular não libera ${want}fps pra outros apps")
            return false
        }
        useFront = false
        lensIdx = idx
        return true
    }

    private fun toggleLens() {
        if (lenses.size < 2 && !useFront) { toast("Só uma lente traseira disponível pra apps"); return }
        useFront = false
        lensIdx = (lensIdx + 1) % lenses.size.coerceAtLeast(1)
        fitFpsToLens()
        saveState(); refreshUi(); reopenCamera()
    }

    private fun toggleFront() {
        if (front == null) { toast("Câmera frontal indisponível"); return }
        useFront = !useFront
        fitFpsToLens()
        saveState(); refreshUi(); reopenCamera()
    }

    private fun fitFpsToLens() {
        val lens = currentLens() ?: return
        if (slow && slowFps == 240 && !camera.supports(lens, 240)) slowFps = 120
        if (effectiveFps() >= 120 && !camera.supports(lens, 120)) {
            slow = false
            fps = 60
        }
        if (fps == 60 && !camera.supports(lens, 60) && !camera.supports(lens, 120)) fps = 30
    }

    /** Funciona até gravando, como girar o anel de foco no meio da filmagem. */
    private fun cycleFocus() {
        focusIdx = (focusIdx + 1) % focusPresets.size
        camera.changeFocus(focusPresets[focusIdx].second)
        saveState(); refreshUi()
    }

    private fun toggleFish() {
        fish = !fish
        saveState(); applyRendererLook(); refreshUi()
    }

    private fun setPhotoMode(p: Boolean) {
        if (photoMode == p) return
        photoMode = p
        saveState(); refreshUi(); reopenCamera()
    }

    private fun toggleOrientation() {
        portrait = !portrait
        saveState()
        recreate()
    }

    private fun openEditor() {
        if (recording) return
        val f = Clips.last(this)
        if (f == null) { toast("Nenhum vídeo gravado ainda"); return }
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_PATH, f.absolutePath))
    }

    private fun showDiagnostics() {
        val text = camera.report()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Câmeras do aparelho")
            .setMessage(text)
            .setPositiveButton("Copiar") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("diagnóstico", text))
                toast("Copiado")
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    // ---------- UI ----------

    private fun refreshUi() {
        val labels = if (portrait) portraitLabels else landscapeLabels
        formatChips.forEachIndexed { i, c -> c.on = i == formatIdx }
        fpsChips.forEachIndexed { i, c -> c.on = !slow && listOf(30, 60, 120)[i] == fps }
        focusChip.text = "FOCO\n${focusPresets[focusIdx].first}"
        focusChip.on = false
        slowChip.on = slow
        val slowState = if (slow) "$slowFps" else "OFF"
        slowChip.text = if (portrait) "SLOWMO\n$slowState" else "SLOW\n$slowState"
        fishChip.on = fish
        lensChip.text = "${lenses.getOrNull(lensIdx)?.label ?: "1x"}\nLENTE"
        // O Poco não libera a ultra-wide pra outros apps: sem 2ª lente, o botão some.
        lensChip.visibility = if (lenses.size >= 2 && !useFront) View.VISIBLE else View.GONE
        videoOnly.forEach { it.visibility = if (photoMode) View.GONE else View.VISIBLE }
        stylePill(modePhoto, photoMode)
        stylePill(modeVideo, !photoMode)
        shutter.mode = when {
            photoMode -> ShutterView.Mode.PHOTO
            recording -> ShutterView.Mode.RECORDING
            else -> ShutterView.Mode.VIDEO
        }
        lockWhileRec.forEach { it.isEnabled = !recording; it.alpha = if (recording) 0.35f else 1f }
        formatText.text = labels[formatIdx]
        if (photoMode) {
            statusText.visibility = View.GONE
            badgeText.text = "FOTO"
            badgeText.background = roundRect(this, 0x8C000000.toInt(), null, 4)
            badgeText.setTextColor(Pal.FG)
        } else {
            statusText.visibility = View.VISIBLE
            if (!recording) statusText.text = "STBY 00:00:00:00"
            statusText.setTextColor(if (recording) Pal.REC else Pal.FG)
            val e = effectiveFps()
            if (slow) {
                badgeText.text = "$slowFps FPS · SLOWMO"
                badgeText.background = roundRect(this, Pal.ACCENT, null, 4)
                badgeText.setTextColor(Pal.BG)
            } else {
                badgeText.text = "$e FPS"
                badgeText.background = roundRect(this, 0x8C000000.toInt(), null, 4)
                badgeText.setTextColor(Pal.FG)
            }
        }
    }

    private fun stylePill(t: TextView, on: Boolean) {
        t.background = if (on) roundRect(this, Pal.FG, null, 18) else null
        t.setTextColor(if (on) Pal.BG else Pal.FG)
    }

    private fun refreshThumb() {
        Thread {
            val f = Clips.last(this) ?: return@Thread
            val bmp = (try { ThumbnailUtils.createVideoThumbnail(f, Size(160, 160), null) } catch (e: Exception) { null })
                ?: return@Thread
            runOnUiThread { galleryBtn.background = BitmapDrawable(resources, bmp) }
        }.start()
    }

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun makeFormatChips(): List<Chip> {
        formatChips.clear()
        val labels = if (portrait) portraitLabels else landscapeLabels
        labels.forEachIndexed { i, l ->
            val c = Chip(this, l)
            c.setOnClickListener { pickFormat(i) }
            formatChips.add(c)
            lockWhileRec.add(c)
        }
        return formatChips
    }

    private fun makeFpsChips(): List<Chip> {
        fpsChips.clear()
        listOf(30, 60, 120).forEach { f ->
            val c = Chip(this, f.toString())
            c.setOnClickListener { pickFps(f) }
            fpsChips.add(c)
            lockWhileRec.add(c)
        }
        return fpsChips
    }

    private fun makeIconChip(text: String, onClick: () -> Unit): Chip = Chip(this, text).apply {
        radiusDp = 10
        textSize = 10f
        setLineSpacing(0f, 0.95f)
        setOnClickListener { onClick() }
    }

    private fun makeCommonControls() {
        slowChip = makeIconChip("SLOW\nON") { toggleSlow() }.apply { dashedWhenOff = true }
        fishChip = makeIconChip("FISH") { toggleFish() }
        lensChip = makeIconChip("1x\nLENTE") { toggleLens() }
        focusChip = makeIconChip("FOCO\nPERTO") { cycleFocus() }.apply { textSize = 9f }
        lensChip.setOnLongClickListener { showDiagnostics(); true }
        fishChip.setOnLongClickListener { showDiagnostics(); true }
        lockWhileRec.add(slowChip)
        lockWhileRec.add(lensChip)
        shutter = ShutterView(this).apply { setOnClickListener { shutterPressed() } }
        galleryBtn = View(this).apply {
            background = roundRect(this@MainActivity, 0xFF3A3832.toInt(), Pal.LINE, 8)
            contentDescription = "Abrir último clipe"
            isClickable = true
            clipToOutline = true
            setOnClickListener { openEditor() }
        }
        modePhoto = TextView(this).apply {
            text = "FOTO"; typeface = MONO_BOLD; textSize = 11f; gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { setPhotoMode(true) }
        }
        modeVideo = TextView(this).apply {
            text = "VÍDEO"; typeface = MONO_BOLD; textSize = 11f; gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { setPhotoMode(false) }
        }
        lockWhileRec.add(modePhoto)
        lockWhileRec.add(modeVideo)
    }

    /** Área do visor: preview GL + textos por cima. */
    private fun buildViewfinder(withModeToggle: Boolean): FrameLayout {
        val f = FrameLayout(this)
        f.addView(glView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val m = dp(12)
        statusText = label("STBY 00:00:00:00", 12f, Pal.FG, true)
        f.addView(statusText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(m, m, m, m) })
        badgeText = label("120 FPS", 11f, Pal.FG, true).apply { setPadding(dp(7), dp(3), dp(7), dp(3)) }
        f.addView(badgeText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(m, m, m, m) })
        val vol = label("◀) VOL = disparo", 11f, Pal.FG)
        f.addView(vol, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(m, m, m, m) })
        formatText = label("4:3", 12f, Pal.FG, true)
        f.addView(formatText, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(m, m, m, m) })
        if (withModeToggle) {
            f.addView(makeModeToggle(), FrameLayout.LayoutParams(-2, dp(36), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) })
        }
        flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f; isClickable = false }
        f.addView(flash, FrameLayout.LayoutParams(-1, -1))
        return f
    }

    private fun makeModeToggle(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = roundRect(this@MainActivity, 0x99000000.toInt(), null, 20)
        setPadding(dp(3), dp(3), dp(3), dp(3))
        addView(modePhoto, LinearLayout.LayoutParams(-2, -1))
        addView(modeVideo, LinearLayout.LayoutParams(-2, -1))
    }

    private fun buildLandscape(): View {
        makeCommonControls()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Pal.BG)
        }

        // coluna esquerda: formato + fps
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        }
        left.addView(label("FORMATO"), lp(-2, -2).apply { bottomMargin = dp(2) })
        makeFormatChips().forEach { left.addView(it, lp(dp(58), dp(44)).apply { topMargin = dp(4) }) }
        val fpsLabel = label("FPS")
        left.addView(fpsLabel, lp(-2, -2).apply { topMargin = dp(12); bottomMargin = dp(2) })
        videoOnly.add(fpsLabel)
        makeFpsChips().forEach {
            left.addView(it, lp(dp(58), dp(44)).apply { topMargin = dp(4) })
            videoOnly.add(it)
        }
        root.addView(left, lp(dp(75), -1))

        root.addView(buildViewfinder(true), LinearLayout.LayoutParams(0, -1, 1f))

        // coluna direita
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }
        fun spacer() = right.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        val vert = makeIconChip("VERT") { toggleOrientation() }
        lockWhileRec.add(vert)
        right.addView(vert, lp(dp(52), dp(44)))
        spacer()
        right.addView(lensChip, lp(dp(52), dp(44)))
        right.addView(focusChip, lp(dp(52), dp(44)))
        spacer()
        val flipL = makeIconChip("⟲\nFRONT") { toggleFront() }
        lockWhileRec.add(flipL)
        right.addView(flipL, lp(dp(52), dp(44)))
        spacer()
        right.addView(fishChip, lp(dp(52), dp(44)))
        spacer()
        right.addView(slowChip, lp(dp(52), dp(44)))
        videoOnly.add(slowChip)
        spacer()
        right.addView(shutter, lp(dp(60), dp(60)))
        spacer()
        right.addView(galleryBtn, lp(dp(46), dp(46)))
        root.addView(right, lp(dp(75), -1))
        return root
    }

    private fun buildPortrait(): View {
        makeCommonControls()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.BG)
        }

        // barra do topo
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        val title = label("FINGERBOARD CAMERA", 13f, Pal.FG, true)
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(lensChip, lp(dp(52), dp(44)).apply { marginStart = dp(6) })
        top.addView(focusChip, lp(dp(52), dp(44)).apply { marginStart = dp(6) })
        top.addView(fishChip, lp(dp(52), dp(44)).apply { marginStart = dp(6) })
        val horiz = makeIconChip("HORIZ") { toggleOrientation() }
        lockWhileRec.add(horiz)
        top.addView(horiz, lp(dp(52), dp(44)).apply { marginStart = dp(6) })
        root.addView(top, lp(-1, dp(60)))

        root.addView(buildViewfinder(false), LinearLayout.LayoutParams(-1, 0, 1f))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        val fmtRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fmtRow.addView(label("FORMATO"), lp(dp(64), -2))
        makeFormatChips().forEach { fmtRow.addView(it, lp(dp(58), dp(44)).apply { marginEnd = dp(6) }) }
        panel.addView(fmtRow)

        val fpsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fpsRow.addView(label("FPS"), lp(dp(64), -2))
        makeFpsChips().forEach { fpsRow.addView(it, lp(dp(58), dp(44)).apply { marginEnd = dp(6) }) }
        fpsRow.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        slowChip.radiusDp = 6
        fpsRow.addView(slowChip, lp(dp(72), dp(44)))
        panel.addView(fpsRow, lp(-1, -2).apply { topMargin = dp(10) })
        videoOnly.add(fpsRow)

        val modeWrap = FrameLayout(this)
        modeWrap.addView(makeModeToggle(), FrameLayout.LayoutParams(-2, dp(40), Gravity.CENTER))
        panel.addView(modeWrap, lp(-1, dp(40)).apply { topMargin = dp(14) })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        bottom.addView(galleryBtn, lp(dp(46), dp(46)))
        bottom.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottom.addView(shutter, lp(dp(72), dp(72)))
        bottom.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        val flip = makeIconChip("⟲\nFRONT") { toggleFront() }.apply { radiusDp = 23 }
        lockWhileRec.add(flip)
        bottom.addView(flip, lp(dp(46), dp(46)))
        panel.addView(bottom, lp(-1, dp(80)).apply { topMargin = dp(10) })

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
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
