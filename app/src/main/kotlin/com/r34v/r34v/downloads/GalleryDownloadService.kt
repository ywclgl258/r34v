package com.r34v.r34v.downloads

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.r34v.r34v.MainActivity
import com.r34v.r34v.R34Application
import com.r34v.r34v.data.GalleryDownloadState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class GalleryDownloadService : Service() {
    private val app get() = application as R34Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var lastStart = 0
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "图集下载", NotificationManager.IMPORTANCE_LOW))
        showProgress()
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "R34V:GalleryDownload").apply {
            setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
        }
        scope.launch { app.galleryDownloads.changes.collectLatest { delay(200); showProgress() } }
    }
    private fun showProgress() {
        val task = app.galleryDownloads.tasks().firstOrNull { it.state == GalleryDownloadState.DOWNLOADING }
        val open = PendingIntent.getActivity(this, 42, Intent(this, MainActivity::class.java).putExtra("downloads", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("图集下载").setContentText(task?.let { "${it.entry.title} · ${it.pages} / ${it.total} 页" } ?: "正在准备下载任务")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(task?.total ?: 0, task?.pages ?: 0, task == null || task.total == 0).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(42, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        if (worker?.isActive != true) pump()
        return START_STICKY
    }
    private fun pump() {
        worker = scope.launch {
            while (isActive) {
                val next = app.galleryDownloads.next(this) ?: break
                next.join()
            }
            withContext(Dispatchers.Main) {
                worker = null
                if (app.galleryDownloads.hasPending() && app.galleryDownloads.canRun) pump() else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(lastStart) }
            }
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        app.galleryDownloads.pauseForTimeout()
        scope.cancel(); stopSelf()
    }
    override fun onDestroy() { scope.cancel(); wakeLock?.let { if (it.isHeld) it.release() }; super.onDestroy() }
    companion object { private const val CHANNEL = "gallery-downloads" }
}
