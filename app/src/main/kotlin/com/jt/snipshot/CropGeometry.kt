package com.jt.snipshot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min

/** Pure geometry helpers used by CropOverlay. Kept separate so they are unit-testable. */
object CropGeometry {

    fun normalize(a: Offset, b: Offset): Rect {
        val l = min(a.x, b.x); val t = min(a.y, b.y)
        val r = max(a.x, b.x); val btm = max(a.y, b.y)
        return Rect(l, t, r, btm)
    }

    /** Letterbox `(srcW, srcH)` into `dst`, returning the on-screen rect. */
    fun fitRect(srcW: Int, srcH: Int, dst: Size): Rect {
        if (srcW == 0 || srcH == 0 || dst.width <= 0f || dst.height <= 0f) {
            return Rect(0f, 0f, dst.width, dst.height)
        }
        val srcRatio = srcW.toFloat() / srcH
        val dstRatio = dst.width / dst.height
        val w: Float; val h: Float
        if (srcRatio > dstRatio) { w = dst.width; h = w / srcRatio }
        else { h = dst.height; w = h * srcRatio }
        val left = (dst.width - w) / 2f
        val top = (dst.height - h) / 2f
        return Rect(left, top, left + w, top + h)
    }

    /**
     * Translate a view-space rect (e.g., user's finger drag) into image-pixel coordinates.
     * Clamps to image bounds so the resulting rect is always crop-safe.
     */
    fun viewToImageRect(viewRect: Rect, canvas: IntSize, src: IntSize): Rect {
        if (canvas.width <= 0 || canvas.height <= 0 || src.width <= 0 || src.height <= 0) {
            return Rect(0f, 0f, 0f, 0f)
        }
        val fit = fitRect(src.width, src.height, Size(canvas.width.toFloat(), canvas.height.toFloat()))
        if (fit.width <= 0f || fit.height <= 0f) return Rect(0f, 0f, 0f, 0f)
        val scaleX = src.width / fit.width
        val scaleY = src.height / fit.height
        val l = ((viewRect.left - fit.left) * scaleX).coerceIn(0f, src.width.toFloat())
        val t = ((viewRect.top - fit.top) * scaleY).coerceIn(0f, src.height.toFloat())
        val r = ((viewRect.right - fit.left) * scaleX).coerceIn(0f, src.width.toFloat())
        val b = ((viewRect.bottom - fit.top) * scaleY).coerceIn(0f, src.height.toFloat())
        return Rect(l, t, r, b)
    }
}
