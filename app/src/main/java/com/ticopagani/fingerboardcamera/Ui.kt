package com.ticopagani.fingerboardcamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.abs

object Pal {
    const val BG = 0xFF0B0B0A.toInt()
    const val FG = 0xFFF2EFE6.toInt()
    const val MUTED = 0xFFA8A49A.toInt()
    const val LINE = 0xFF4A4842.toInt()
    const val ACCENT = 0xFFFFB000.toInt()
    const val REC = 0xFFFF453A.toInt()
    const val PANEL = 0xFF1C1B18.toInt()
    const val FILM = 0xFF34322C.toInt()
}

/** Valores padrão do efeito quando o FISH está ligado. */
object FishDefaults {
    const val K = 0.9f
    const val VIG = 0.6f
}

fun Context.dp(v: Number): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
val MONO: Typeface = Typeface.MONOSPACE

fun Context.label(text: String, sizeSp: Float = 10f, color: Int = Pal.MUTED, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) MONO_BOLD else MONO
        letterSpacing = 0.08f
    }

fun roundRect(ctx: Context, fill: Int, stroke: Int?, radiusDp: Int, dashed: Boolean = false): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = ctx.dp(radiusDp).toFloat()
        setColor(fill)
        if (stroke != null) {
            if (dashed) setStroke(ctx.dp(1), stroke, ctx.dp(4).toFloat(), ctx.dp(3).toFloat())
            else setStroke(ctx.dp(1), stroke)
        }
    }

/** Botão de opção: âmbar quando ligado, contorno quando desligado. */
class Chip(ctx: Context, text: String) : TextView(ctx) {
    var radiusDp = 6
    var dashedWhenOff = false
    var on = false
        set(v) {
            field = v
            refresh()
        }

    init {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 13f
        typeface = MONO_BOLD
        isClickable = true
        isFocusable = true
        includeFontPadding = false
        refresh()
    }

    fun refresh() {
        background = if (on) roundRect(context, Pal.ACCENT, Pal.ACCENT, radiusDp)
        else roundRect(context, Color.TRANSPARENT, Pal.LINE, radiusDp, dashedWhenOff)
        setTextColor(if (on) Pal.BG else Pal.FG)
        alpha = if (isEnabled) 1f else 0.35f
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        refresh()
    }
}

/** Obturador: círculo vermelho (vídeo), quadrado (gravando) ou branco (foto). */
class ShutterView(ctx: Context) : View(ctx) {
    enum class Mode { PHOTO, VIDEO, RECORDING }

    var mode = Mode.VIDEO
        set(v) {
            field = v
            contentDescription = when (v) {
                Mode.PHOTO -> "Tirar foto"
                Mode.VIDEO -> "Gravar"
                Mode.RECORDING -> "Parar gravação"
            }
            invalidate()
        }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Pal.FG
        strokeWidth = ctx.dp(3).toFloat()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Gravar"
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - ring.strokeWidth
        c.drawCircle(cx, cy, r, ring)
        when (mode) {
            Mode.PHOTO -> { fill.color = Pal.FG; c.drawCircle(cx, cy, r * 0.8f, fill) }
            Mode.VIDEO -> { fill.color = Pal.REC; c.drawCircle(cx, cy, r * 0.78f, fill) }
            Mode.RECORDING -> {
                fill.color = Pal.REC
                val s = r * 0.42f
                c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, s * 0.25f, s * 0.25f, fill)
            }
        }
    }
}

/** Linha do tempo com duas barras: início e fim do trecho em slow. */
class RangeSliderView(ctx: Context) : View(ctx) {
    var start = 0.35f
        private set
    var end = 0.65f
        private set
    var playhead = 0f
        set(v) { field = v; invalidate() }
    var highlight = true
        set(v) { field = v; invalidate() }
    var label = "SLOW"
        set(v) { field = v; invalidate() }
    var onChange: ((Float, Float) -> Unit)? = null

    private val minGap = 0.05f
    private var dragging = -1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleW = ctx.dp(14).toFloat()
    private val pad = handleW / 2f + ctx.dp(2)

    private fun xOf(f: Float) = pad + f * (width - 2 * pad)
    private fun fOf(x: Float) = ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)

    fun setRange(s: Float, e: Float) {
        start = s.coerceIn(0f, 1f - minGap)
        end = e.coerceIn(start + minGap, 1f)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val top = height * 0.1f
        val bot = height * 0.9f
        p.style = Paint.Style.FILL
        p.color = Pal.FILM
        c.drawRoundRect(pad, top, width - pad, bot, 8f, 8f, p)
        p.color = Pal.PANEL
        val step = context.dp(24).toFloat()
        var x = pad + step
        while (x < width - pad) {
            c.drawRect(x, top, x + context.dp(2), bot, p)
            x += step
        }
        if (highlight) {
            val x0 = xOf(start)
            val x1 = xOf(end)
            p.color = (Pal.ACCENT and 0x00FFFFFF) or 0x48000000
            c.drawRect(x0, top, x1, bot, p)
            p.color = Pal.ACCENT
            val sw = context.dp(2).toFloat()
            c.drawRect(x0, top, x1, top + sw, p)
            c.drawRect(x0, bot - sw, x1, bot, p)
            p.color = Pal.FG
            p.textSize = context.dp(11).toFloat()
            p.typeface = MONO_BOLD
            p.textAlign = Paint.Align.CENTER
            if (x1 - x0 > p.measureText(label) + handleW * 2) {
                c.drawText(label, (x0 + x1) / 2f, (top + bot) / 2f + p.textSize / 3f, p)
            }
        }
        p.color = Pal.REC
        val ph = xOf(playhead)
        c.drawRect(ph - 2f, 0f, ph + 2f, height.toFloat(), p)
        // As barras ficam sempre visíveis: arrastar uma delas já liga o slow.
        if (!highlight) p.alpha = 150
        drawHandle(c, xOf(start))
        drawHandle(c, xOf(end))
        p.alpha = 255
    }

    private fun drawHandle(c: Canvas, x: Float) {
        p.color = Pal.BG
        c.drawRoundRect(x - handleW / 2 - 2, 0f, x + handleW / 2 + 2, height.toFloat(), 8f, 8f, p)
        p.color = Pal.FG
        c.drawRoundRect(x - handleW / 2, 2f, x + handleW / 2, height - 2f, 6f, 6f, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        val f = fOf(e.x)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = when {
                    f <= start -> 0
                    f >= end -> 1
                    abs(f - start) <= abs(f - end) -> 0
                    else -> 1
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                move(f)
                return true
            }
            MotionEvent.ACTION_MOVE -> { move(f); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = -1; return true }
        }
        return super.onTouchEvent(e)
    }

    private fun move(f: Float) {
        if (dragging == 0) start = minOf(f, end - minGap).coerceAtLeast(0f)
        else if (dragging == 1) end = maxOf(f, start + minGap).coerceAtMost(1f)
        invalidate()
        onChange?.invoke(start, end)
    }
}
