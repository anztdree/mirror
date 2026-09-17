package com.screenmirror.sender.webrtc

import org.webrtc.PeerConnection

/**
 * Default PeerConnection.Observer implementation that does nothing.
 * Subclasses override only the methods they care about.
 */
open class PeerConnectionObserver : PeerConnection.Observer {
    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) {}
    override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>?) {}
    override fun onAddStream(stream: org.webrtc.MediaStream?) {}
    override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
    override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {}
}
