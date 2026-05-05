package com.videoeditor

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * A transparent overlay drawn on top of the video TextureView.
 *
 * When enabled it renders:
 *  - A dimmed overlay outside the crop rectangle
 *  - A bright border with 8 handles (4 corners + 4 edge midpoints) on the crop rect
 *  - A rule-of-thirds grid inside the crop rect
 *
 * Touch gestures:
 *  - Drag a corner or edge handle → resize the crop rect
 *  - Drag the interior → translate the crop rect
 *  - All gestures are constrained to the video render rect
 *
 * Usage:
 *  1. Call [setVideoInfo] with the video's display dimensions and the host view size once ready.
 *  2. Call [setEnabled] to show/hide the overlay.
 *  3. Read [getCropRectInVideoPixels] to get the selected region in video pixel coordinates.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Fires whenever the crop rect changes, with (left, top, right, bottom) in video pixels. */
    var onCropChanged: ((Int, Int, Int, Int) -> Unit)? = null

    // ── State ─────────────────────────────────────────────────────────────────

    private var videoDisplayW = 0
    private var videoDisplayH = 0
    private val videoRect = RectF()   // video render rect within this view
    private val cropRect  = RectF()   // current crop rect in view coordinates
    private var active = false

    // ── Dimensions ────────────────────────────────────────────────────────────

    private val dp         = context.resources.displayMetrics.density
    private val handleHalf = 10f * dp     // half-size of a handle square
    private val touchSlop  = 24f * dp     // hit area around each handle
    private val minSize    = 40f * dp     // minimum crop side length in view pixels

    // ── Paints ────────────────────────────────────────────────────────────────

    private val overlayPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f * dp
    }

    // ── Touch drag ────────────────────────────────────────────────────────────

    private enum class Zone {
        NONE, INTERIOR,
        TOP_LEFT, TOP, TOP_RIGHT,
        RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT
    }

    private var activeZone = Zone.NONE
    private var lastX = 0f
    private var lastY = 0f

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Provide the video's display dimensions (after applying the rotation hint) so the overlay
     * knows where the video is rendered inside this view.
     */
    fun setVideoInfo(displayWidth: Int, displayHeight: Int) {
        videoDisplayW = displayWidth
        videoDisplayH = displayHeight
        recalcVideoRect()
    }

    fun setOverlayEnabled(enabled: Boolean) {
        active = enabled
        if (enabled && videoRect.width() > 0f && cropRect.isEmpty) resetToFull()
        visibility = if (enabled) VISIBLE else INVISIBLE
        invalidate()
    }

    fun isOverlayEnabled() = active

    /**
     * Returns the selected crop rectangle in video pixel coordinates, or null if the overlay is
     * disabled, if no video is loaded, or if the crop rect is empty / the video rect is unknown.
     */
    fun getCropRectInVideoPixels(): Rect? {
        if (!active || cropRect.isEmpty || videoRect.isEmpty || videoDisplayW <= 0) return null
        val sx = videoDisplayW / videoRect.width()
        val sy = videoDisplayH / videoRect.height()
        return Rect(
            ((cropRect.left   - videoRect.left) * sx).toInt().coerceIn(0, videoDisplayW),
            ((cropRect.top    - videoRect.top)  * sy).toInt().coerceIn(0, videoDisplayH),
            ((cropRect.right  - videoRect.left) * sx).toInt().coerceIn(0, videoDisplayW),
            ((cropRect.bottom - videoRect.top)  * sy).toInt().coerceIn(0, videoDisplayH),
        )
    }

    // ── Layout callbacks ──────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalcVideoRect()
    }

    private fun recalcVideoRect() {
        if (width == 0 || height == 0 || videoDisplayW == 0 || videoDisplayH == 0) return
        val scale = min(width.toFloat() / videoDisplayW, height.toFloat() / videoDisplayH)
        val rw = videoDisplayW * scale
        val rh = videoDisplayH * scale
        val rl = (width  - rw) / 2f
        val rt = (height - rh) / 2f
        videoRect.set(rl, rt, rl + rw, rt + rh)
        if (active && cropRect.isEmpty) resetToFull()
    }

    private fun resetToFull() {
        cropRect.set(videoRect)
        fireCropChanged()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (!active || cropRect.isEmpty) return

        // Dim the area outside the crop rect
        val outside = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRect(cropRect, Path.Direction.CCW)
        }
        canvas.drawPath(outside, overlayPaint)

        // Rule-of-thirds grid
        val cw3 = cropRect.width()  / 3f
        val ch3 = cropRect.height() / 3f
        for (i in 1..2) {
            canvas.drawLine(cropRect.left + i * cw3, cropRect.top,
                            cropRect.left + i * cw3, cropRect.bottom, gridPaint)
            canvas.drawLine(cropRect.left, cropRect.top + i * ch3,
                            cropRect.right, cropRect.top + i * ch3, gridPaint)
        }

        // Border
        canvas.drawRect(cropRect, borderPaint)

        // Handles
        val hs = handleHalf
        val handlePositions = listOf(
            cropRect.left     to cropRect.top,
            cropRect.centerX() to cropRect.top,
            cropRect.right    to cropRect.top,
            cropRect.right    to cropRect.centerY(),
            cropRect.right    to cropRect.bottom,
            cropRect.centerX() to cropRect.bottom,
            cropRect.left     to cropRect.bottom,
            cropRect.left     to cropRect.centerY(),
        )
        handlePositions.forEach { (cx, cy) ->
            canvas.drawRect(cx - hs, cy - hs, cx + hs, cy + hs, handlePaint)
        }
    }

    // ── Touch handling ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active) return false
        val x = event.x
        val y = event.y
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeZone = detectZone(x, y)
                if (activeZone != Zone.NONE) {
                    lastX = x; lastY = y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    true
                } else false
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeZone == Zone.NONE) return false
                applyDrag(x - lastX, y - lastY)
                lastX = x; lastY = y
                invalidate()
                fireCropChanged()
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeZone = Zone.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                true
            }
            else -> false
        }
    }

    private fun detectZone(x: Float, y: Float): Zone {
        val s = touchSlop
        val inL = abs(x - cropRect.left)    < s
        val inR = abs(x - cropRect.right)   < s
        val inT = abs(y - cropRect.top)     < s
        val inB = abs(y - cropRect.bottom)  < s
        val inCX = abs(x - cropRect.centerX()) < s
        val inCY = abs(y - cropRect.centerY()) < s
        val inX = x in cropRect.left..cropRect.right
        val inY = y in cropRect.top..cropRect.bottom
        return when {
            inL && inT    -> Zone.TOP_LEFT
            inR && inT    -> Zone.TOP_RIGHT
            inL && inB    -> Zone.BOTTOM_LEFT
            inR && inB    -> Zone.BOTTOM_RIGHT
            inT && inCX   -> Zone.TOP
            inB && inCX   -> Zone.BOTTOM
            inL && inCY   -> Zone.LEFT
            inR && inCY   -> Zone.RIGHT
            inX && inY    -> Zone.INTERIOR
            else          -> Zone.NONE
        }
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val r = RectF(cropRect)
        when (activeZone) {
            Zone.INTERIOR -> {
                r.offset(dx, dy)
                if (r.left < videoRect.left)   r.offset(videoRect.left   - r.left,   0f)
                if (r.right > videoRect.right)  r.offset(videoRect.right  - r.right,  0f)
                if (r.top < videoRect.top)      r.offset(0f, videoRect.top   - r.top)
                if (r.bottom > videoRect.bottom) r.offset(0f, videoRect.bottom - r.bottom)
            }
            Zone.LEFT         -> r.left   = (r.left   + dx).coerceIn(videoRect.left,  r.right  - minSize)
            Zone.RIGHT        -> r.right  = (r.right  + dx).coerceIn(r.left + minSize, videoRect.right)
            Zone.TOP          -> r.top    = (r.top    + dy).coerceIn(videoRect.top,   r.bottom - minSize)
            Zone.BOTTOM       -> r.bottom = (r.bottom + dy).coerceIn(r.top  + minSize, videoRect.bottom)
            Zone.TOP_LEFT     -> {
                r.left = (r.left + dx).coerceIn(videoRect.left,  r.right  - minSize)
                r.top  = (r.top  + dy).coerceIn(videoRect.top,   r.bottom - minSize)
            }
            Zone.TOP_RIGHT    -> {
                r.right = (r.right + dx).coerceIn(r.left + minSize, videoRect.right)
                r.top   = (r.top   + dy).coerceIn(videoRect.top,    r.bottom - minSize)
            }
            Zone.BOTTOM_LEFT  -> {
                r.left   = (r.left   + dx).coerceIn(videoRect.left,  r.right  - minSize)
                r.bottom = (r.bottom + dy).coerceIn(r.top + minSize,  videoRect.bottom)
            }
            Zone.BOTTOM_RIGHT -> {
                r.right  = (r.right  + dx).coerceIn(r.left + minSize, videoRect.right)
                r.bottom = (r.bottom + dy).coerceIn(r.top  + minSize, videoRect.bottom)
            }
            Zone.NONE -> {}
        }
        cropRect.set(r)
    }

    private fun fireCropChanged() {
        getCropRectInVideoPixels()?.let { onCropChanged?.invoke(it.left, it.top, it.right, it.bottom) }
    }
}
