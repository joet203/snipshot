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

    // TWO job ids, used alternately. JobScheduler.schedule() with the id of a
    // CURRENTLY RUNNING job stops that job (documented in the SDK javadoc), so a
    // fired job must arm the OTHER id — that keeps a trigger registered for the
    // whole time the current batch is being processed, with no self-stop.
    private const val JOB_ID_A = 7001
    private const val JOB_ID_B = 7002

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
    // Seeded once, never bumped after — see ENGINEERING_NOTES.md gotcha #12.
    @Volatile private var startMaxId = -1L
    @Volatile private var selfWriteAttached = false

    // In-memory mirror of KEY_ENABLED, so in-flight work and overlay posts can bail
    // the instant the user hits Stop (canceling the jobs doesn't stop posted work).
    @Volatile private var enabled: Boolean? = null

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isEnabled(context: Context): Boolean =
        enabled ?: prefs(context).getBoolean(KEY_ENABLED, false).also { enabled = it }

    /** @return null on success, error message on failure. */
    fun start(context: Context): String? {
        val app = context.applicationContext
        return try {
            val maxId = queryMaxImageId(app) ?: -1L
            // Reset to a known single-trigger state before arming.
            app.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID_B)
            if (!scheduleJob(app, JOB_ID_A)) {
                ServiceStatus.lastError = "JobScheduler rejected the watch job"
                return "JobScheduler rejected the watch job"
            }
            prefs(app).edit().putBoolean(KEY_ENABLED, true).putLong(KEY_MAX_ID, maxId).apply()
            enabled = true
            startMaxId = maxId
            ensureSelfWriteAttached()
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
        enabled = false
        app.getSystemService(JobScheduler::class.java)?.let {
            it.cancel(JOB_ID_A)
            it.cancel(JOB_ID_B)
        }
        ServiceStatus.isRunning = false
        mainHandler.post { OverlayManager.dismiss() }
    }

    /** Re-arm the trigger after a reboot, but only if the user had watching enabled. */
    fun resumeIfEnabled(context: Context) {
        val app = context.applicationContext
        if (!prefs(app).getBoolean(KEY_ENABLED, false)) return
        enabled = true
        startMaxId = prefs(app).getLong(KEY_MAX_ID, -1L)
        ensureSelfWriteAttached()
        if (!scheduleJob(app, JOB_ID_A)) {
            ServiceStatus.lastError = "boot re-arm: JobScheduler rejected the watch job"
            return
        }
        ServiceStatus.isRunning = true
    }

    fun isActive(context: Context): Boolean =
        context.getSystemService(JobScheduler::class.java)?.let {
            it.getPendingJob(JOB_ID_A) != null || it.getPendingJob(JOB_ID_B) != null
        } ?: false

    /**
     * Arm the trigger under the OTHER job id than the one that just fired. Called
     * from onStartJob BEFORE processing, so a trigger is registered for changes that
     * land while this batch is handled. Never call with the running job's own id —
     * schedule() on a running id stops that job.
     */
    fun armNext(context: Context, firedJobId: Int) {
        val next = if (firedJobId == JOB_ID_A) JOB_ID_B else JOB_ID_A
        if (!scheduleJob(context.applicationContext, next)) {
            ServiceStatus.lastError = "armNext: JobScheduler rejected job $next"
        }
    }

    /** @return true when the job was accepted (RESULT_SUCCESS). */
    private fun scheduleJob(context: Context, jobId: Int): Boolean {
        val js = context.getSystemService(JobScheduler::class.java) ?: return false
        val info = JobInfo.Builder(
            jobId,
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
        return runCatching { js.schedule(info) == JobScheduler.RESULT_SUCCESS }
            .onFailure { Log.e(TAG, "schedule($jobId) failed: ${it.message}", it) }
            .getOrDefault(false)
    }

    /**
     * Invoked by [ScreenshotJobService.onStartJob] on the main thread. Hands the work
     * to a background thread (MediaStore queries off the main looper) and invokes [done]
     * — which must call jobFinished — when the batch is processed.
     */
    fun onJobFired(context: Context, params: JobParameters, done: () -> Unit) {
        workHandler.post {
            try {
                if (!isEnabled(context)) {
                    // stop() raced with an in-flight fire; onStartJob already armed the
                    // next trigger, so cancel BOTH ids again to leave nothing behind.
                    context.getSystemService(JobScheduler::class.java)?.let {
                        it.cancel(JOB_ID_A)
                        it.cancel(JOB_ID_B)
                    }
                    return@post
                }
                if (startMaxId < 0) startMaxId = prefs(context).getLong(KEY_MAX_ID, -1L)
                ensureSelfWriteAttached()
                ServiceStatus.isRunning = true
                ServiceStatus.observerFires++

                val itemUris = params.triggeredContentUris
                    ?.filter { it.lastPathSegment?.toLongOrNull() != null }
                    .orEmpty()
                ServiceStatus.lastUri = if (itemUris.isNotEmpty()) {
                    itemUris.joinToString(",").take(120)
                } else {
                    // Authorities-only (>50 changes collapsed) or a bare directory URI.
                    "<authorities: ${params.triggeredContentAuthorities?.joinToString() ?: "<none>"}>"
                }
                itemUris.forEach { handleNewMedia(context, it) }

                // Always sweep after the batch: covers the >50-change collapse, the ms
                // gap before armNext registered, and anything (e.g. an IS_PENDING flip)
                // that landed while this batch was processing. handleNewMedia dedups.
                queryFreshScreenshots(context).forEach { handleNewMedia(context, it) }
            } catch (t: Throwable) {
                Log.e(TAG, "onJobFired threw: ${t.message}", t)
                ServiceStatus.lastDecision = "CRASH: ${t.javaClass.simpleName}: ${t.message}"
            } finally {
                // Self-heal: if armNext was rejected (job quota, throttling), no trigger
                // is pending now — retry the OTHER id (never the running job's own id).
                if (isEnabled(context) && !isActive(context)) {
                    val other = if (params.jobId == JOB_ID_A) JOB_ID_B else JOB_ID_A
                    scheduleJob(context, other)
                }
                // Overlay show()s were posted to mainHandler above; routing done() through
                // the same handler guarantees the overlay window is attached before
                // jobFinished() releases the job's process-priority guarantee.
                mainHandler.post { done() }
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
            // Re-check enabled on the main thread: the user may hit Stop between this
            // post and its execution, and a Stop must never be followed by an overlay.
            mainHandler.post { if (isEnabled(appCtx)) OverlayManager.show(appCtx, uri) }
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
        // Content-trigger jobs are one-shot. Arm the OTHER job id before processing so
        // a trigger stays registered for changes that land during this batch. Never the
        // same id: schedule() on a currently-running job's id STOPS that job (SDK doc).
        ScreenshotWatcher.armNext(applicationContext, params.jobId)
        ScreenshotWatcher.onJobFired(applicationContext, params) {
            jobFinished(params, false)
        }
        return true // work continues on a background thread
    }

    // A system-initiated stop (timeout etc.) can't kill the watcher: the next trigger
    // was already armed in onStartJob, and the in-flight batch finishes on our own
    // HandlerThread regardless. Nothing to reschedule.
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
