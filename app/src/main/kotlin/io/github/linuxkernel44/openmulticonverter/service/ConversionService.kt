package io.github.linuxkernel44.openmulticonverter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.linuxkernel44.openmulticonverter.MainActivity
import io.github.linuxkernel44.openmulticonverter.OmcApp
import io.github.linuxkernel44.openmulticonverter.core.ConversionJob
import io.github.linuxkernel44.openmulticonverter.core.JobState
import io.github.linuxkernel44.openmulticonverter.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service that keeps the process alive while the queue runs and shows progress. */
class ConversionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as OmcApp).engine
        if (intent?.action == ACTION_CANCEL) engine.cancelAll()
        createChannel()
        val n = buildNotification(engine.jobs.value)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, n)
        if (watcher?.isActive != true) {
            watcher = scope.launch {
                engine.jobs.collect { jobs ->
                    if (!engine.isActive) {
                        finish(jobs)
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(jobs))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun finish(jobs: List<ConversionJob>) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val ok = jobs.count { it.state == JobState.DONE }
        val failed = jobs.count { it.state == JobState.FAILED }
        if (ok + failed > 0) {
            val text = buildString {
                append("$ok converted")
                if (failed > 0) append(", $failed failed")
            }
            val n = NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Conversion finished")
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, n)
        }
        stopSelf()
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(jobs: List<ConversionJob>): Notification {
        val total = jobs.count { it.state != JobState.CANCELLED }
        val finished = jobs.count { it.state == JobState.DONE || it.state == JobState.FAILED }
        val running = jobs.firstOrNull { it.state == JobState.RUNNING }
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ConversionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Converting (${minOf(finished + 1, total)}/$total)")
            .setContentText(running?.sourceName ?: "Preparing…")
            .setProgress(100, ((running?.progress ?: 0f) * 100).toInt(), running == null || running.progress == 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Conversion progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Conversion results", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_DONE = "done"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CANCEL = "io.github.linuxkernel44.openmulticonverter.CANCEL"

        fun start(context: Context) {
            val i = Intent(context, ConversionService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}
