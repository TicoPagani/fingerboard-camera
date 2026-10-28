package com.ticopagani.fingerboardcamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Clipes gravados ficam na pasta do app até serem exportados. O nome guarda o formato e se foi em slow. */
object Clips {
    data class Meta(val aspect: Float, val slow: Boolean)

    fun dir(ctx: Context): File =
        (ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(ctx.filesDir, "clips")).also { it.mkdirs() }

    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun newFile(ctx: Context, aspect: Float, slow: Boolean): File =
        File(dir(ctx), "FBC_${stamp()}_${(aspect * 1000).roundToInt()}_${if (slow) "s" else "n"}.mp4")

    fun last(ctx: Context): File? =
        dir(ctx).listFiles { f -> f.name.endsWith(".mp4") && f.length() > 0 }?.maxByOrNull { it.lastModified() }

    fun meta(f: File): Meta {
        val parts = f.nameWithoutExtension.split("_")
        val a = parts.getOrNull(3)?.toIntOrNull()?.let { it / 1000f } ?: (16f / 9f)
        return Meta(a, parts.getOrNull(4) == "s")
    }
}

/** Salva na galeria do celular (Fotos/Filmes › FingerboardCamera). */
object Gallery {
    fun saveJpeg(ctx: Context, bmp: Bitmap, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FingerboardCamera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        return uri
    }

    fun saveVideo(ctx: Context, file: File, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FingerboardCamera")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        cr.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        return uri
    }
}

/** Aplica o fisheye + vinheta + corte de formato numa foto (mesma conta do shader). */
object PhotoProcessor {
    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun process(jpeg: ByteArray, rotationCw: Int, aspect: Float, k: Float, vig: Float, maxSide: Int = 3000): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 4200) sample *= 2
        val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("Foto inválida")
        val src = if (rotationCw % 360 != 0) {
            val m = Matrix()
            m.postRotate(rotationCw.toFloat())
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it != raw) raw.recycle() }
        } else raw

        val sw = src.width
        val sh = src.height
        val srcAspect = sw.toFloat() / sh
        val cropW: Float
        val cropH: Float
        if (srcAspect > aspect) { cropH = sh.toFloat(); cropW = sh * aspect } else { cropW = sw.toFloat(); cropH = sw / aspect }
        val x0 = (sw - cropW) / 2f
        val y0 = (sh - cropH) / 2f
        val scale = min(1f, maxSide / max(cropW, cropH))
        val ow = (cropW * scale).toInt().coerceAtLeast(1)
        val oh = (cropH * scale).toInt().coerceAtLeast(1)

        val pixels = IntArray(sw * sh)
        src.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        src.recycle()

        val out = IntArray(ow * oh)
        val norm = 0.25f * (aspect * aspect + 1f)
        val vStart = 1.05f + (0.62f - 1.05f) * vig
        for (y in 0 until oh) {
            val py = (y + 0.5f) / oh - 0.5f
            for (x in 0 until ow) {
                val px = (x + 0.5f) / ow - 0.5f
                val ax = px * aspect
                val r2 = (ax * ax + py * py) / norm
                val f = (1f + k * r2) / (1f + k)
                val sx = px * f + 0.5f
                val sy = py * f + 0.5f
                var color = 0xFF000000.toInt()
                if (sx in 0f..1f && sy in 0f..1f) {
                    val ix = (x0 + sx * cropW).toInt().coerceIn(0, sw - 1)
                    val iy = (y0 + sy * cropH).toInt().coerceIn(0, sh - 1)
                    color = pixels[iy * sw + ix]
                }
                if (vig > 0f) {
                    val v = 1f - smoothstep(vStart, vStart + 0.28f, sqrt(r2))
                    if (v < 0.999f) {
                        val r = ((color shr 16 and 0xFF) * v).toInt()
                        val g = ((color shr 8 and 0xFF) * v).toInt()
                        val b = ((color and 0xFF) * v).toInt()
                        color = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                out[y * ow + x] = color
            }
        }
        return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
    }
}
