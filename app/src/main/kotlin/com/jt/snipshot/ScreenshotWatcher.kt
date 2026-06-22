package com.jt.snipshot

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log

/**
 * Screenshot detection without a long-running foreground service.
 *
 * The previous design ran a 24/7 `dataSync` foreground service. Since Android 14
 * that FGS type has a ~6h-per-24h cap; on target SDK 35+ the OS throws
 * `ForegroundServiceDidNotStopInTimeException` once the cap is hit — which is the
 * crash that motivated this rewrite. There is no "run forever" FGS type that
 * fits a passive watcher.
 *
 * Instead we register a JobScheduler **content-URI trigger** on the MediaStore
 * images collection. The OS wakes [ScreenshotJobService] only when images change,
 * with no process alive in between. The overlay is a SYSTEM_ALERT_WINDOW view, so
 * it needs no foreground service to show, and a visible overlay keeps the process
 * at perceptible priority through the crop/save.
 *
 * All detection state (dedup maps, the `_ID` high-water mark) lives here as a
 * process-level singleton so it survives across the short-lived job executions
 * that share one process. The high-water mark is also persisted so it survives
 * process death and reboot.
 */
object ScreenshotWatcher {

    private const val TAG = "Snipshot/Watcher"
    private const val JOB_ID = 7001

    private const val PREFS = "snipshot_watcher"
    private const val KEY_ENABLED = "watching_enabled"
    private const val KEY_MAX_ID = "start_max_id"

    // Batch the burst of MediaStore changes a single screenshot produces (insert +
    // IS_PENDING flip) into one job execution; the per-id dedup chain handles the rest.
    private const val TRIGGER_UPDATE_DELAY_MS = 500L
    private const val TRIGGER_MAX_DELAY_MS = 1500L

    private val workThread = HandlerThread("snipshot-job").apply { start() }
    private val workHandler = Handler(workThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    // _ID high-water mark captured when watching is (re)started. The no-URI fallback
    // only accepts rows above it, so nothing that existed at start can be resurfaced.
    // Seeded once, never bumped after — see CLAUDE.md gotcha #12.
    @Volatile private var startMaxId = -1L
    @Volatile private var selfWriteAttached = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** @return null on success, error message on failure. */
    fun start(context: Context): String? {
        val app = context.applicationContext
        return try {
            val maxId = queryMaxImageId(app) ?: -1L
            prefs(app).edit().putBoolean(KEY_ENABLED, true).putLong(KEY_MAX_ID, maxId).apply()
            startMaxId = maxId
            ensureSelfWriteAttached()
            scheduleJob(app)
            ServiceStatus.isRunning = true
            ServiceStatus.lastError = null
            null
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            ServiceStatus.lastError = "start: ${e.javaClass.simpleName}: ${e.message}"
            "${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
        }
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_ENABLED, false).apply()
        app.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        ServiceStatus.isRunning = false
        mainHandler.post { OverlayManager.dismiss() }
    }

    /** Re-arm the trigger after a reboot, but only if the user had watching enabled. */
    fun resumeIfEnabled(context: Context) {
        val app = context.applicationContext
        if (!prefs(app).getBoolean(KEY_ENABLED, false)) return
        startMaxId = prefs(app).getLong(KEY_MAX_ID, -1L)
        ensureSelfWriteAttached()
        scheduleJob(app)
        ServiceStatus.isRunning = true
    }

