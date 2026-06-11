package com.jt.snipshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.LifecycleService

class SnipshotService : LifecycleService() {

    private lateinit var handlerThread: HandlerThread
    private lateinit var observer: ContentObserver
    private var fileObserver: android.os.FileObserver? = null
    private var screenReceiver: android.content.BroadcastReceiver? = null
    private var observerRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun setupFileObserver(handler: Handler): android.os.FileObserver? {
        val dir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES
            ),
            "Screenshots"
        )
        if (!dir.exists() && !runCatching { dir.mkdirs() }.getOrDefault(false)) {
            android.util.Log.w("Snipshot", "Screenshots dir not accessible: $dir")
            return null
        }
        val mask = android.os.FileObserver.CREATE or
            android.os.FileObserver.CLOSE_WRITE or
            android.os.FileObserver.MOVED_TO
        val obs = object : android.os.FileObserver(dir, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                // Skip in-progress system writes (".pending-*"); the MOVED_TO event
                // with the final name will follow once the screenshot is committed.
                if (path.startsWith(".")) return
                ServiceStatus.observerFires++
                ServiceStatus.lastUri = "file:$path"
                // We know the exact filename, so NEVER fall back to "latest screenshot"
                // here — that's how a previous screenshot could be shown for this event.
                // The MediaStore row can lag the file, so retry the filename lookup.
                resolveFilenameWithRetry(handler, path, RESOLVE_BACKOFFS_MS.size - 1)
            }
        }
        obs.startWatching()
        return obs
    }

    /**
     * The FileObserver sees the file before MediaStore has a row for it.
     * Retry the exact-filename lookup with backoff instead of guessing "latest".
     */
    private fun resolveFilenameWithRetry(handler: Handler, name: String, retriesLeft: Int) {
        handler.postDelayed({
            val uri = queryUriForFilename(name)
            when {
                uri != null -> handleNewMedia(uri)
                retriesLeft > 0 -> resolveFilenameWithRetry(handler, name, retriesLeft - 1)
                else -> ServiceStatus.lastDecision = "skip: no MediaStore row for $name after retries"
            }
        }, RESOLVE_BACKOFFS_MS[RESOLVE_BACKOFFS_MS.size - 1 - retriesLeft])
    }

    private fun queryUriForFilename(name: String): Uri? {
        return runCatching {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, arrayOf(name),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
                )
            }
        }.onFailure {
            android.util.Log.w("Snipshot", "queryUriForFilename failed: ${it.message}")
        }.getOrNull()
    }

    private fun registerObserver() {
        if (observerRegistered) return
        runCatching {
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            observerRegistered = true
        }.onFailure {
            android.util.Log.w("Snipshot", "Register observer failed: ${it.message}")
        }
    }

    private fun unregisterObserver() {
        if (!observerRegistered) return
        runCatching { contentResolver.unregisterContentObserver(observer) }
        observerRegistered = false
    }

    // Dedup recently-fired URIs (id) and recently-saved-by-us URIs (id).
    private val recentlySeen = linkedMapOf<Long, Long>()           // id -> timeSeenMs
    private val recentlySaved = linkedMapOf<Long, Long>()          // id -> timeSavedMs
    private val recentlyShown = linkedMapOf<Long, Long>()          // id -> overlayShownAtMs

    // _ID high-water mark at SERVICE START, seeded once and never bumped after.
    // The no-URI fallback only accepts rows above it, so nothing that existed
    // before the service started can ever be resurfaced. A dynamic watermark was
    // rejected: bumping it for row X can strand a still-pending screenshot with
    // a lower _ID (codex review, v0.1.2). Post-start dedup is handled instead by
    // recentlyShown (60s) + the 5s DATE_ADDED window + Snipshot_ exclusion.
    @Volatile private var startMaxId = -1L

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        try {
            startInForeground()
        } catch (e: Exception) {
            android.util.Log.e("Snipshot", "startInForeground failed", e)
            ServiceStatus.lastError = "startForeground: ${e.javaClass.simpleName}: ${e.message}"
            stopSelf()
            return
        }
        ServiceStatus.isRunning = true
        ServiceStatus.lastError = null

        SelfWriteTracker.attach { id ->
            synchronized(recentlySaved) {
                recentlySaved[id] = System.currentTimeMillis()
                pruneOldEntries(recentlySaved)
            }
        }

        handlerThread = HandlerThread("snipshot-observer").also { it.start() }
        val handler = Handler(handlerThread.looper)

        // Seed the _ID high-water mark BEFORE any observer is registered, so
        // every image that existed at startup is excluded from the fallback.
        // Both observers dispatch on this handler, so ordering is guaranteed.
        handler.post {
            queryMaxImageId()?.let { startMaxId = it }
        }

        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                ServiceStatus.observerFires++
                queryFreshScreenshots().forEach { handleNewMedia(it) }
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                ServiceStatus.observerFires++
                ServiceStatus.lastUri = uri?.toString() ?: "<null>"
                if (uri == null) queryFreshScreenshots().forEach { handleNewMedia(it) }
                else handleNewMedia(uri)
            }

            override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
                ServiceStatus.observerFires++
                ServiceStatus.lastUri = uris.joinToString(",").take(120)
                if (uris.isEmpty()) {
                    queryFreshScreenshots().forEach { handleNewMedia(it) }
                } else {
                    uris.forEach { handleNewMedia(it) }
                }
            }
        }

        // Prefer FileObserver when we have file-system access — it only fires for
        // changes in the Screenshots folder, vs ContentObserver which fires for
        // every image change device-wide. Defensive: if FileObserver setup throws
        // for any reason, fall back to ContentObserver so the app stays functional.
        if (PermissionHelpers.hasAllFilesAccess()) {
            fileObserver = runCatching { setupFileObserver(handler) }
                .onFailure { android.util.Log.e("Snipshot", "FileObserver setup failed", it) }
                .getOrNull()
        }
        if (fileObserver == null) {
            registerObserver()
        }

        // Pause observation while the screen is off — saves battery and ignores
        // screenshots that can only happen via app-driven capture anyway.
        screenReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        fileObserver?.stopWatching()
                        unregisterObserver()
                        mainHandler.post { OverlayManager.dismiss() }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        if (fileObserver != null) fileObserver?.startWatching()
                        else registerObserver()
                    }
                }
            }
        }
        registerReceiver(
            screenReceiver,
            android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
        )
    }

    override fun onDestroy() {
        ServiceStatus.isRunning = false
        fileObserver?.let { runCatching { it.stopWatching() } }
        fileObserver = null
        unregisterObserver()
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        runCatching { handlerThread.quitSafely() }
        SelfWriteTracker.detach()
        mainHandler.post { OverlayManager.dismiss() }
        super.onDestroy()
    }

    private fun handleNewMedia(uri: Uri) {
        try {
            handleNewMediaInner(uri)
        } catch (t: Throwable) {
            android.util.Log.e("Snipshot", "handleNewMedia threw: ${t.message}", t)
            ServiceStatus.lastDecision = "CRASH: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun handleNewMediaInner(uri: Uri) {
        val id = uri.lastPathSegment?.toLongOrNull()
        if (id == null) {
            ServiceStatus.lastDecision = "skip: no id in uri"
            return
        }
        val now = System.currentTimeMillis()

        synchronized(recentlySaved) {
            recentlySaved[id]?.let { savedAt ->
                if (now - savedAt < 10_000) {
                    ServiceStatus.lastDecision = "skip: own write"
                    return
                }
            }
        }
        // Never show the overlay twice for the same image, no matter which
        // observer path delivered it (FileObserver fires CREATE + CLOSE_WRITE +
        // MOVED_TO; ContentObserver fires on insert + IS_PENDING flip).
        synchronized(recentlyShown) {
            recentlyShown[id]?.let { shownAt ->
                if (now - shownAt < 60_000) {
                    ServiceStatus.lastDecision = "skip: already shown"
                    return
                }
            }
        }
        synchronized(recentlySeen) {
            recentlySeen[id]?.let { seenAt ->
                if (now - seenAt < 2_000) {
                    ServiceStatus.lastDecision = "skip: dedup burst"
                    return
                }
            }
            recentlySeen[id] = now
            pruneOldEntries(recentlySeen)
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.IS_PENDING
        )
        val cursor = runCatching {
            contentResolver.query(uri, projection, null, null, null)
        }.getOrNull()
        if (cursor == null) {
            ServiceStatus.lastDecision = "skip: query returned null"
            return
        }
        cursor.use { c ->
            if (!c.moveToFirst()) {
                ServiceStatus.lastDecision = "skip: cursor empty"
                return
            }
            val relPath = c.getStringOr(MediaStore.Images.Media.RELATIVE_PATH) ?: ""
            val name = c.getStringOr(MediaStore.Images.Media.DISPLAY_NAME) ?: ""
            val dateAdded = c.getLongOr(MediaStore.Images.Media.DATE_ADDED) ?: 0L
            val dateModified = c.getLongOr(MediaStore.Images.Media.DATE_MODIFIED) ?: 0L
            val isPending = (c.getLongOr(MediaStore.Images.Media.IS_PENDING) ?: 0L) != 0L

            if (isPending) {
                ServiceStatus.lastDecision = "skip: IS_PENDING=1 (will retry on next fire)"
                synchronized(recentlySeen) { recentlySeen.remove(id) }
                return
            }

            val isScreenshot = relPath.contains("Screenshots", ignoreCase = true)
            if (!isScreenshot) {
                ServiceStatus.lastDecision = "skip: path=$relPath name=$name"
                return
            }
            if (name.startsWith("Snipshot_")) {
                ServiceStatus.lastDecision = "skip: our own output ($name)"
                return
            }

            val nowSec = now / 1000
            val fresh = (nowSec - dateAdded) < 30 || (nowSec - dateModified) < 30
            if (!fresh) {
                ServiceStatus.lastDecision = "skip: stale (added=${nowSec - dateAdded}s ago, modified=${nowSec - dateModified}s ago)"
                return
            }

            synchronized(recentlyShown) {
                recentlyShown[id] = now
                pruneOldEntries(recentlyShown)
            }
            ServiceStatus.lastDecision = "SHOWING overlay for $name"
            ServiceStatus.lastAcceptedFire = "$name @ ${nowSec}"
            ServiceStatus.overlayAttempts++
            mainHandler.post { OverlayManager.show(applicationContext, uri) }
        }
    }

    /**
     * Only used by the ContentObserver no-URI path. Returns ALL fresh candidate
     * rows (capped), not just the newest — if the newest was already shown, a
     * just-committed older one must still be inspected. handleNewMedia dedups.
     * Anchored on _ID above the service-start high-water mark, plus a freshness
     * window and own-output exclusion. Pending rows are excluded by MediaStore.
     */
    private fun queryFreshScreenshots(): List<Uri> {
        return runCatching {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.Images.Media.DATE_ADDED} >= ? AND " +
                "${MediaStore.Images.Media.DISPLAY_NAME} NOT LIKE ? AND " +
                "${MediaStore.Images.Media._ID} > ?"
            val args = arrayOf(
                "%Screenshots%",
                (System.currentTimeMillis() / 1000 - 5).toString(),
                "Snipshot_%",
                startMaxId.toString()
            )
            val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, sort
            )?.use { c ->
                val uris = mutableListOf<Uri>()
                while (c.moveToNext() && uris.size < 5) {
                    uris.add(
                        android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)
                        )
                    )
                }
                uris
            } ?: emptyList()
        }.onFailure {
            android.util.Log.w("Snipshot", "queryFreshScreenshots failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun queryMaxImageId(): Long? {
        return runCatching {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                null, null,
                "${MediaStore.Images.Media._ID} DESC"
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.onFailure {
            android.util.Log.w("Snipshot", "queryMaxImageId failed: ${it.message}")
        }.getOrNull()
    }

    private fun pruneOldEntries(map: MutableMap<Long, Long>) {
        if (map.size <= 64) return
        val it = map.entries.iterator()
        while (it.hasNext() && map.size > 32) { it.next(); it.remove() }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        val channelId = "snipshot_service"
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val NOTIF_ID = 1001
        // First entry is the initial delay before the first lookup attempt.
        private val RESOLVE_BACKOFFS_MS = longArrayOf(0, 150, 350, 700, 1200)

        /** @return null on success, error message on failure. */
        fun start(context: Context): String? {
            val intent = Intent(context, SnipshotService::class.java)
            return try {
                context.startForegroundService(intent)
                null
            } catch (e: Exception) {
                android.util.Log.e("Snipshot", "startForegroundService failed", e)
                "${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SnipshotService::class.java))
        }
    }
}

/** Lightweight running-state flag visible to the UI. */
object ServiceStatus {
    @Volatile var isRunning: Boolean = false
    @Volatile var lastError: String? = null
    @Volatile var observerFires: Int = 0
    @Volatile var lastUri: String? = null
    @Volatile var lastDecision: String? = null
    @Volatile var lastAcceptedFire: String? = null   // most recent fire that PASSED filters
    @Volatile var overlayAttempts: Int = 0           // times we called OverlayManager.show
    @Volatile var overlayResult: String? = null      // result of last show attempt
    @Volatile var decodeResult: String? = null       // what bitmap decode did
}

/** Cropper notifies us about its own MediaStore writes so we can skip them. */
object SelfWriteTracker {
    @Volatile private var listener: ((Long) -> Unit)? = null
    fun attach(l: (Long) -> Unit) { listener = l }
    fun detach() { listener = null }
    fun notifySaved(id: Long) { listener?.invoke(id) }
}

private fun android.database.Cursor.getStringOr(column: String): String? {
    val idx = getColumnIndex(column)
    return if (idx >= 0 && !isNull(idx)) getString(idx) else null
}

private fun android.database.Cursor.getLongOr(column: String): Long? {
    val idx = getColumnIndex(column)
    return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
}
