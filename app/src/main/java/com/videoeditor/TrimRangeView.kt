package com.videoeditor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * A custom view that renders a video thumbnail strip with two draggable trim handles.
 *
 * ┌──────────────────────────────────────────────────────┐
 * │▓▓▓│ thumbnail  |  thumbnail  |  thumbnail  |  thumbnail │▓▓▓│
 * └──────────────────────────────────────────────────────┘
 *   ↑ start handle                             end handle ↑
 *
 * Areas outside [startTime, endTime] are dimmed.
 * A white line tracks the current playback position.
 * Tapping inside the selected region seeks without moving handles.
 */
class TrimRangeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ── Public callbacks ──────────────────────────────────────────────────────

    var onStartTimeChanged: ((Long) -> Unit)? = null
    var onEndTimeChanged: ((Long) -> Unit)? = null
    /** Invoked when the user taps inside the selected region (seek, not trim). */
    var onSeek: ((Long) -> Unit)? = null

    // ── State ─────────────────────────────────────────────────────────────────

    private var durationMs: Long = 0L
    private var startMs: Long = 0L
    private var endMs: Long = 0L
    private var currentMs: Long = 0L
    private val thumbnails = mutableListOf<Bitmap>()

    // ── Touch drag tracking ───────────────────────────────────────────────────

    private enum class Drag { NONE, START, END }
    private var drag = Drag.NONE

    // ── Dimensions (density-aware) ────────────────────────────────────────────

    private val dp = context.resources.displayMetrics.density
    private val handleW = 20f * dp        // width of each side handle tab
    private val cornerR = 6f * dp        // corner radius
    private val borderW = 3.5f * dp     // selection border stroke width
    private val touchSlop = 40f * dp    // pixel radius for handle hit detection

    // ── Paints ────────────────────────────────────────────────────────────────

    private val thumbPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val overlayPaint = Paint().apply {
        color = Color.argb(155, 0, 0, 0)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6D00")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6D00")
        style = Paint.Style.STROKE
        strokeWidth = borderW
    }
    private val seekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f * dp
        style = Paint.Style.STROKE
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#1C1C1C")
    }

    // Clip path reused across draws to avoid allocations
    private val clipPath = Path()
    private val clipRect = RectF()

    // ── Public API ────────────────────────────────────────────────────────────

    fun setDuration(ms: Long) {
        durationMs = ms
        invalidate()
    }

    fun setStartTime(ms: Long) {
        startMs = ms.coerceIn(0L, (endMs - 100L).coerceAtLeast(0L))
        invalidate()
    }

    fun setEndTime(ms: Long) {
        endMs = ms.coerceIn((startMs + 100L).coerceAtMost(durationMs), durationMs)
        invalidate()
    }

    fun setCurrentTime(ms: Long) {
        if (ms == currentMs) return
        currentMs = ms
        invalidate()
    }

    fun getStartTime(): Long = startMs
    fun getEndTime(): Long = endMs

    fun setThumbnails(bitmaps: List<Bitmap>) {
        thumbnails.clear()
        thumbnails.addAll(bitmaps)
        invalidate()
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    /** Convert a time (ms) to an X pixel coordinate on the timeline strip. */
    private fun timeToX(ms: Long): Float {
        if (durationMs <= 0L || width == 0) return handleW
        return handleW + ms.toFloat() / durationMs.toFloat() * (width - 2f * handleW)
    }

    /** Convert an X pixel coordinate to a time (ms). */
    private fun xToTime(x: Float): Long {
        if (durationMs <= 0L || width <= 2 * handleW) return 0L
        return ((x - handleW) / (width - 2f * handleW) * durationMs)
            .toLong()
            .coerceIn(0L, durationMs)
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // Clip entire view to rounded rectangle
        clipRect.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(clipRect, cornerR, cornerR, Path.Direction.CW)
        canvas.clipPath(clipPath)

        // Background (visible when no thumbnails yet)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // ── Thumbnails ──
        if (thumbnails.isNotEmpty()) {
            val stripLeft = handleW
            val stripRight = w - handleW
            val thumbW = (stripRight - stripLeft) / thumbnails.size
            thumbnails.forEachIndexed { i, bmp ->
                val left = stripLeft + i * thumbW
                val dst = RectF(left, 0f, left + thumbW, h)
                canvas.drawBitmap(bmp, null, dst, thumbPaint)
            }
        }

        val startX = timeToX(startMs)
        val endX = timeToX(endMs)

        // ── Dim regions outside selection ──
        canvas.drawRect(0f, 0f, startX, h, overlayPaint)
        canvas.drawRect(endX, 0f, w, h, overlayPaint)

        // ── Selection top / bottom border lines ──
        val bo = borderW / 2f
        canvas.drawLine(startX, bo, endX, bo, borderPaint)
        canvas.drawLine(startX, h - bo, endX, h - bo, borderPaint)

        // ── Left (start) handle ──
        val startTabRect = RectF(startX - handleW, 0f, startX, h)
        canvas.drawRoundRect(startTabRect, cornerR, cornerR, handlePaint)
        drawArrow(canvas, startX - handleW / 2f, h / 2f, pointRight = true)

        // ── Right (end) handle ──
        val endTabRect = RectF(endX, 0f, endX + handleW, h)
        canvas.drawRoundRect(endTabRect, cornerR, cornerR, handlePaint)
        drawArrow(canvas, endX + handleW / 2f, h / 2f, pointRight = false)

        // ── Playback position line (only inside selection) ──
        if (durationMs > 0L && currentMs in startMs..endMs) {
            val seekX = timeToX(currentMs)
            canvas.drawLine(seekX, 0f, seekX, h, seekPaint)
        }
    }

    /** Draw a small filled triangle arrow centred at (cx, cy). */
    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, pointRight: Boolean) {
        val s = 5f * dp
        val path = Path().apply {
            if (pointRight) {
                moveTo(cx - s, cy - s * 1.6f)
                lineTo(cx + s, cy)
                lineTo(cx - s, cy + s * 1.6f)
            } else {
                moveTo(cx + s, cy - s * 1.6f)
                lineTo(cx - s, cy)
                lineTo(cx + s, cy + s * 1.6f)
            }
            close()
        }
        canvas.drawPath(path, arrowPaint)
    }

    // ── Touch handling ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (durationMs <= 0L) return false

        val x = event.x
        val startX = timeToX(startMs)
        val endX = timeToX(endMs)

        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                drag = when {
                    abs(x - startX) < touchSlop -> Drag.START
                    abs(x - endX) < touchSlop   -> Drag.END
                    else                         -> Drag.NONE
                }
                if (drag != Drag.NONE) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    true
                } else if (x in startX..endX) {
                    // Seek to tapped position
                    onSeek?.invoke(xToTime(x))
                    true
                } else {
                    false // allow parent to scroll
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val t = xToTime(x)
                when (drag) {
                    Drag.START -> {
                        startMs = t.coerceIn(0L, (endMs - 500L).coerceAtLeast(0L))
                        onStartTimeChanged?.invoke(startMs)
                        invalidate()
                        true
                    }
                    Drag.END -> {
                        endMs = t.coerceIn((startMs + 500L).coerceAtMost(durationMs), durationMs)
                        onEndTimeChanged?.invoke(endMs)
                        invalidate()
                        true
                    }
                    Drag.NONE -> false
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag != Drag.NONE) {
                    drag = Drag.NONE
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                true
            }

            else -> false
        }
    }
}
