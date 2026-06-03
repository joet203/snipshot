package com.jt.snipshot

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect as AndroidRect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.OutputStream

object Cropper {

    private const val TAG = "Snipshot/Cropper"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * @param sourceUri MediaStore Uri of the original screenshot.
     * @param cropRectImagePx Null → tap, no crop, leave original.
     */
    fun saveCrop(context: Context, sourceUri: Uri, cropRectImagePx: Rect?) {
        scope.launch {
            if (cropRectImagePx == null) return@launch

            // Query image dimensions without decoding pixels.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                context.contentResolver.openInputStream(sourceUri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read image bounds", e)
                return@launch
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.e(TAG, "Could not read image dimensions: $sourceUri")
                return@launch
            }

            val rect = AndroidRect(
                cropRectImagePx.left.toInt().coerceAtLeast(0),
                cropRectImagePx.top.toInt().coerceAtLeast(0),
                cropRectImagePx.right.toInt().coerceAtMost(bounds.outWidth),
                cropRectImagePx.bottom.toInt().coerceAtMost(bounds.outHeight)
            )
            if (rect.width() <= 0 || rect.height() <= 0) {
                Log.w(TAG, "Empty crop rect, skipping")
                return@launch
            }

            // Try region-decode first; fall back to full-bitmap decode on failure.
            var cropped: Bitmap? = try {
                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    val decoder = BitmapRegionDecoder.newInstance(input) ?: return@use null
                    try {
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        decoder.decodeRegion(rect, opts)?.also { it.setHasAlpha(false) }
                    } finally {
                        decoder.recycle()
                    }
                }
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "OOM region-decoding; will fall back", oom)
                null
            } catch (e: Exception) {
                Log.e(TAG, "Region decode failed; falling back to full decode", e)
                null
            }

            var fallbackOriginal: Bitmap? = null
            if (cropped == null) {
                fallbackOriginal = try {
                    context.contentResolver.openInputStream(sourceUri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } catch (oom: OutOfMemoryError) {
                    Log.e(TAG, "OOM decoding original; skipping crop", oom)
                    null
                } catch (e: Exception) {
                    Log.e(TAG, "Decode original failed", e)
                    null
                }
                if (fallbackOriginal == null) {
                    Log.e(TAG, "Could not decode original: $sourceUri")
                    return@launch
                }
                cropped = runCatching {
                    Bitmap.createBitmap(
                        fallbackOriginal,
                        rect.left,
                        rect.top,
                        rect.width(),
                        rect.height()
                    )
                }.getOrNull()
                if (cropped == null) {
                    fallbackOriginal.recycle()
                    return@launch
                }
            }

            try {
                val name = "Snipshot_${System.currentTimeMillis()}.png"
                val savedUri = saveToScreenshots(context, cropped, name)
                if (savedUri == null) {
                    Log.e(TAG, "Failed to save crop")
                    return@launch
                }
                // Tell the observer to ignore this URI when it fires.
                savedUri.lastPathSegment?.toLongOrNull()?.let { SelfWriteTracker.notifySaved(it) }

                val keepOriginal = Prefs.keepOriginal(context).first()
                if (!keepOriginal) {
                    val deleted = runCatching {
                        context.contentResolver.delete(sourceUri, null, null)
                    }.getOrElse { 0 }
                    if (deleted == 0) {
                        Log.w(TAG, "Could not delete original (need MANAGE_EXTERNAL_STORAGE on Android 11+)")
                    }
                }
            } finally {
                if (fallbackOriginal != null && !fallbackOriginal.isRecycled) {
                    fallbackOriginal.recycle()
                }
                if (!cropped.isRecycled) {
                    cropped.recycle()
                }
            }
        }
    }

    private fun saveToScreenshots(context: Context, bitmap: Bitmap, fileName: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { out: OutputStream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("Bitmap.compress returned false")
                }
                out.flush()
            } ?: throw IllegalStateException("openOutputStream returned null")
            true
        }.getOrElse { e ->
            Log.e(TAG, "Save failed, rolling back", e)
            runCatching { resolver.delete(uri, null, null) }
            false
        }

        if (!ok) return null

        if (Build.VERSION.SDK_INT >= 29) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            runCatching { resolver.update(uri, values, null, null) }
        }
        return uri
    }
}
