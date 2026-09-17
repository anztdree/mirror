package com.screenmirror.sender.webrtc

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import com.screenmirror.sender.signaling.MqttSignalingClient
import com.screenmirror.sender.signaling.SignalingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.EglBase14
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import android.content.Intent

/**
 * Manages WebRTC PeerConnection for SENDER side.
 *
 * Responsibilities:
 *  - Initialize PeerConnectionFactory + EGL context
 *  - Capture screen via MediaProjection (default source)
 *  - Capture front camera (alternate source, switchable)
 *  - Capture mic audio
 *  - Manage PeerConnection lifecycle with one or more receivers
 *  - Exchange SDP + ICE via MqttSignalingClient
 *  - Receive control messages (switch source, mute) via MQTT
 */
class WebRtcManager(
    private val context: Context,
    private val eglBase: EglBase,
    private val signaling: MqttSignalingClient,
    private val mediaProjectionIntent: Intent
) {
    companion object {
        private const val TAG = "WebRtcManager"
        private const val VIDEO_TRACK_ID = "screen_mirror_video"
        private const val AUDIO_TRACK_ID = "screen_mirror_audio"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30

        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _connectionState = MutableSharedFlow<ConnectionState>(extraBufferCapacity = 16)
    val connectionState: SharedFlow<ConnectionState> = _connectionState.asSharedFlow()

    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var currentReceiverId: String? = null

    private lateinit var videoSource: VideoSource
    private lateinit var videoTrack: VideoTrack
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var cameraCapturer: VideoCapturer? = null
    private var screenSurfaceHelper: SurfaceTextureHelper? = null
    private var cameraSurfaceHelper: SurfaceTextureHelper? = null
    private var currentSource: String = "screen"
    private var isMicMuted: Boolean = false

    private var audioDeviceModule: JavaAudioDeviceModule? = null

    @Volatile
    private var isInitialized = false

    fun initialize() {
        if (isInitialized) return
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        audioDeviceModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(
            eglBase.eglBaseContext
        )

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()

        videoSource = factory.createVideoSource(false)
        videoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource)
        videoTrack.setEnabled(true)

        startScreenCapture()

        isInitialized = true
        Log.d(TAG, "WebRtcManager initialized")
    }

    private fun startScreenCapture() {
        if (screenCapturer != null) {
            try { screenCapturer!!.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS) } catch (e: Exception) { Log.w(TAG, "start screen", e) }
            currentSource = "screen"
            return
        }
        screenSurfaceHelper = SurfaceTextureHelper.create(
            "screen-capture-thread", eglBase.eglBaseContext
        )
        screenCapturer = ScreenCapturerAndroid(
            mediaProjectionIntent,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection stopped by user / system")
                    _connectionState.tryEmit(ConnectionState.MediaProjectionStopped)
                }
            }
        )
        screenCapturer!!.initialize(
            screenSurfaceHelper!!, context, videoSource.capturerObserver
        )
        screenCapturer!!.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
        currentSource = "screen"
        Log.d(TAG, "Screen capture started")
    }

    private fun startFrontCameraCapture() {
        if (cameraCapturer == null) {
            cameraSurfaceHelper = SurfaceTextureHelper.create(
                "camera-capture-thread", eglBase.eglBaseContext
            )
            val enumerator: CameraEnumerator = Camera2Enumerator(context)
            val deviceName = enumerator.deviceNames.firstOrNull {
                enumerator.isFrontFacing(it)
            } ?: run {
                Log.e(TAG, "No front camera found")
                return
            }
            cameraCapturer = enumerator.createCapturer(deviceName, null)
            if (cameraCapturer == null) {
                Log.e(TAG, "Failed to create camera capturer")
                return
            }
        }
        cameraCapturer!!.initialize(
            cameraSurfaceHelper!!, context, videoSource.capturerObserver
        )
        try {
            cameraCapturer!!.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
            currentSource = "front_camera"
            Log.d(TAG, "Front camera capture started")
        } catch (e: Exception) {
            Log.e(TAG, "Camera startCapture failed", e)
        }
    }

    fun switchSource(target: String) {
        Log.d(TAG, "Switching source to $target (current=$currentSource)")
        if (target == currentSource) return
        when (target) {
            "screen" -> {
                try { cameraCapturer?.stopCapture() } catch (e: Exception) { Log.w(TAG, "stop cam", e) }
                startScreenCapture()
            }
            "front_camera" -> {
                try { screenCapturer?.stopCapture() } catch (e: Exception) { Log.w(TAG, "stop screen", e) }
                startFrontCameraCapture()
            }
        }
    }

    fun setMicMuted(muted: Boolean) {
        isMicMuted = muted
        audioDeviceModule?.let {
            try { it.setMicrophoneMute(muted) } catch (e: Exception) { Log.w(TAG, "mute mic", e) }
        }
        Log.d(TAG, "Mic muted=$muted")
    }

    fun createPeerConnectionForReceiver(receiverId: String) {
        if (!isInitialized) initialize()
        currentReceiverId = receiverId

        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            iceServers = ICE_SERVERS
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
        }

        val pc = factory.createPeerConnection(config, object : PeerConnectionObserver() {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate ?: return
                val rid = currentReceiverId ?: return
                scope.launch {
                    val msg = SignalingMessage(
                        type = MqttSignalingClient.MSG_TYPE_ICE_CANDIDATE,
                        from = signaling.myId,
                        to = rid,
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex
                    )
                    signaling.sendTo(rid, msg)
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d(TAG, "ICE state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED -> _connectionState.tryEmit(ConnectionState.Connected)
                    PeerConnection.IceConnectionState.DISCONNECTED -> _connectionState.tryEmit(ConnectionState.Disconnected)
                    PeerConnection.IceConnectionState.FAILED -> _connectionState.tryEmit(ConnectionState.Failed)
                    else -> {}
                }
            }
        })
        if (pc == null) {
            Log.e(TAG, "createPeerConnection returned null")
            return
        }
        peerConnection = pc

        // Add local tracks
        pc.addTrack(videoTrack)
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val audioSource = factory.createAudioSource(audioConstraints)
        val audioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        audioTrack.setEnabled(!isMicMuted)
        pc.addTrack(audioTrack)

        // Create offer using observer pattern (not suspend extension)
        val offerConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                pc.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        scope.launch {
                            val msg = SignalingMessage(
                                type = MqttSignalingClient.MSG_TYPE_SDP_OFFER,
                                from = signaling.myId,
                                to = receiverId,
                                sdp = sdp.description,
                                sdpType = "offer"
                            )
                            signaling.sendTo(receiverId, msg)
                            _connectionState.tryEmit(ConnectionState.OfferSent)
                        }
                    }
                    override fun onSetFailure(error: String?) { Log.e(TAG, "setLocalDesc failed: $error") }
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, sdp)
            }
            override fun onCreateFailure(error: String?) { Log.e(TAG, "createOffer failed: $error") }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, offerConstraints)
    }

    fun handleIncomingMessage(msg: SignalingMessage) {
        val pc = peerConnection ?: return
        when (msg.type) {
            MqttSignalingClient.MSG_TYPE_RECEIVER_HELLO -> {
                createPeerConnectionForReceiver(msg.from)
            }
            MqttSignalingClient.MSG_TYPE_SDP_ANSWER -> {
                msg.sdp?.let { sdpStr ->
                    val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, sdpStr)
                    pc.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() { _connectionState.tryEmit(ConnectionState.AnswerReceived) }
                        override fun onSetFailure(error: String?) { Log.e(TAG, "setRemoteDesc failed: $error") }
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onCreateFailure(error: String?) {}
                    }, remoteSdp)
                }
            }
            MqttSignalingClient.MSG_TYPE_ICE_CANDIDATE -> {
                val candidate = IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.candidate)
                pc.addIceCandidate(candidate)
            }
            MqttSignalingClient.MSG_TYPE_SWITCH_SOURCE -> {
                msg.source?.let { switchSource(it) }
            }
            MqttSignalingClient.MSG_TYPE_MUTE_AUDIO -> {
                setMicMuted(msg.muted)
            }
            MqttSignalingClient.MSG_TYPE_DISCONNECT -> {
                _connectionState.tryEmit(ConnectionState.PeerDisconnected)
            }
        }
    }

    fun dispose() {
        try { screenCapturer?.stopCapture() } catch (e: Exception) { Log.w(TAG, "stop screen", e) }
        try { cameraCapturer?.stopCapture() } catch (e: Exception) { Log.w(TAG, "stop cam", e) }
        peerConnection?.dispose()
        if (::videoTrack.isInitialized) videoTrack.dispose()
        if (::videoSource.isInitialized) videoSource.dispose()
        audioDeviceModule?.release()
        if (::factory.isInitialized) factory.dispose()
        screenSurfaceHelper?.dispose()
        cameraSurfaceHelper?.dispose()
        Log.d(TAG, "Disposed")
    }

    sealed class ConnectionState {
        object OfferSent : ConnectionState()
        object AnswerReceived : ConnectionState()
        object Connected : ConnectionState()
        object Disconnected : ConnectionState()
        object Failed : ConnectionState()
        object PeerDisconnected : ConnectionState()
        object MediaProjectionStopped : ConnectionState()
    }
}
