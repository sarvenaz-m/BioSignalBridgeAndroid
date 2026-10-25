package dev.sarvenaz.biosignalbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import dev.sarvenaz.biosignalbridge.capture.SignalTransport
import dev.sarvenaz.biosignalbridge.capture.CapturePhase
import javax.inject.Inject
import kotlinx.coroutines.*

@AndroidEntryPoint
class CaptureService : Service() {
    @Inject lateinit var engine: BridgeEngine
    @Inject lateinit var capture: SignalTransport
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var opening: Job? = null
    private var observer: Job? = null
    private var requestToken = 0L
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Sensor connection", NotificationManager.IMPORTANCE_LOW))

    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            engine.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        val address = intent?.getStringExtra(ADDRESS)
        if (intent?.action != CONNECT || address.isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, CaptureService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("BioSignal Bridge")
            .setContentText("Sensor connection is active. Tap to review the session.")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(41, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(41, notification)
        observer?.cancel()
        opening?.cancel()
        val token = ++requestToken
        opening = engine.connect(address)
        observer = scope.launch {
            opening?.join()
            if (token != requestToken) return@launch
            capture.state.collect { state ->
                if (state.phase == CapturePhase.ERROR || state.phase == CapturePhase.DISCONNECTED) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        opening?.cancel()
        scope.cancel()
        if (engine.state.value.mode == dev.sarvenaz.biosignalbridge.ui.BridgeMode.LIVE) capture.disconnect()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val CONNECT = "dev.sarvenaz.biosignalbridge.CONNECT"
        const val STOP = "dev.sarvenaz.biosignalbridge.STOP"
        const val ADDRESS = "sensor-address"
        private const val CHANNEL = "sensor-connection"
    }
}
