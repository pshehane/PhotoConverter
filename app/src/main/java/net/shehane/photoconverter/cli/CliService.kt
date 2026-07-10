package net.shehane.photoconverter.cli

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import net.shehane.photoconverter.core.Pipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * adb-driven CLI:
 *   adb shell pm grant net.shehane.photoconverter android.permission.READ_MEDIA_IMAGES
 *   adb shell pm grant net.shehane.photoconverter android.permission.ACCESS_MEDIA_LOCATION
 *   adb shell am start-foreground-service -n net.shehane.photoconverter/.cli.CliService -e cmd run --ei count 12
 *   adb shell am start-foreground-service -n net.shehane.photoconverter/.cli.CliService -e cmd cleanup
 *   adb logcat -s HEIFConv
 */
class CliService : Service() {

    companion object {
        const val TAG = "HEIFConv"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithType()
        val cmd = intent?.getStringExtra("cmd") ?: "run"
        val count = intent?.getIntExtra("count", 12) ?: 12
        Log.i(TAG, "CLI command: $cmd count=$count")
        scope.launch {
            try {
                when (cmd) {
                    "run" -> run(count)
                    "cleanup" -> cleanup()
                    else -> Log.e(TAG, "unknown cmd: $cmd (expected run|cleanup)")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "FATAL: ${t.message}", t)
            } finally {
                Log.i(TAG, "CLI done: $cmd")
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun run(count: Int) {
        val pipeline = Pipeline(
            this,
            onLog = { Log.i(TAG, it) },
            onProgress = { _, _, _ -> },
        )
        val uris = pipeline.firstGalleryImages(count)
        Log.i(TAG, "found ${uris.size} gallery images (requested $count)")
        if (uris.isEmpty()) {
            Log.e(TAG, "no images found — is READ_MEDIA_IMAGES granted?")
            return
        }

        val analysis = pipeline.analyze(uris)
        Log.i(TAG, "ANALYSIS: ${analysis.stats.count} images in ${analysis.stats.totalMs}ms " +
            "(avg ${analysis.stats.avgMs}ms/image)")
        Log.i(TAG, "SORT: JPEG=${analysis.jpegs.size} HEIF=${analysis.heifs.size} other=${analysis.others.size}")

        val h2j = pipeline.convertHeifToJpeg(analysis.heifs)
        Log.i(TAG, "HEIF->JPEG: ${h2j.succeeded}/${h2j.stats.count} ok, ${h2j.errors.size} errors, " +
            "${h2j.warnings.size} warnings, ${h2j.stats.totalMs}ms total, avg ${h2j.stats.avgMs}ms")
        h2j.errors.forEach { Log.w(TAG, "  H2J error: ${it.name}: ${it.message}") }
        h2j.warnings.forEach { Log.w(TAG, "  H2J warning: ${it.name}: ${it.message}") }

        val j2h = pipeline.convertJpegToHeif(analysis.jpegs)
        Log.i(TAG, "JPEG->HEIF: ${j2h.succeeded}/${j2h.stats.count} ok, ${j2h.errors.size} errors, " +
            "${j2h.warnings.size} warnings, ${j2h.stats.totalMs}ms total, avg ${j2h.stats.avgMs}ms")
        j2h.errors.forEach { Log.w(TAG, "  J2H error: ${it.name}: ${it.message}") }
        j2h.warnings.forEach { Log.w(TAG, "  J2H warning: ${it.name}: ${it.message}") }

        val h2a = pipeline.convertHeifToAvif(analysis.heifs)
        Log.i(TAG, "HEIF->AVIF: ${h2a.succeeded}/${h2a.stats.count} ok, ${h2a.errors.size} errors, " +
            "${h2a.warnings.size} warnings, ${h2a.stats.totalMs}ms total, avg ${h2a.stats.avgMs}ms")
        h2a.errors.forEach { Log.w(TAG, "  H2A error: ${it.name}: ${it.message}") }
        h2a.warnings.forEach { Log.w(TAG, "  H2A warning: ${it.name}: ${it.message}") }

        val j2a = pipeline.convertJpegToAvif(analysis.jpegs)
        Log.i(TAG, "JPEG->AVIF: ${j2a.succeeded}/${j2a.stats.count} ok, ${j2a.errors.size} errors, " +
            "${j2a.warnings.size} warnings, ${j2a.stats.totalMs}ms total, avg ${j2a.stats.avgMs}ms")
        j2a.errors.forEach { Log.w(TAG, "  J2A error: ${it.name}: ${it.message}") }
        j2a.warnings.forEach { Log.w(TAG, "  J2A warning: ${it.name}: ${it.message}") }

        pipeline.writeReport(analysis, h2j, j2h, h2a, j2a)
        Log.i(TAG, "outputs in: ${pipeline.store.root.absolutePath}")
    }

    private fun cleanup() {
        val (files, bytes) = Pipeline(this, onLog = { Log.i(TAG, it) }).store.cleanup()
        Log.i(TAG, "CLEANUP: removed $files files (${bytes / 1024} KiB)")
    }

    private fun startForegroundWithType() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("cli", "CLI runs", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, "cli")
            .setContentTitle("HEIF Converter CLI")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(1, notification, type)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
