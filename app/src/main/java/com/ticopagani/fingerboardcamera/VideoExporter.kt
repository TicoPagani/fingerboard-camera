package com.ticopagani.fingerboardcamera

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Exporta o clipe: decodifica o vídeo limpo, aplica fisheye/vinheta/corte/rotação via OpenGL
 * e reencoda em H.264, esticando o trecho escolhido (slow) ao reescrever os tempos dos quadros.
 */
class VideoExporter(private val ctx: Context) {

    class Params(
        val aspect: Float,
        val k: Float,
        val vig: Float,
        val slowStartSec: Double,
        val slowEndSec: Double,
        val mult: Int
    )

    private fun even(v: Float): Int = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)

    private fun outSize(aspect: Float): Pair<Int, Int> =
        if (aspect >= 1f) Pair(even(1080 * aspect), 1080) else Pair(1080, even(1080 / aspect))

    private fun mapTime(tUs: Long, p: Params): Long {
        if (p.mult <= 1) return tUs
        val a = (p.slowStartSec * 1_000_000).toLong()
        val b = (p.slowEndSec * 1_000_000).toLong()
        return when {
            tUs < a -> tUs
            tUs <= b -> a + (tUs - a) * p.mult
            else -> a + (b - a) * p.mult + (tUs - b)
        }
    }

    fun export(input: File, p: Params, progress: (Float) -> Unit): File {
        val retriever = MediaMetadataRetriever()
        val rotation = try {
            retriever.setDataSource(input.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } finally {
            retriever.release()
        }

        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        var track = -1
        var inFmt: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                track = i; inFmt = f; break
            }
        }
        if (track < 0 || inFmt == null) {
            extractor.release()
            throw IllegalStateException("Vídeo sem imagem")
        }
        extractor.selectTrack(track)
        val w = inFmt.getInteger(MediaFormat.KEY_WIDTH)
        val h = inFmt.getInteger(MediaFormat.KEY_HEIGHT)
        val durUs = if (inFmt.containsKey(MediaFormat.KEY_DURATION)) inFmt.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1) else 1L
        val srcAspect = if (rotation % 180 != 0) h.toFloat() / w else w.toFloat() / h

        val (outW, outH) = outSize(p.aspect)
        val out = File(ctx.cacheDir, "export_${System.currentTimeMillis()}.mp4")

        val encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 20_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encSurface = encoder.createInputSurface()
        encoder.start()

        val egl = EglCore(encSurface)
        val gl = FisheyeGl()
        gl.init()
        val tex = gl.createOesTexture()
        val st = SurfaceTexture(tex)
        val frames = Semaphore(0)
        val listenerThread = HandlerThread("export-frames").apply { start() }
        st.setOnFrameAvailableListener({ frames.release() }, Handler(listenerThread.looper))
        val decSurface = Surface(st)

        // O decoder gira o quadro sozinho ao desenhar na Surface.
        inFmt.setInteger(MediaFormat.KEY_ROTATION, rotation)
        val decoder = MediaCodec.createDecoderByType(inFmt.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inFmt, decSurface, null, 0)
        decoder.start()

        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1
        var muxStarted = false
        val info = MediaCodec.BufferInfo()
        val encInfo = MediaCodec.BufferInfo()
        val stM = FloatArray(16)
        val (cx, cy) = FisheyeGl.crop(srcAspect, p.aspect)

        var inputDone = false
        var decoderDone = false
        var encoderDone = false
        var firstPts = -1L
        var lastOut = -1L
        val minGap = 1_000_000L / 60 - 3_000

        fun drainEncoder(endOfStream: Boolean) {
            var tries = 0
            while (true) {
                val idx = encoder.dequeueOutputBuffer(encInfo, if (endOfStream) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                        if (++tries > 500) throw IllegalStateException("Encoder travou")
                    }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        muxTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxStarted = true
                    }
                    idx >= 0 -> {
                        val buf = encoder.getOutputBuffer(idx)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                        if (buf != null && encInfo.size > 0 && muxStarted) {
                            buf.position(encInfo.offset)
                            buf.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(muxTrack, buf, encInfo)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                            return
                        }
                    }
                }
            }
        }

        try {
            while (!encoderDone) {
                if (!inputDone) {
                    val ii = decoder.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = decoder.getInputBuffer(ii)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                drainEncoder(false)

                if (!decoderDone) {
                    val oi = decoder.dequeueOutputBuffer(info, 10_000)
                    if (oi >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        var render = false
                        var outPts = 0L
                        if (!(eos && info.size == 0)) {
                            if (firstPts < 0) firstPts = info.presentationTimeUs
                            outPts = mapTime(info.presentationTimeUs - firstPts, p)
                            render = lastOut < 0 || outPts - lastOut >= minGap
                        }
                        decoder.releaseOutputBuffer(oi, render)
                        if (render) {
                            if (!frames.tryAcquire(3, TimeUnit.SECONDS)) throw IllegalStateException("Tempo esgotado lendo o vídeo")
                            st.updateTexImage()
                            st.getTransformMatrix(stM)
                            GLES20.glViewport(0, 0, outW, outH)
                            GLES20.glClearColor(0f, 0f, 0f, 1f)
                            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                            gl.draw(tex, FisheyeGl.texMatrix(stM, 0, false), cx, cy, p.aspect, p.k, p.vig)
                            egl.setPresentationTime(outPts * 1000)
                            egl.swap()
                            lastOut = outPts
                            progress(((info.presentationTimeUs - firstPts).toFloat() / durUs).coerceIn(0f, 1f))
                        }
                        if (eos) {
                            encoder.signalEndOfInputStream()
                            decoderDone = true
                        }
                    }
                }

                if (decoderDone) drainEncoder(true)
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            try { if (muxStarted) muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { decSurface.release() } catch (_: Exception) {}
            try { st.release() } catch (_: Exception) {}
            try { egl.release() } catch (_: Exception) {}
            try { encSurface.release() } catch (_: Exception) {}
            listenerThread.quitSafely()
        }
        if (!muxStarted || out.length() == 0L) throw IllegalStateException("A exportação não gerou vídeo")
        return out
    }
}