    fun isActive(context: Context): Boolean =
        context.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID) != null

    /** Re-schedule the one-shot content trigger. Called by the job itself on each fire. */
    fun rearm(context: Context) = scheduleJob(context.applicationContext)

    private fun scheduleJob(context: Context) {
        val js = context.getSystemService(JobScheduler::class.java) ?: return
        val info = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, ScreenshotJobService::class.java)
        )
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                )
            )
            .setTriggerContentUpdateDelay(TRIGGER_UPDATE_DELAY_MS)
            .setTriggerContentMaxDelay(TRIGGER_MAX_DELAY_MS)
            .build()
        runCatching { js.schedule(info) }
            .onFailure { Log.e(TAG, "schedule failed: ${it.message}", it) }
    }

    /**
     * Invoked by [ScreenshotJobService.onStartJob] on the main thread. Hands the work
     * to a background thread (MediaStore queries off the main looper) and invokes [done]
     * — which must call jobFinished — when the batch is processed.
     */
    fun onJobFired(context: Context, params: JobParameters, done: () -> Unit) {
        workHandler.post {
            try {
                if (startMaxId < 0) startMaxId = prefs(context).getLong(KEY_MAX_ID, -1L)
                ensureSelfWriteAttached()
                ServiceStatus.isRunning = true
                ServiceStatus.observerFires++

                val uris = params.triggeredContentUris
                val itemUris = uris?.filter { it.lastPathSegment?.toLongOrNull() != null }
                if (!itemUris.isNullOrEmpty()) {
                    ServiceStatus.lastUri = itemUris.joinToString(",").take(120)
                    itemUris.forEach { handleNewMedia(context, it) }
                } else {
                    // Authorities-only (>50 changes) or a bare directory URI — fall back
                    // to a fresh-screenshot scan anchored on the start high-water mark.
                    val auth = params.triggeredContentAuthorities?.joinToString() ?: "<none>"
                    ServiceStatus.lastUri = "<authorities: $auth>"
                    queryFreshScreenshots(context).forEach { handleNewMedia(context, it) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "onJobFired threw: ${t.message}", t)
                ServiceStatus.lastDecision = "CRASH: ${t.javaClass.simpleName}: ${t.message}"
            } finally {
                done()
            }
        }
    }

    private fun ensureSelfWriteAttached() {
        if (selfWriteAttached) return
        selfWriteAttached = true
        SelfWriteTracker.attach { id ->
            synchronized(recentlySaved) {
                recentlySaved[id] = System.currentTimeMillis()
                pruneOldEntries(recentlySaved)
            }
        }
    }

    // ---- Detection / dedup (ported verbatim from the old SnipshotService) ----

    // Dedup recently-fired URIs (id) and recently-saved-by-us URIs (id).
    private val recentlySeen = linkedMapOf<Long, Long>()           // id -> timeSeenMs
    private val recentlySaved = linkedMapOf<Long, Long>()          // id -> timeSavedMs
    private val recentlyShown = linkedMapOf<Long, Long>()          // id -> overlayShownAtMs

    private fun handleNewMedia(context: Context, uri: Uri) {
        try {
            handleNewMediaInner(context, uri)
        } catch (t: Throwable) {
            Log.e(TAG, "handleNewMedia threw: ${t.message}", t)
            ServiceStatus.lastDecision = "CRASH: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun handleNewMediaInner(context: Context, uri: Uri) {
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
        // Never show the overlay twice for the same image, no matter which fire delivered it.
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
            context.contentResolver.query(uri, projection, null, null, null)
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
            val appCtx = context.applicationContext
            mainHandler.post { OverlayManager.show(appCtx, uri) }
        }
    }

    /**
     * No-URI fallback. Returns ALL fresh candidate rows (capped), not just the newest —
     * if the newest was already shown, a just-committed older one must still be inspected.
     * handleNewMedia dedups. Anchored on the service-start `_ID` high-water mark plus a
     * freshness window and own-output exclusion. Pending rows are excluded by MediaStore.
     */
    private fun queryFreshScreenshots(context: Context): List<Uri> {
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
            context.contentResolver.query(
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
            Log.w(TAG, "queryFreshScreenshots failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun queryMaxImageId(context: Context): Long? {
        return runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                null, null,
                "${MediaStore.Images.Media._ID} DESC"
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.onFailure {
            Log.w(TAG, "queryMaxImageId failed: ${it.message}")
        }.getOrNull()
    }

    private fun pruneOldEntries(map: MutableMap<Long, Long>) {
        if (map.size <= 64) return
        val it = map.entries.iterator()
        while (it.hasNext() && map.size > 32) { it.next(); it.remove() }
    }
}

/** JobScheduler entry point: woken by the MediaStore content trigger. */
class ScreenshotJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        // Content-trigger jobs are one-shot. Re-arm immediately so changes that land
        // while we process this batch aren't missed. The re-armed trigger only fires
        // on NEW changes, so this does not loop on the change that woke us.
        ScreenshotWatcher.rearm(applicationContext)
        ScreenshotWatcher.onJobFired(applicationContext, params) {
            jobFinished(params, false)
        }
        return true // work continues on a background thread
    }

    // We already re-armed in onStartJob; nothing to reschedule here.
    override fun onStopJob(params: JobParameters): Boolean = false
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
