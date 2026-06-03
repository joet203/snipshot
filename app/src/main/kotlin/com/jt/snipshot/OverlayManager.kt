package com.jt.snipshot

import android.content.Context
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Hosts a Compose UI inside a SYSTEM_ALERT_WINDOW overlay.
 * Must be invoked from the main thread (WindowManager + ComposeView require it).
 */
object OverlayManager {

    private const val TAG = "Snipshot/Overlay"
    private const val STALE_AFTER_MS = 15_000L

    private var current: OverlayHost? = null
    private var currentShownAt: Long = 0L

    fun show(context: Context, uri: Uri) {
        // Force-clear stale overlays so a stuck one can't permanently block new screenshots.
        if (current != null && System.currentTimeMillis() - currentShownAt > STALE_AFTER_MS) {
            Log.w(TAG, "Force-dismissing stale overlay (>${STALE_AFTER_MS}ms)")
            dismiss()
        }
        if (current != null) {
            ServiceStatus.overlayResult = "SKIP: already showing"
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission revoked — skipping show")
            ServiceStatus.overlayResult = "FAIL: canDrawOverlays=false"
            return
        }
        val host = OverlayHost(context.applicationContext)
        val attached = host.attach(uri) { dismiss() }
        if (attached) {
            current = host
            currentShownAt = System.currentTimeMillis()
            ServiceStatus.overlayResult = "OK: attached"
        }
    }

    fun dismiss() {
        current?.detach()
        current = null
        currentShownAt = 0L
    }
}

private class OverlayHost(private val context: Context) :
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val vmStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore = vmStore
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private var composeView: ComposeView? = null

    /** @return true if the overlay was successfully attached. */
    fun attach(uri: Uri, onDismiss: () -> Unit): Boolean {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayHost)
            setViewTreeViewModelStoreOwner(this@OverlayHost)
            setViewTreeSavedStateRegistryOwner(this@OverlayHost)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                CropOverlay(
                    imageUri = uri,
                    onDone = { rect ->
                        Cropper.saveCrop(context, uri, rect)
                        onDismiss()
                    },
                    onCancel = onDismiss
                )
            }
        }

        val type =
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val wm = context.getSystemService(WindowManager::class.java) ?: return false

        return try {
            wm.addView(view, params)
            composeView = view
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            true
        } catch (e: Exception) {
            Log.e("Snipshot/Overlay", "addView failed: ${e.message}", e)
            ServiceStatus.overlayResult = "FAIL addView: ${e.javaClass.simpleName}: ${e.message}"
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            vmStore.clear()
            false
        }
    }

    fun detach() {
        val view = composeView
        composeView = null
        if (view != null) {
            val wm = context.getSystemService(WindowManager::class.java)
            runCatching { wm?.removeView(view) }
            runCatching { view.disposeComposition() }
        }
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        vmStore.clear()
    }
}
