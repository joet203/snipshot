package com.jt.snipshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val DRAG_SLOP_DP = 40f
private const val AUTO_DISMISS_MS = 3000
private const val MIN_CROP_PX = 80

@Composable
fun CropOverlay(
    imageUri: Uri,
    onDone: (cropRectInImagePx: Rect?) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var imgSize by remember { mutableStateOf(IntSize.Zero) }

    var decodeError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(imageUri) {
        val (decoded, err) = withContext(Dispatchers.IO) {
            decodeScreenshotWithDiagnostic(context, imageUri)
        }
        ServiceStatus.decodeResult = err ?: "OK ${decoded?.originalWidth}x${decoded?.originalHeight}"
        if (decoded == null) {
            decodeError = err ?: "unknown"
            // Do NOT auto-cancel — let the user see what failed.
        } else {
            imgSize = IntSize(decoded.originalWidth, decoded.originalHeight)
            image = decoded.bitmap.asImageBitmap()
        }
    }

    if (image == null) {
        val errMsg = decodeError
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (errMsg == null) Color.Black.copy(alpha = 0.45f)
                    else Color(0xCC8B0000)
                )
                .pointerInput(Unit) { detectTapGestures { onCancel() } }
        ) {
            if (errMsg != null) {
                androidx.compose.material3.Text(
                    text = "Snipshot decode FAIL — tap to dismiss\n\n$errMsg\n\nuri=$imageUri",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            }
        }
        return
    }
    val img = image!!

    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val slopPx = with(density) { DRAG_SLOP_DP.dp.toPx() }

    // Auto-dismiss countdown: save full screen after 3s of no interaction.
    var userInteracted by remember { mutableStateOf(false) }
    var remainingMs by remember { mutableIntStateOf(AUTO_DISMISS_MS) }
    LaunchedEffect(image, userInteracted) {
        if (image == null || userInteracted) return@LaunchedEffect
        remainingMs = AUTO_DISMISS_MS
        while (remainingMs > 0) {
            kotlinx.coroutines.delay(250)
            remainingMs -= 250
        }
        if (!userInteracted) onDone(null)
    }

    var antsPhase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(dragStart, dragCurrent) {
        while (dragStart != null && dragCurrent != null) {
            antsPhase = (antsPhase + 2f) % 20f
            delay(60)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .onSizeChanged {
                canvasSize = it
                if (dragStart != null) {
                    dragStart = null
                    dragCurrent = null
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    // Tap with no drag → save full screen (no crop).
                    onDone(null)
                })
            }
            .pointerInput(canvasSize, imgSize) {
                detectDragGestures(
                    onDragStart = { offset ->
                        userInteracted = true
                        dragStart = offset
                        dragCurrent = offset
                    },
                    onDrag = { change, _ ->
                        dragCurrent = change.position
                    },
                    onDragEnd = {
                        val s = dragStart; val c = dragCurrent
                        val canvas = canvasSize; val src = imgSize
                        dragStart = null; dragCurrent = null

                        if (s == null || c == null || canvas.width <= 0 || src.width <= 0) {
                            onDone(null); return@detectDragGestures
                        }
                        // Tiny movement → treat as tap.
                        if (abs(c.x - s.x) < slopPx && abs(c.y - s.y) < slopPx) {
                            onDone(null); return@detectDragGestures
                        }
                        val rectView = CropGeometry.normalize(s, c)
                        val rectImg = CropGeometry.viewToImageRect(rectView, canvas, src)
                        if (rectImg.width >= MIN_CROP_PX && rectImg.height >= MIN_CROP_PX)
                            onDone(rectImg)
                        else onDone(null)
                    },
                    onDragCancel = {
                        dragStart = null
                        dragCurrent = null
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Fit using ORIGINAL dims so display layout matches gesture mapping exactly.
            val fit = CropGeometry.fitRect(imgSize.width, imgSize.height, size)
            drawImage(
                image = img,
                dstOffset = IntOffset(fit.left.toInt(), fit.top.toInt()),
                dstSize = IntSize(fit.width.toInt(), fit.height.toInt())
            )

            val s = dragStart
            val c = dragCurrent
            if (s != null && c != null) {
                val r = CropGeometry.normalize(s, c)
                drawRect(Color.Black.copy(alpha = 0.55f), size = size)
                clipRect(r.left, r.top, r.right, r.bottom) {
                    drawImage(
                        image = img,
                        dstOffset = IntOffset(fit.left.toInt(), fit.top.toInt()),
                        dstSize = IntSize(fit.width.toInt(), fit.height.toInt())
                    )
                }
                val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 8f), antsPhase)
                drawRect(
                    color = Color.White,
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width, r.height),
                    style = Stroke(width = 4f, pathEffect = dash)
                )
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width, r.height),
                    style = Stroke(width = 1.5f)
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!userInteracted && remainingMs > 0) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Text(
                        text = ((remainingMs + 999) / 1000).toString(),
                        color = Color.White,
                        style = androidx.compose.material3.MaterialTheme.typography.displaySmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                }
            }

            IconButton(
                onClick = {
                    userInteracted = true
                    onCancel()
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel")
            }
        }
    }
}

internal data class DecodedScreenshot(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int
)

/**
 * Decode + diagnostic with retry. The MediaStore observer can fire before the
 * screenshot bytes are fully written; retry with backoff so we catch the file
 * as soon as it's readable.
 */
private suspend fun decodeScreenshotWithDiagnostic(
    context: Context,
    uri: Uri,
    maxPixels: Long = 12_000_000L
): Pair<DecodedScreenshot?, String?> {
    val backoffs = listOf(0L, 100L, 250L, 500L, 1000L, 1500L)
    var lastError: String? = null
    for ((i, wait) in backoffs.withIndex()) {
        if (wait > 0) kotlinx.coroutines.delay(wait)
        val (result, err) = tryDecode(context, uri, maxPixels)
        if (result != null) return result to null
        lastError = "${err ?: "unknown"} (retry ${i + 1}/${backoffs.size})"
    }
    return null to lastError
}

private fun tryDecode(
    context: Context,
    uri: Uri,
    maxPixels: Long
): Pair<DecodedScreenshot?, String?> {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val openOk1 = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds); true
        } ?: false
        if (!openOk1) return null to "openInputStream returned null (bounds pass)"
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null to "bounds invalid: ${bounds.outWidth}x${bounds.outHeight}"
        }

        val total = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        var sample = 1
        while (total / (sample.toLong() * sample) > maxPixels) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null to "openInputStream returned null (decode pass)"

        DecodedScreenshot(
            bitmap = bmp,
            originalWidth = bounds.outWidth,
            originalHeight = bounds.outHeight
        ) to null
    } catch (e: OutOfMemoryError) {
        Log.e("Snipshot/Crop", "OOM decoding screenshot", e)
        null to "OOM: ${e.message}"
    } catch (e: SecurityException) {
        Log.e("Snipshot/Crop", "SecurityException on decode", e)
        null to "SecurityException: ${e.message} — uri may not be readable with current permissions"
    } catch (e: Exception) {
        Log.e("Snipshot/Crop", "Decode failed", e)
        null to "${e.javaClass.simpleName}: ${e.message}"
    }
}

