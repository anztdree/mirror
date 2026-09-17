package com.screenmirror.receiver.webrtc

import android.content.Context
import android.util.Log
import com.screenmirror.receiver.signaling.MqttSignalingClient
import com.screenmirror.receiver.signaling.SignalingMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Receiver-side WebRTC manager.
 */
class WebRtcManager(
    private val context: Context,
    private val eglBase: EglBase,
    private val signaling: MqttSignalingClient,
    private val remoteRenderer: SurfaceViewRenderer
) {
    companion object {
        private const val TAG = "ReceiverWebRtc"
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
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var senderPeerId: String? = null
    @Volatile private var isRendererInit = false

    fun initialize() {
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

        remoteRenderer.init(eglBase.eglBaseContext, null)
        isRendererInit = true

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
                val sender = senderPeerId ?: return
                scope.launch {
                    val msg = SignalingMessage(
                        type = MqttSignalingClient.MSG_TYPE_ICE_CANDIDATE,
                        from = signaling.myId,
                        to = sender,
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex
                    )
                    signaling.sendTo(sender, msg)
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

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {
                val track = receiver?.track() ?: return
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    track.setEnabled(true)
                    remoteVideoTrack?.addSink(remoteRenderer)
                    _connectionState.tryEmit(ConnectionState.VideoReady)
                    Log.d(TAG, "Remote video track added")
                }
            }
        })
        if (pc == null) {
            Log.e(TAG, "createPeerConnection returned null")
            return
        }
        peerConnection = pc

        _connectionState.tryEmit(ConnectionState.Ready)
        Log.d(TAG, "WebRtcManager initialized")
    }

    fun handleIncomingMessage(msg: SignalingMessage) {
        if (msg.to.isNotEmpty() && msg.to != signaling.myId) return
        val pc = peerConnection ?: return

        when (msg.type) {
            MqttSignalingClient.MSG_TYPE_SDP_OFFER -> {
                senderPeerId = msg.from
                val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, msg.sdp)
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val answerConstraints = MediaConstraints().apply {
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                        }
                        pc.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(sdp: SessionDescription?) {
                                sdp ?: return
                                val sender = senderPeerId ?: return
                                pc.setLocalDescription(object : SdpObserver {
                                    override fun onSetSuccess() {
                                        scope.launch {
                                            signaling.sendTo(sender, SignalingMessage(
                                                type = MqttSignalingClient.MSG_TYPE_SDP_ANSWER,
                                                from = signaling.myId,
                                                to = sender,
                                                sdp = sdp.description,
                                                sdpType = "answer"
                                            ))
                                            _connectionState.tryEmit(ConnectionState.AnswerSent)
                                        }
                                    }
                                    override fun onSetFailure(error: String?) { Log.e(TAG, "setLocal failed: $error") }
                                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                                    override fun onCreateFailure(error: String?) {}
                                }, sdp)
                            }
                            override fun onCreateFailure(error: String?) { Log.e(TAG, "createAnswer failed: $error") }
                            override fun onSetSuccess() {}
                            override fun onSetFailure(error: String?) {}
                        }, answerConstraints)
                    }
                    override fun onSetFailure(error: String?) { Log.e(TAG, "setRemote failed: $error") }
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(error: String?) {}
                }, remoteSdp)
            }
            MqttSignalingClient.MSG_TYPE_ICE_CANDIDATE -> {
                val candidate = IceCandidate(msg.sdpMid, msg.sdpMLineIndex, msg.candidate)
                pc.addIceCandidate(candidate)
            }
        }
    }

    fun requestSwitchSource(source: String) {
        val sender = senderPeerId ?: return
        scope.launch {
            signaling.sendTo(sender, SignalingMessage(
                type = MqttSignalingClient.MSG_TYPE_SWITCH_SOURCE,
                from = signaling.myId,
                to = sender,
                source = source
            ))
        }
    }

    fun requestMuteAudio(muted: Boolean) {
        val sender = senderPeerId ?: return
        scope.launch {
            signaling.sendTo(sender, SignalingMessage(
                type = MqttSignalingClient.MSG_TYPE_MUTE_AUDIO,
                from = signaling.myId,
                to = sender,
                muted = muted
            ))
        }
    }

    fun dispose() {
        remoteVideoTrack?.removeSink(remoteRenderer)
        if (isRendererInit) {
            remoteRenderer.release()
            isRendererInit = false
        }
        peerConnection?.dispose()
        if (::factory.isInitialized) factory.dispose()
        audioDeviceModule?.release()
        eglBase.release()
        Log.d(TAG, "Disposed")
    }

    sealed class ConnectionState {
        object Ready : ConnectionState()
        object AnswerSent : ConnectionState()
        object VideoReady : ConnectionState()
        object Connected : ConnectionState()
        object Disconnected : ConnectionState()
        object Failed : ConnectionState()
    }
}
