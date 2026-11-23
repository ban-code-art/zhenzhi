package com.example.myapplication.ui

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONObject
import org.mozilla.geckoview.GeckoRuntime

/** Private, offline, opt-in debug collector. Originals remain owned by Gecko. */
class GeckoPocCrashService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    override fun onCreate() {
        super.onCreate()
        val channel = "m03-crash-capture"
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channel, "Debug crash capture", NotificationManager.IMPORTANCE_LOW))
        }
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else Notification.Builder(this)
        startForeground(2403, builder.setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("M03 debug crash capture").setContentText("Saving local crash evidence").build())
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != GeckoRuntime.ACTION_CRASHED) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        worker.execute {
            val dir = File(filesDir, "m03-gecko-crashes/${System.currentTimeMillis()}-${UUID.randomUUID()}")
            val report = JSONObject().put("pid", Process.myPid()).put("timeMs", System.currentTimeMillis())
                .put("process", File("/proc/self/cmdline").readText().trimEnd('\u0000'))
            try {
                check(dir.mkdirs())
                for ((key, name) in listOf(GeckoRuntime.EXTRA_MINIDUMP_PATH to "minidump.dmp",
                    GeckoRuntime.EXTRA_EXTRAS_PATH to "annotations.extra")) {
                    val path = intent.getStringExtra(key)
                    report.put(key, path ?: JSONObject.NULL)
                    if (path == null) continue
                    val source = File(path).canonicalFile
                    require(source.path.startsWith(applicationInfo.dataDir + File.separator)) { "Non-private crash path" }
                    source.copyTo(dir.resolve(name))
                    report.put(name + "Bytes", source.length())
                }
                val extra = dir.resolve("annotations.extra")
                if (extra.exists()) {
                    val annotations = JSONObject(extra.readText())
                    report.put("hasJavaStackTrace", annotations.has("JavaStackTrace"))
                    if (annotations.has("JavaStackTrace"))
                        dir.resolve("JavaStackTrace.txt").writeText(annotations.getString("JavaStackTrace"))
                }
            } catch (e: Exception) {
                report.put("error", e.toString())
                Log.e("M03GeckoCrash", "Capture failed", e)
            } finally {
                runCatching { dir.resolve("capture.json.tmp").writeText(report.toString(2))
                    check(dir.resolve("capture.json.tmp").renameTo(dir.resolve("capture.json"))) }
                    .onFailure { Log.e("M03GeckoCrash", "Could not persist capture metadata", it) }
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
