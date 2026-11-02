package app.radd.android

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.radd.MainActivity
import app.radd.R
import app.radd.vm.ScanState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Keeps the scan alive while the app is in the background. A visible notification with a Stop button is mandatory for this. */
class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            return START_NOT_STICKY
        }
        ensureChannel()
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(0, 0, true), type)
        } catch (_: Exception) {
            // The system refused a foreground service (for example started from the background): scan anyway while the process lives.
        }
        if (job?.isActive != true) {
            val graph = Graph.of(this)
            acquireWakeLock()
            job = scope.launch {
                val notifier = launch {
                    graph.controller.state.collectLatest { st ->
                        if (st is ScanState.Running) {
                            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                            nm?.notify(NOTIFICATION_ID, build((st.progress.percent * 100).toInt(), st.progress.found, st.progress.percent <= 0f))
                            delay(1000)
                        }
                    }
                }
                try {
                    graph.controller.run()
                } finally {
                    notifier.cancel()
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 ends a data-sync service after about 6 hours; stop cleanly, the results found so far are already saved. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "radd:scan").apply { acquire(6 * 60 * 60 * 1000L) }
        } catch (_: Exception) { }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
        wakeLock = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "فحص الذاكرة", NotificationManager.IMPORTANCE_LOW))
    }

    private fun build(percent: Int, found: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ScanService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_radd)
            .setContentTitle("ردّ بيفحص الذاكرة")
            .setContentText(if (found > 0) "لقى $found صورة لحد دلوقتي" else "بيدوّر في الذاكرة…")
            .setProgress(100, percent, indeterminate)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(0, "إيقاف", stop)
            .build()
    }

    companion object {
        const val CHANNEL = "scan"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "app.radd.STOP_SCAN"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ScanService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScanService::class.java).setAction(ACTION_STOP))
        }
    }
}
