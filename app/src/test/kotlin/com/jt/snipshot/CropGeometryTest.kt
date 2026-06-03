package com.jt.snipshot

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropGeometryTest {

    private val eps = 0.5f

    private fun assertRectEq(expected: Rect, actual: Rect, msg: String = "") {
        assertEquals("$msg left",   expected.left,   actual.left,   eps)
        assertEquals("$msg top",    expected.top,    actual.top,    eps)
        assertEquals("$msg right",  expected.right,  actual.right,  eps)
        assertEquals("$msg bottom", expected.bottom, actual.bottom, eps)
    }

    // ─── normalize ────────────────────────────────────────────────────────────

    @Test fun normalize_handlesAllDragDirections() {
        val r1 = CropGeometry.normalize(Offset(10f, 20f), Offset(100f, 200f))
        assertRectEq(Rect(10f, 20f, 100f, 200f), r1, "top-left to bottom-right")

        val r2 = CropGeometry.normalize(Offset(100f, 200f), Offset(10f, 20f))
        assertRectEq(Rect(10f, 20f, 100f, 200f), r2, "bottom-right to top-left")

        val r3 = CropGeometry.normalize(Offset(100f, 20f), Offset(10f, 200f))
        assertRectEq(Rect(10f, 20f, 100f, 200f), r3, "top-right to bottom-left")
    }

    @Test fun normalize_zeroSize() {
        val r = CropGeometry.normalize(Offset(50f, 50f), Offset(50f, 50f))
        assertEquals(0f, r.width, eps)
        assertEquals(0f, r.height, eps)
    }

    // ─── fitRect ──────────────────────────────────────────────────────────────

    @Test fun fitRect_squareImageInWideCanvas() {
        // 100x100 image into 200x100 canvas → letterboxed centered
        val r = CropGeometry.fitRect(100, 100, Size(200f, 100f))
        assertRectEq(Rect(50f, 0f, 150f, 100f), r)
    }

    @Test fun fitRect_squareImageInTallCanvas() {
        // 100x100 → 100x200 → letterboxed vertically
        val r = CropGeometry.fitRect(100, 100, Size(100f, 200f))
        assertRectEq(Rect(0f, 50f, 100f, 150f), r)
    }

    @Test fun fitRect_phonePortraitInFoldInnerLandscape() {
        // Phone-shaped screenshot 1080x2400 fit into the Fold's roughly-square inner display 2208x1840.
        // Letterboxed on left/right.
        val r = CropGeometry.fitRect(1080, 2400, Size(2208f, 1840f))
        assertEquals(0f, r.top, eps)
        assertEquals(1840f, r.bottom, eps)
        // Width = 1840 * 1080/2400 = 828
        assertEquals(828f, r.width, eps)
        // Centered horizontally
        assertEquals((2208f - 828f) / 2f, r.left, eps)
    }

    @Test fun fitRect_foldOuterScreenshotInFoldInner() {
        // Inner-display screenshot 2208x1840 displayed in same-size canvas → exact fit.
        val r = CropGeometry.fitRect(2208, 1840, Size(2208f, 1840f))
        assertRectEq(Rect(0f, 0f, 2208f, 1840f), r)
    }

    @Test fun fitRect_zeroSrcDoesNotCrash() {
        val r = CropGeometry.fitRect(0, 0, Size(100f, 100f))
        // Should return something sane, not NaN.
        assertTrue(!r.left.isNaN() && !r.right.isNaN())
    }

    @Test fun fitRect_zeroDstDoesNotCrash() {
        val r = CropGeometry.fitRect(100, 100, Size(0f, 0f))
        assertTrue(!r.left.isNaN())
    }

    // ─── viewToImageRect ──────────────────────────────────────────────────────

    @Test fun viewToImageRect_fullCanvasMapsToFullImage() {
        val canvas = IntSize(2208, 1840)
        val src = IntSize(2208, 1840)  // exact fit
        val full = Rect(0f, 0f, 2208f, 1840f)
        val mapped = CropGeometry.viewToImageRect(full, canvas, src)
        assertRectEq(Rect(0f, 0f, 2208f, 1840f), mapped)
    }

    @Test fun viewToImageRect_centerHalfMapsCorrectly() {
        // 1000x1000 image in 1000x1000 canvas. Drag a centered 500x500 box.
        val canvas = IntSize(1000, 1000)
        val src = IntSize(1000, 1000)
        val drag = Rect(250f, 250f, 750f, 750f)
        val mapped = CropGeometry.viewToImageRect(drag, canvas, src)
        assertRectEq(Rect(250f, 250f, 750f, 750f), mapped)
    }

    @Test fun viewToImageRect_letterboxedImage_mapsCorrectly() {
        // 1080x2400 phone screenshot in 2208x1840 canvas.
        // fitRect → width=828, centered horizontally, full height.
        // letterbox left = (2208-828)/2 = 690
        // letterbox right = 690 + 828 = 1518
        val canvas = IntSize(2208, 1840)
        val src = IntSize(1080, 2400)

        // Drag from top-left of the image area to bottom-right.
        val drag = Rect(690f, 0f, 1518f, 1840f)
        val mapped = CropGeometry.viewToImageRect(drag, canvas, src)
        assertRectEq(Rect(0f, 0f, 1080f, 2400f), mapped, "full image area")
    }

    @Test fun viewToImageRect_dragInLetterboxIsClampedToImage() {
        // User drags entirely in the dimmed letterbox region (left side).
        // Result rect should be clamped to image bounds (width 0 on the left).
        val canvas = IntSize(2208, 1840)
        val src = IntSize(1080, 2400)
        // image left edge at 690; dragging in 100..500 (entirely left of image)
        val drag = Rect(100f, 100f, 500f, 500f)
        val mapped = CropGeometry.viewToImageRect(drag, canvas, src)
        // Both ends should clamp to 0 on the left side.
        assertEquals(0f, mapped.left, eps)
        assertEquals(0f, mapped.right, eps)
    }

    @Test fun viewToImageRect_partialOverlapClampedToImage() {
        // Drag starts in letterbox, ends in image. Left edge clamps to 0.
        val canvas = IntSize(2208, 1840)
        val src = IntSize(1080, 2400)
        // Drag from x=600 (in letterbox, left=690) to x=1200 (in image).
        val drag = Rect(600f, 500f, 1200f, 1200f)
        val mapped = CropGeometry.viewToImageRect(drag, canvas, src)
        assertEquals(0f, mapped.left, eps)
        assertTrue("Right should be > 0", mapped.right > 0f)
    }

    @Test fun viewToImageRect_zeroSizesReturnEmpty() {
        val r = CropGeometry.viewToImageRect(
            Rect(0f, 0f, 100f, 100f), IntSize(0, 0), IntSize(100, 100)
        )
        assertEquals(0f, r.width, eps)

        val r2 = CropGeometry.viewToImageRect(
            Rect(0f, 0f, 100f, 100f), IntSize(100, 100), IntSize(0, 0)
        )
        assertEquals(0f, r2.width, eps)
    }

    // ─── additional edge cases ────────────────────────────────────────────────

    @Test fun fitRect_wideScreenshotInPortraitCanvas_letterboxesVertically() {
        // 2400x1080 landscape into 1080x2400 portrait → tall letterbox.
        // h = 1080 / (2400/1080) = 486; top = (2400-486)/2 = 957
        val r = CropGeometry.fitRect(2400, 1080, Size(1080f, 2400f))
        assertRectEq(Rect(0f, 957f, 1080f, 1443f), r)
    }

    @Test fun fitRect_zeroSrcWidthOnly() {
        val r = CropGeometry.fitRect(0, 100, Size(100f, 100f))
        // Degenerate: should return canvas-shaped rect, no NaN.
        assertTrue(!r.left.isNaN() && !r.right.isNaN())
        assertRectEq(Rect(0f, 0f, 100f, 100f), r)
    }

    @Test fun fitRect_zeroSrcHeightOnly() {
        val r = CropGeometry.fitRect(100, 0, Size(100f, 100f))
        assertTrue(!r.top.isNaN() && !r.bottom.isNaN())
        assertRectEq(Rect(0f, 0f, 100f, 100f), r)
    }

    @Test fun fitRect_fractionalScale_centeringPreserved() {
        // 1001x777 in 333x222 — non-clean ratios. Either letterboxed h or w.
        // srcRatio ≈ 1.288, dstRatio = 1.5 → src narrower → h = 222, w = 222 * 1.288 ≈ 285.83
        val r = CropGeometry.fitRect(1001, 777, Size(333f, 222f))
        assertEquals(222f, r.height, eps)
        // Centered: left == (333 - w)/2; w = r.width
        assertEquals((333f - r.width) / 2f, r.left, eps)
    }

    @Test fun viewToImageRect_wideImageVerticalLetterbox_fullImageArea() {
        // 2400x1080 image (wide) in 1080x2400 canvas (tall). Letterbox top/bottom.
        // fit = (0, 957, 1080, 1443) → dragging full image rect maps to full image.
        val r = CropGeometry.viewToImageRect(
            Rect(0f, 957f, 1080f, 1443f),
            IntSize(1080, 2400), IntSize(2400, 1080)
        )
        assertRectEq(Rect(0f, 0f, 2400f, 1080f), r)
    }

    @Test fun viewToImageRect_dragBelowImageClampsToBottom() {
        // Drag entirely below the image (in bottom letterbox).
        val r = CropGeometry.viewToImageRect(
            Rect(100f, 1600f, 500f, 2200f),
            IntSize(1080, 2400), IntSize(2400, 1080)
        )
        // Both top and bottom should clamp to the image height 1080.
        assertEquals(1080f, r.top, eps)
        assertEquals(1080f, r.bottom, eps)
    }

    @Test fun viewToImageRect_tinyCropAtThreshold() {
        // 4x4 drag → maps to ≤4x4 region; CropOverlay's > 4 check should treat as no-crop.
        val r = CropGeometry.viewToImageRect(
            Rect(10f, 10f, 14f, 14f),
            IntSize(1000, 1000), IntSize(1000, 1000)
        )
        assertEquals(4f, r.width, eps)
        assertEquals(4f, r.height, eps)
    }

    @Test fun viewToImageRect_unNormalizedInputContract() {
        // Document contract: caller must normalize before calling. We test that
        // CropOverlay always does this. If you ever skip normalize, you get an
        // inverted rect — caught here so the contract stays explicit.
        val r = CropGeometry.viewToImageRect(
            Rect(750f, 750f, 250f, 250f),  // inverted
            IntSize(1000, 1000), IntSize(1000, 1000)
        )
        // right < left and bottom < top: that's the "wrong shape" signal.
        assertTrue("Caller must normalize first", r.right < r.left)
    }
}
