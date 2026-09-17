package com.screenmirror.receiver.receiver

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.screenmirror.receiver.R
import com.screenmirror.receiver.signaling.MqttSignalingClient
import com.screenmirror.receiver.utils.AppPrefs
import com.screenmirror.receiver.webrtc.WebRtcManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.SurfaceViewRenderer
import java.net.UnknownHostException

/**
 * Displays the mirrored video stream and exposes controls.
 * Persists pairing code in SharedPreferences so user does not have to re-enter on each launch.
 * Retries MQTT connection up to 3 times on network errors.
 */
class ReceiverActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAIRING_CODE = "pairing_code"
        private const val TAG = "ReceiverActivity"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1500L
    }

    private lateinit var pairingCode: String
    private lateinit var renderer: SurfaceViewRenderer
    private lateinit var statusText: TextView
    private lateinit var btnScreen: Button
    private lateinit var btnCamera: Button
    private lateinit var btnMute: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnRetry: Button

    private var eglBase: EglBase? = null
    private var signaling: MqttSignalingClient? = null
    private var webRtcManager: WebRtcManager? = null
    private var isMuted = false
    private var prefs: AppPrefs? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)

        prefs = AppPrefs(this)

        pairingCode = intent.getStringExtra(EXTRA_PAIRING_CODE) ?: run {
            // No code passed — try saved one; if none, finish
            val saved = prefs?.getPairingCode()
            if (saved.isNullOrEmpty()) {
                Toast.makeText(this, "No pairing code", Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            saved
        }

        // Persist code for next launch
        prefs?.savePairingCode(pairingCode)

        renderer = findViewById(R.id.surfaceRenderer)
        statusText = findViewById(R.id.tvStatus)
        btnScreen = findViewById(R.id.btnScreen)
        btnCamera = findViewById(R.id.btnCamera)
        btnMute = findViewById(R.id.btnMute)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnRetry = findViewById(R.id.btnRetry)

        statusText.text = "Kode: $pairingCode\nMempersiapkan..."
        btnScreen.isEnabled = false
        btnCamera.isEnabled = false
        btnMute.isEnabled = false
        btnRetry.visibility = android.view.View.GONE

        btnScreen.setOnClickListener {
            webRtcManager?.requestSwitchSource(MqttSignalingClient.SOURCE_SCREEN)
            statusText.text = "Meminta view: Screen"
        }
        btnCamera.setOnClickListener {
            webRtcManager?.requestSwitchSource(MqttSignalingClient.SOURCE_FRONT_CAMERA)
            statusText.text = "Meminta view: Front Camera"
        }
        btnMute.setOnClickListener {
            isMuted = !isMuted
            webRtcManager?.requestMuteAudio(isMuted)
            btnMute.text = if (isMuted) "UNMUTE MIC" else "MUTE MIC"
        }
        btnDisconnect.setOnClickListener {
            prefs?.clearPairingCode()
            finish()
        }
        btnRetry.setOnClickListener {
            setupWebRtc()
        }

        setupWebRtc()
    }

    private fun setupWebRtc() {
        btnRetry.visibility = android.view.View.GONE
        lifecycleScope.launch {
            try {
                statusText.text = "Kode: $pairingCode\nMempersiapkan GL context..."
                eglBase?.release()
                eglBase = withContext(Dispatchers.Main) { EglBase.create() }
                if (eglBase == null) {
                    statusText.text = "Gagal: EGL context null. Device mungkin gak support OpenGL ES."
                    btnRetry.visibility = android.view.View.VISIBLE
                    return@launch
                }

                // Connect MQTT with retry
                statusText.text = "Kode: $pairingCode\nMenghubungkan ke broker MQTT..."
                val mqtt = MqttSignalingClient(pairingCode)
                mqtt.setStatusCallback { brokerStatus ->
                    // Update UI from broker callback (will be on IO dispatcher)
                    runOnUiThread {
                        if (!isFinishing) {
                            statusText.text = "Kode: $pairingCode\n$brokerStatus"
                        }
                    }
                }
                signaling = mqtt
                connectWithRetry(mqtt)
                val brokerInfo = mqtt.connectedBrokerHost?.let { " ($it)" } ?: ""
                statusText.text = "Kode: $pairingCode\nBroker:$brokerInfo\nInisialisasi WebRTC..."
                val manager = WebRtcManager(
                    context = this@ReceiverActivity,
                    eglBase = eglBase!!,
                    signaling = mqtt,
                    remoteRenderer = renderer
                )
                manager.initialize()
                webRtcManager = manager

                mqtt.incomingMessages.onEach { msg ->
                    webRtcManager?.handleIncomingMessage(msg)
                }.launchIn(lifecycleScope)

                manager.connectionState.onEach { state ->
                    when (state) {
                        WebRtcManager.ConnectionState.Ready -> {
                            statusText.text = "Kode: $pairingCode\nMenunggu sender..."
                            mqtt.sendReceiverHello()
                        }
                        WebRtcManager.ConnectionState.AnswerSent -> {
                            statusText.text = "Kode: $pairingCode\nMenunggu video stream..."
                        }
                        WebRtcManager.ConnectionState.VideoReady -> {
                            statusText.text = "Live"
                            btnScreen.isEnabled = true
                            btnCamera.isEnabled = true
                            btnMute.isEnabled = true
                        }
                        WebRtcManager.ConnectionState.Connected -> {
                            statusText.text = "Connected"
                        }
                        WebRtcManager.ConnectionState.Disconnected -> {
                            statusText.text = "Disconnected. Mencoba reconnect..."
                            btnScreen.isEnabled = false
                            btnCamera.isEnabled = false
                            btnMute.isEnabled = false
                        }
                        WebRtcManager.ConnectionState.Failed -> {
                            statusText.text = "Connection failed. Coba kode baru atau tekan RETRY."
                            btnRetry.visibility = android.view.View.VISIBLE
                        }
                    }
                }.launchIn(lifecycleScope)
            } catch (e: Throwable) {
                Log.e(TAG, "setup failed", e)
                val friendly = friendlyErrorMessage(e)
                statusText.text = "Error: $friendly"
                btnRetry.visibility = android.view.View.VISIBLE
            }
        }
    }

    private suspend fun connectWithRetry(mqtt: MqttSignalingClient) {
        var lastError: Throwable? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                statusText.text = "Kode: $pairingCode\nKoneksi ke broker (percobaan $attempt/$MAX_RETRIES)..."
                mqtt.connect()
                return
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "MQTT connect attempt $attempt failed: ${e.message}")
                if (attempt < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS)
                }
            }
        }
        throw lastError ?: RuntimeException("MQTT connection failed after $MAX_RETRIES attempts")
    }

    private fun friendlyErrorMessage(e: Throwable): String {
        // Unwrap nested exceptions (ExecutionException, etc.)
        var cause: Throwable = e
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        return when {
            cause is UnknownHostException -> "Semua broker DNS gagal. Cek koneksi internet (WiFi/data) atau coba jaringan lain."
            cause is java.util.concurrent.TimeoutException -> "Koneksi timeout (>15s). Coba lagi."
            cause is java.net.ConnectException -> "Tidak bisa connect ke broker. Mungkin di-block firewall."
            cause is java.net.SocketTimeoutException -> "Koneksi timeout. Coba lagi."
            cause is javax.net.ssl.SSLException -> "SSL handshake gagal. Coba jaringan lain."
            cause is com.hivemq.client.mqtt.exceptions.ConnectionFailedException -> {
                "MQTT koneksi gagal: ${cause.message ?: "unknown"}"
            }
            cause is UnsatisfiedLinkError -> "Native lib gagal load: ${cause.message}"
            cause is NoClassDefFoundError -> "Class tidak ditemukan: ${cause.message}"
            else -> "${cause.javaClass.simpleName}: ${cause.message ?: "no message"}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            webRtcManager?.dispose()
        } catch (e: Throwable) {
            Log.w(TAG, "dispose error", e)
        }
        lifecycleScope.launch {
            try { signaling?.disconnect() } catch (e: Throwable) { Log.w(TAG, "disconnect error", e) }
        }
    }
}
