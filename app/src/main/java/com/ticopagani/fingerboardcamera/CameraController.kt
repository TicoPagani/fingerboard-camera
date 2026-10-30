@file:Suppress("DEPRECATION")

package com.ticopagani.fingerboardcamera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class LensOption(val cameraId: String, val zoom: Float, val label: String, val front: Boolean = false)

data class SessionInfo(val size: Size, val fps: Int, val highSpeed: Boolean, val photo: Boolean)

/**
 * Tudo que fala com a câmera (Camera2): abre a lente, monta a sessão certa
 * (foto, vídeo normal ou alta velocidade 120fps), grava e tira foto.
 * Todo o trabalho roda numa thread própria.
 */
class CameraController(private val context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var imageReader: ImageReader? = null
    private var info: SessionInfo? = null
    private var lens: LensOption? = null
    @Volatile private var openToken = 0

    // ---------- Descoberta de lentes e capacidades ----------

    private fun chars(id: String): CameraCharacteristics = manager.getCameraCharacteristics(id)

    fun sensorOrientation(id: String): Int = try {
        chars(id).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    } catch (e: Exception) { 90 }

    private fun focal(id: String): Float =
        chars(id).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 4f

    private fun usable(id: String): Boolean =
        chars(id).get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: false

    private fun ids(): List<String> = try { manager.cameraIdList.toList() } catch (e: Exception) { emptyList() }

    fun backLenses(): List<LensOption> {
        val backs = ids().filter {
            try { chars(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK && usable(it) }
            catch (e: Exception) { false }
        }
        if (backs.isEmpty()) return emptyList()
        val main = backs.first()
        val mainFocal = focal(main)
        val out = mutableListOf(LensOption(main, 1f, "1x"))
        val wide = backs.drop(1).filter { focal(it) < mainFocal * 0.75f }.minByOrNull { focal(it) }
        if (wide != null) {
            out.add(LensOption(wide, 1f, "0.6x"))
        } else if (Build.VERSION.SDK_INT >= 30) {
            val zr = chars(main).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zr != null && zr.lower < 0.95f) {
                out.add(LensOption(main, zr.lower, String.format(Locale.US, "%.1fx", zr.lower)))
            }
        }
        return out
    }

    fun frontLens(): LensOption? {
        val id = ids().firstOrNull {
            try { chars(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT && usable(it) }
            catch (e: Exception) { false }
        } ?: return null
        return LensOption(id, 1f, "FRONT", front = true)
    }

    fun highSpeedSize(id: String, fps: Int = 120): Size? = try {
        val c = chars(id)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        if (!caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) null
        else {
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val ok = map.highSpeedVideoSizes.filter { s ->
                try { map.getHighSpeedVideoFpsRangesFor(s).any { it.lower == fps && it.upper == fps } } catch (e: Exception) { false }
            }
            ok.firstOrNull { it.width == 1920 && it.height == 1080 }
                ?: ok.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
        }
    } catch (e: Exception) { null }

    fun supports(lens: LensOption, fps: Int): Boolean {
        if (fps >= 120) return lens.zoom == 1f && highSpeedSize(lens.cameraId, fps) != null
        val ranges = (try { chars(lens.cameraId).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) } catch (e: Exception) { null })
            ?: return fps <= 30
        return ranges.any { it.upper >= fps }
    }

    private fun videoSize(id: String): Size {
        val map = chars(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(MediaRecorder::class.java)?.toList() ?: emptyList()
        val wide = sizes.filter { abs(it.width * 9 - it.height * 16) < 32 }
        return wide.firstOrNull { it.width == 1920 && it.height == 1080 }
            ?: wide.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: sizes.firstOrNull()
            ?: Size(1280, 720)
    }

    private fun jpegSize(id: String): Size {
        val map = chars(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        val std = sizes.filter { abs(it.width * 3 - it.height * 4) < 40 && it.width * it.height <= 16_000_000 }
        return std.maxByOrNull { it.width * it.height }
            ?: sizes.maxByOrNull { it.width * it.height }
            ?: Size(1920, 1440)
    }

    private fun bestRange(id: String, fps: Int): Range<Int> {
        val ranges = chars(id).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return Range(fps, fps)
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper >= fps }.minByOrNull { it.upper }
            ?: Range(fps, fps)
    }

    /** Texto de diagnóstico: o que o aparelho libera pra apps (lentes, FPS, tamanhos). */
    fun report(): String = buildString {
        appendLine("Fingerboard Camera · diagnóstico")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
        appendLine()
        for (id in ids()) {
            try {
                val c = chars(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "traseira"
                    CameraCharacteristics.LENS_FACING_FRONT -> "frontal"
                    else -> "externa"
                }
                appendLine("Câmera $id · $facing · focal ${"%.2f".format(Locale.US, focal(id))}mm · sensor ${sensorOrientation(id)}°")
                val minF = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                val cal = when (c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)) {
                    CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "calibrado"
                    CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "aproximado"
                    else -> "sem calibração"
                }
                appendLine("  foco manual: ${if (minF > 0f) "até %.1f dioptrias (~%.0fcm), $cal".format(Locale.US, minF, 100f / minF) else "fixo / não liberado"}")
                val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val names = caps.toList().mapNotNull {
                    when (it) {
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "alta-velocidade"
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "multi-câmera"
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "raw"
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "manual"
                        else -> null
                    }
                }
                if (names.isNotEmpty()) appendLine("  recursos: ${names.joinToString()}")
                if (Build.VERSION.SDK_INT >= 28 && c.physicalCameraIds.isNotEmpty()) {
                    appendLine("  lentes físicas: ${c.physicalCameraIds.joinToString()}")
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { appendLine("  zoom: ${it.lower}x a ${it.upper}x") }
                }
                c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.let { r ->
                    appendLine("  fps: ${r.joinToString { "${it.lower}-${it.upper}" }}")
                }
                val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (map != null && caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) {
                    for (s in map.highSpeedVideoSizes) {
                        val r = map.getHighSpeedVideoFpsRangesFor(s).joinToString { "${it.lower}-${it.upper}" }
                        appendLine("  alta-velocidade ${s.width}x${s.height}: $r")
                    }
                }
                appendLine()
            } catch (e: Exception) {
                appendLine("Câmera $id: erro ao ler (${e.message})")
            }
        }
    }

    // ---------- Sessão ----------

    @SuppressLint("MissingPermission")
    fun open(
        lens: LensOption,
        photo: Boolean,
        fps: Int,
        st: SurfaceTexture,
        onReady: (SessionInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        val token = ++openToken
        handler.post {
            if (token != openToken) return@post
            closeInternal()
            try {
                val high = !photo && fps >= 120
                val size = if (high) {
                    highSpeedSize(lens.cameraId, fps) ?: throw IllegalStateException("${fps}fps indisponível nesta câmera")
                } else videoSize(lens.cameraId)
                st.setDefaultBufferSize(size.width, size.height)
                previewSurface = Surface(st)
                val inf = SessionInfo(size, if (photo) 30 else fps, high, photo)
                info = inf
                this.lens = lens
                if (photo) {
                    val js = jpegSize(lens.cameraId)
                    imageReader = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 2)
                } else {
                    recorderSurface = MediaCodec.createPersistentInputSurface()
                    val warm = File(context.cacheDir, "warmup.mp4")
                    buildRecorder(warm, 0).apply { prepare(); release() }
                    warm.delete()
                }
                manager.openCamera(lens.cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        if (token != openToken) { d.close(); return }
                        device = d
                        createSession(d, token, onReady, onError)
                    }

                    override fun onDisconnected(d: CameraDevice) {
                        d.close()
                        if (device == d) device = null
                    }

                    override fun onError(d: CameraDevice, error: Int) {
                        d.close()
                        if (device == d) device = null
                        if (token == openToken) onError("A câmera deu erro ($error). Feche outros apps de câmera.")
                    }
                }, handler)
            } catch (e: Exception) {
                onError("Erro na câmera: ${e.message}")
            }
        }
    }

    private fun createSession(dev: CameraDevice, token: Int, onReady: (SessionInfo) -> Unit, onError: (String) -> Unit) {
        val inf = info ?: return
        val targets = mutableListOf(previewSurface!!)
        recorderSurface?.let { targets.add(it) }
        imageReader?.let { targets.add(it.surface) }
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (token != openToken) { s.close(); return }
                session = s
                try {
                    startRepeating(false)
                    onReady(inf)
                } catch (e: Exception) {
                    onError("Erro ao iniciar preview: ${e.message}")
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (token == openToken) onError("O aparelho recusou essa combinação (${inf.fps}fps ${inf.size.width}x${inf.size.height}).")
            }
        }
        try {
            if (inf.highSpeed) dev.createConstrainedHighSpeedCaptureSession(targets, cb, handler)
            else dev.createCaptureSession(targets, cb, handler)
        } catch (e: Exception) {
            onError("Erro ao criar sessão: ${e.message}")
        }
    }

    /**
     * Foco travado em dioptrias (1 / metros), como o anel manual de uma filmadora:
     * null = autofoco contínuo.
     */
    @Volatile var focus: Float? = null

    fun changeFocus(diopters: Float?) {
        focus = diopters
        handler.post {
            try { startRepeating(recorder != null) } catch (_: Exception) {}
        }
    }

    private fun applyFocus(b: CaptureRequest.Builder, id: String, photo: Boolean) {
        val c = chars(id)
        val min = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val f = focus
        if (f != null && min > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, f.coerceIn(0f, min))
        } else {
            val af = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val want = if (photo) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            if (af.contains(want)) b.set(CaptureRequest.CONTROL_AF_MODE, want)
        }
    }

    private fun applyCommon(b: CaptureRequest.Builder, ln: LensOption, photo: Boolean) {
        if (Build.VERSION.SDK_INT >= 30 && ln.zoom != 1f) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, ln.zoom)
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        applyFocus(b, ln.cameraId, photo)
    }

    private fun startRepeating(withRecorder: Boolean) {
        val dev = device ?: return
        val s = session ?: return
        val inf = info ?: return
        val ln = lens ?: return
        val b = dev.createCaptureRequest(if (inf.photo) CameraDevice.TEMPLATE_PREVIEW else CameraDevice.TEMPLATE_RECORD)
        b.addTarget(previewSurface!!)
        if (withRecorder) b.addTarget(recorderSurface!!)
        if (inf.highSpeed) {
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(inf.fps, inf.fps))
            applyFocus(b, ln.cameraId, false)
            val list = (s as CameraConstrainedHighSpeedCaptureSession).createHighSpeedRequestList(b.build())
            s.setRepeatingBurst(list, null, handler)
        } else {
            if (!inf.photo) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestRange(ln.cameraId, inf.fps))
            applyCommon(b, ln, inf.photo)
            s.setRepeatingRequest(b.build(), null, handler)
        }
    }

    private fun buildRecorder(file: File, hint: Int): MediaRecorder =
        buildRecorder(hint) { it.setOutputFile(file.absolutePath) }

    private fun buildRecorder(hint: Int, output: (MediaRecorder) -> Unit): MediaRecorder {
        val inf = info!!
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        output(r)
        r.setVideoEncodingBitRate(
            when {
                inf.fps >= 240 -> 80_000_000
                inf.highSpeed -> 50_000_000
                else -> 24_000_000
            }
        )
        r.setVideoFrameRate(inf.fps)
        r.setVideoSize(inf.size.width, inf.size.height)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setOrientationHint(hint)
        r.setInputSurface(recorderSurface!!)
        return r
    }

    /** Grava direto num arquivo da galeria (descritor aberto pelo MediaStore). */
    fun startRecording(fd: ParcelFileDescriptor, orientationHint: Int, done: (Boolean, String?) -> Unit) {
        handler.post {
            try {
                val r = buildRecorder(orientationHint) { it.setOutputFile(fd.fileDescriptor) }
                r.prepare()
                recorder = r
                startRepeating(true)
                r.start()
                done(true, null)
            } catch (e: Exception) {
                try { recorder?.release() } catch (_: Exception) {}
                recorder = null
                try { startRepeating(false) } catch (_: Exception) {}
                done(false, e.message)
            }
        }
    }

    /** Para a gravação; devolve false se o clipe ficou inválido (curto demais). */
    fun stopRecording(done: (Boolean) -> Unit) {
        handler.post {
            val r = recorder
            if (r == null) { done(false); return@post }
            var ok = true
            try { r.stop() } catch (e: RuntimeException) { ok = false }
            try { r.release() } catch (_: Exception) {}
            recorder = null
            try { startRepeating(false) } catch (_: Exception) {}
            done(ok)
        }
    }

    fun takePhoto(onJpeg: (ByteArray) -> Unit, onError: (String) -> Unit) {
        handler.post {
            val dev = device
            val s = session
            val ir = imageReader
            val ln = lens
            if (dev == null || s == null || ir == null || ln == null) { onError("Câmera não está pronta"); return@post }
            try {
                ir.setOnImageAvailableListener({ reader ->
                    val img = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                    try {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        onJpeg(bytes)
                    } finally {
                        img.close()
                    }
                }, handler)
                val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(ir.surface)
                applyCommon(b, ln, true)
                b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                b.set(CaptureRequest.JPEG_ORIENTATION, 0)
                s.capture(b.build(), null, handler)
            } catch (e: Exception) {
                onError("Erro na foto: ${e.message}")
            }
        }
    }

    private fun closeInternal() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { recorderSurface?.release() } catch (_: Exception) {}
        recorderSurface = null
        try { previewSurface?.release() } catch (_: Exception) {}
        previewSurface = null
    }

    /** Fecha tudo e espera terminar (usado ao sair do app). */
    fun closeBlocking() {
        openToken++
        val latch = CountDownLatch(1)
        handler.post {
            closeInternal()
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    fun shutdown() {
        closeBlocking()
        thread.quitSafely()
    }
}
