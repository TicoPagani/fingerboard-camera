package com.ticopagani.fingerboardcamera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
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

/**
 * Os clipes brutos (sem efeito) são gravados direto na galeria, em Filmes › FingerboardCamera › Originais.
 * O nome do arquivo guarda o formato escolhido e se foi gravado em slow.
 */
object Clips {
    /** slowFps: 0 = gravado sem slow; 120 ou 240 = gravado com SLOWMO nesse fps. aspect null = desconhecido. */
    data class Meta(val aspect: Float?, val slowFps: Int) {
        val slow get() = slowFps > 0
    }

    class Recording(val uri: Uri, val fd: ParcelFileDescriptor)

    private val ORIGINALS = Environment.DIRECTORY_MOVIES + "/FingerboardCamera/Originais"
    private fun collection() = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun name(aspect: Float, slowFps: Int): String {
        val tag = when {
            slowFps >= 240 -> "t"
            slowFps > 0 -> "s"
            else -> "n"
        }
        return "FBC_${stamp()}_${(aspect * 1000).roundToInt()}_$tag.mp4"
    }

    /** Cria o vídeo na galeria (oculto até terminar) e abre pra gravação. */
    fun create(ctx: Context, aspect: Float, slowFps: Int): Recording? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name(aspect, slowFps))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, ORIGINALS)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(collection(), values) ?: return null
        val fd = try { ctx.contentResolver.openFileDescriptor(uri, "rw") } catch (e: Exception) { null }
        if (fd == null) {
            ctx.contentResolver.delete(uri, null, null)
            return null
        }
        return Recording(uri, fd)
    }

    fun finish(ctx: Context, rec: Recording, ok: Boolean) {
        try { rec.fd.close() } catch (_: Exception) {}
        if (ok) {
            val v = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            ctx.contentResolver.update(rec.uri, v, null, null)
        } else {
            try { ctx.contentResolver.delete(rec.uri, null, null) } catch (_: Exception) {}
        }
    }

    /** Último clipe gravado pelo app. */
    fun last(ctx: Context): Uri? {
        val proj = arrayOf(MediaStore.Video.Media._ID)
        val sel = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("$ORIGINALS%", "FBC_%")
        ctx.contentResolver.query(collection(), proj, sel, args, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(collection(), c.getLong(0))
        }
        return null
    }

    fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) { null }

    /** Lê o formato/slow pelo nome; vídeos de fora do app ficam com formato do próprio vídeo. */
    fun meta(name: String?): Meta {
        val parts = (name ?: "").substringBeforeLast('.').split("_")
        if (parts.getOrNull(0) != "FBC") return Meta(null, 0)
        val a = parts.getOrNull(3)?.toIntOrNull()?.let { it / 1000f }
        val slowFps = when (parts.getOrNull(4)) {
            "t" -> 240
            "s" -> 120
            else -> 0
        }
        return Meta(a, slowFps)
    }

    fun delete(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.delete(uri, null, null) > 0
    } catch (e: Exception) { false }

    /** Move clipes da versão antiga (pasta escondida do app) pra galeria e libera o espaço. */
    fun migrateOld(ctx: Context) {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return
        val old = dir.listFiles { f -> f.name.startsWith("FBC_") && f.name.endsWith(".mp4") } ?: return
        for (f in old.sortedBy { it.lastModified() }) {
            try {
                if (f.length() == 0L) { f.delete(); continue }
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, ORIGINALS)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(collection(), values) ?: continue
                ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
                f.delete()
            } catch (_: Exception) {
            }
        }
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
